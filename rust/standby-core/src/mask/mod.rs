use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const GRID_W: usize = 128;
pub const GRID_H: usize = 72;
pub const GRID_CHUNKS_PER_ROW: usize = GRID_W / 32; // 4
pub const GRID_LEN: usize = GRID_H * GRID_CHUNKS_PER_ROW; // 288

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MaskProcessOptions {
    pub invert: bool,
    pub choke: f32,
    pub smooth_radius: f32, // < 0 for auto (1.5 lowres, 0.8 hires)
    pub is_low_res_model: bool,
}

impl Default for MaskProcessOptions {
    fn default() -> Self {
        Self {
            invert: false,
            choke: 1.5,
            smooth_radius: -1.0,
            is_low_res_model: false,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ProcessedMask {
    pub width: u32,
    pub height: u32,
    pub rgba_bytes: Vec<u8>,
    pub occupancy_grid: Vec<u32>,
}

pub fn compute_mask_cache_key(
    image_tag: &str,
    model_name: &str,
    invert: bool,
    choke: f32,
) -> String {
    let payload = format!(
        "{}:{}:{}:{:.1}",
        image_tag,
        model_name,
        if invert { 1 } else { 0 },
        choke
    );
    let mut hasher = Sha256::new();
    hasher.update(payload.as_bytes());
    let digest = hasher.finalize();
    let mut hex = String::with_capacity(64);
    for b in digest {
        hex.push_str(&format!("{b:02x}"));
    }
    hex
}

fn morph_3x3(src: &[u8], w: usize, h: usize, is_min: bool) -> Vec<u8> {
    let mut dst = vec![0u8; w * h];
    for y in 0..h {
        let y0 = y.saturating_sub(1);
        let y1 = (y + 1).min(h - 1);
        for x in 0..w {
            let x0 = x.saturating_sub(1);
            let x1 = (x + 1).min(w - 1);
            let mut acc = if is_min { 255u8 } else { 0u8 };
            for ny in y0..=y1 {
                let row = ny * w;
                for nx in x0..=x1 {
                    let v = src[row + nx];
                    acc = if is_min { acc.min(v) } else { acc.max(v) };
                }
            }
            dst[y * w + x] = acc;
        }
    }
    dst
}

fn gaussian_blur_separable(src: &[u8], w: usize, h: usize, radius: f32) -> Vec<u8> {
    if radius <= 0.05 || w == 0 || h == 0 {
        return src.to_vec();
    }
    let sigma = (radius / 1.5).max(0.4);
    let half_k = (radius.ceil() as isize).clamp(1, 6);
    let mut kernel = Vec::with_capacity((half_k * 2 + 1) as usize);
    let mut sum = 0.0f32;
    for k in -half_k..=half_k {
        let kf = k as f32;
        let w_val = (-0.5 * (kf * kf) / (sigma * sigma)).exp();
        kernel.push(w_val);
        sum += w_val;
    }
    for kw in &mut kernel {
        *kw /= sum;
    }

    let mut tmp = vec![0.0f32; w * h];
    for y in 0..h {
        let row = y * w;
        for x in 0..w {
            let mut acc = 0.0f32;
            for (ki, kw) in kernel.iter().enumerate() {
                let dx = ki as isize - half_k;
                let sx = (x as isize + dx).clamp(0, (w - 1) as isize) as usize;
                acc += (src[row + sx] as f32) * kw;
            }
            tmp[row + x] = acc;
        }
    }

    let mut out = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0.0f32;
            for (ki, kw) in kernel.iter().enumerate() {
                let dy = ki as isize - half_k;
                let sy = (y as isize + dy).clamp(0, (h - 1) as isize) as usize;
                acc += tmp[sy * w + x] * kw;
            }
            out[y * w + x] = acc.round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}

/// Ports `rembg_runner.py:process_mask` + `generate_occupancy_grid`:
/// 1) optional invert, 2) morphological choke, 3) smoothstep [28..210],
/// 4) Gaussian AA blur, 5) recombine alpha with original RGB, 6) 128x72 bitmask grid.
pub fn process_mask_rgba(
    alpha_bytes: &[u8],
    orig_rgba: &[u8],
    width: u32,
    height: u32,
    opts: &MaskProcessOptions,
) -> Result<ProcessedMask, String> {
    let w = width as usize;
    let h = height as usize;
    let pixel_count = w.checked_mul(h).ok_or("Dimensiones desbordadas")?;
    if pixel_count == 0 || alpha_bytes.len() != pixel_count || orig_rgba.len() != pixel_count * 4 {
        return Err(format!(
            "Tamaños incompatibles para {}x{}: alpha={}, rgba={}",
            width,
            height,
            alpha_bytes.len(),
            orig_rgba.len()
        ));
    }

    let mut alpha: Vec<u8> = if opts.invert {
        alpha_bytes.iter().map(|&a| 255 - a).collect()
    } else {
        alpha_bytes.to_vec()
    };

    // 1. Morphological choke
    if opts.choke >= 1.0 {
        alpha = morph_3x3(&alpha, w, h, true);
        if opts.choke >= 2.0 {
            alpha = morph_3x3(&alpha, w, h, true);
        }
    } else if opts.choke <= -1.0 {
        alpha = morph_3x3(&alpha, w, h, false);
        if opts.choke <= -2.0 {
            alpha = morph_3x3(&alpha, w, h, false);
        }
    }

    // 2. Smoothstep [28..210] contrast curve
    for a in &mut alpha {
        let norm = ((*a as f32 - 28.0) / (210.0 - 28.0)).clamp(0.0, 1.0);
        let smooth = norm * norm * (3.0 - 2.0 * norm) * 255.0;
        *a = smooth.round().clamp(0.0, 255.0) as u8;
    }

    // 3. Gaussian AA blur
    let radius = if opts.smooth_radius < 0.0 {
        if opts.is_low_res_model {
            1.5
        } else {
            0.8
        }
    } else {
        opts.smooth_radius
    };
    if radius > 0.0 {
        alpha = gaussian_blur_separable(&alpha, w, h, radius);
    }

    // 4. Recombine alpha with original RGB
    let mut out_rgba = Vec::with_capacity(pixel_count * 4);
    for i in 0..pixel_count {
        let idx = i * 4;
        out_rgba.push(orig_rgba[idx]);
        out_rgba.push(orig_rgba[idx + 1]);
        out_rgba.push(orig_rgba[idx + 2]);
        out_rgba.push(alpha[i]);
    }

    let occupancy_grid = generate_occupancy_grid(&alpha, width, height);

    Ok(ProcessedMask {
        width,
        height,
        rgba_bytes: out_rgba,
        occupancy_grid,
    })
}

/// Generates 288 uint32 words representing a 128x72 foreground occupancy bitmask.
pub fn generate_occupancy_grid(alpha: &[u8], width: u32, height: u32) -> Vec<u32> {
    let w = width as usize;
    let h = height as usize;
    if w == 0 || h == 0 || alpha.len() != w * h {
        return vec![0; GRID_LEN];
    }
    let mut grid = Vec::with_capacity(GRID_LEN);

    for r in 0..GRID_H {
        let fy = ((r as f32 + 0.5) / (GRID_H as f32)) * (h as f32) - 0.5;
        let y0 = (fy.floor() as isize).clamp(0, (h - 1) as isize) as usize;
        let y1 = (y0 + 1).min(h - 1);
        let wy = (fy - y0 as f32).clamp(0.0, 1.0);

        for chunk_i in 0..GRID_CHUNKS_PER_ROW {
            let mut chunk: u32 = 0;
            for b in 0..32 {
                let c = chunk_i * 32 + b;
                let fx = ((c as f32 + 0.5) / (GRID_W as f32)) * (w as f32) - 0.5;
                let x0 = (fx.floor() as isize).clamp(0, (w - 1) as isize) as usize;
                let x1 = (x0 + 1).min(w - 1);
                let wx = (fx - x0 as f32).clamp(0.0, 1.0);

                let a00 = alpha[y0 * w + x0] as f32;
                let a10 = alpha[y0 * w + x1] as f32;
                let a01 = alpha[y1 * w + x0] as f32;
                let a11 = alpha[y1 * w + x1] as f32;

                let top = a00 + (a10 - a00) * wx;
                let bot = a01 + (a11 - a01) * wx;
                let val = top + (bot - top) * wy;
                if val > 100.0 {
                    chunk |= 1u32 << b;
                }
            }
            grid.push(chunk);
        }
    }
    grid
}

/// Computes normalized occlusion ratio [0.0..1.0] for UV rect (u0, v0)..(u1, v1).
pub fn compute_occlusion_ratio(grid: &[u32], u0: f32, v0: f32, u1: f32, v1: f32) -> f32 {
    if grid.len() < GRID_LEN {
        return 0.0;
    }
    let min_u = u0.min(u1).clamp(0.0, 1.0);
    let max_u = u0.max(u1).clamp(0.0, 1.0);
    let min_v = v0.min(v1).clamp(0.0, 1.0);
    let max_v = v0.max(v1).clamp(0.0, 1.0);

    let col0 = ((min_u * (GRID_W as f32)).floor() as usize).min(GRID_W - 1);
    let col1 = ((max_u * (GRID_W as f32)).ceil() as usize)
        .saturating_sub(1)
        .max(col0)
        .min(GRID_W - 1);
    let row0 = ((min_v * (GRID_H as f32)).floor() as usize).min(GRID_H - 1);
    let row1 = ((max_v * (GRID_H as f32)).ceil() as usize)
        .saturating_sub(1)
        .max(row0)
        .min(GRID_H - 1);

    let mut total = 0u32;
    let mut occluded = 0u32;
    for r in row0..=row1 {
        let row_off = r * GRID_CHUNKS_PER_ROW;
        for c in col0..=col1 {
            total += 1;
            let word = grid[row_off + (c >> 5)];
            if (word & (1u32 << (c & 31))) != 0 {
                occluded += 1;
            }
        }
    }
    if total == 0 {
        0.0
    } else {
        (occluded as f32) / (total as f32)
    }
}

/// Evaluates Smart Depth occlusion threshold (e.g. `limit_50` at 50%) with ±0.005 hysteresis.
pub fn evaluate_depth_limit(ratio: f32, threshold_pct: f32, prev_over: bool) -> bool {
    let thresh = (threshold_pct.clamp(5.0, 95.0)) / 100.0;
    let hyst = 0.005f32;
    if prev_over {
        ratio >= (thresh - hyst)
    } else {
        ratio > (thresh + hyst)
    }
}
