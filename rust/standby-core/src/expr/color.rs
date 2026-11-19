use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use super::ast::{node_to_string, parse_ast, AstNode};
use super::eval::{eval_ast, eval_node, EvalContext};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RgbaColor {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub a: f32,
}

impl RgbaColor {
    pub const WHITE: Self = Self {
        r: 1.0,
        g: 1.0,
        b: 1.0,
        a: 1.0,
    };
    pub const BLACK: Self = Self {
        r: 0.0,
        g: 0.0,
        b: 0.0,
        a: 1.0,
    };

    pub fn from_argb_u32(argb: u32) -> Self {
        let a = ((argb >> 24) & 0xFF) as f32 / 255.0;
        let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
        let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
        let b = (argb & 0xFF) as f32 / 255.0;
        Self { r, g, b, a }
    }

    pub fn to_argb_u32(self) -> u32 {
        let a = (self.a.clamp(0.0, 1.0) * 255.0).round() as u32;
        let r = (self.r.clamp(0.0, 1.0) * 255.0).round() as u32;
        let g = (self.g.clamp(0.0, 1.0) * 255.0).round() as u32;
        let b = (self.b.clamp(0.0, 1.0) * 255.0).round() as u32;
        (a << 24) | (r << 16) | (g << 8) | b
    }

    pub fn to_hex_argb(self) -> String {
        let a = (self.a.clamp(0.0, 1.0) * 255.0).round() as u8;
        let r = (self.r.clamp(0.0, 1.0) * 255.0).round() as u8;
        let g = (self.g.clamp(0.0, 1.0) * 255.0).round() as u8;
        let b = (self.b.clamp(0.0, 1.0) * 255.0).round() as u8;
        if a == 255 {
            format!("#{r:02x}{g:02x}{b:02x}")
        } else {
            format!("#{a:02x}{r:02x}{g:02x}{b:02x}")
        }
    }

    pub fn with_alpha(mut self, alpha: f32) -> Self {
        self.a = alpha.clamp(0.0, 1.0);
        self
    }

    pub fn lerp(self, other: Self, t: f32) -> Self {
        let f = t.clamp(0.0, 1.0);
        Self {
            r: self.r + (other.r - self.r) * f,
            g: self.g + (other.g - self.g) * f,
            b: self.b + (other.b - self.b) * f,
            a: self.a + (other.a - self.a) * f,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MaterialYouPalette {
    pub primary: u32,
    pub on_primary: u32,
    pub primary_container: u32,
    pub on_primary_container: u32,
    pub secondary: u32,
    pub on_secondary: u32,
    pub secondary_container: u32,
    pub tertiary: u32,
    pub tertiary_container: u32,
    pub surface: u32,
    pub surface_variant: u32,
    pub surface_container: u32,
    pub on_surface: u32,
    pub on_surface_variant: u32,
    pub background: u32,
    pub outline: u32,
    pub error: u32,
    #[serde(default)]
    pub custom_tokens: HashMap<String, u32>,
}

impl Default for MaterialYouPalette {
    fn default() -> Self {
        Self {
            primary: 0xFFA8C7FA,
            on_primary: 0xFF062E6F,
            primary_container: 0xFF0842A0,
            on_primary_container: 0xFFD3E3FD,
            secondary: 0xFFBEC6DC,
            on_secondary: 0xFF283041,
            secondary_container: 0xFF3E4759,
            tertiary: 0xFFDDBCE0,
            tertiary_container: 0xFF573E5B,
            surface: 0xFF111318,
            surface_variant: 0xFF44474F,
            surface_container: 0xFF1D2026,
            on_surface: 0xFFE2E2E9,
            on_surface_variant: 0xFFC4C6D0,
            background: 0xFF000000,
            outline: 0xFF8E9099,
            error: 0xFFFFB4AB,
            custom_tokens: HashMap::new(),
        }
    }
}

impl MaterialYouPalette {
    pub fn resolve_token(&self, token: &str) -> Option<RgbaColor> {
        let lower = token.trim().to_ascii_lowercase();
        let argb = match lower.as_str() {
            "white" => return Some(RgbaColor::WHITE),
            "black" => return Some(RgbaColor::BLACK),
            "transparent" => return Some(RgbaColor { r: 0.0, g: 0.0, b: 0.0, a: 0.0 }),
            "primary" | "accent" => self.primary,
            "onprimary" | "on_primary" => self.on_primary,
            "primarycontainer" | "primary_container" => self.primary_container,
            "onprimarycontainer" | "on_primary_container" => self.on_primary_container,
            "secondary" => self.secondary,
            "onsecondary" | "on_secondary" => self.on_secondary,
            "secondarycontainer" | "secondary_container" => self.secondary_container,
            "tertiary" => self.tertiary,
            "tertiarycontainer" | "tertiary_container" => self.tertiary_container,
            "surface" => self.surface,
            "surfacevariant" | "surface_variant" => self.surface_variant,
            "surfacecontainer" | "surface_container" => self.surface_container,
            "onsurface" | "on_surface" | "text" => self.on_surface,
            "onsurfacevariant" | "on_surface_variant" | "muted" => self.on_surface_variant,
            "background" => self.background,
            "outline" | "border" => self.outline,
            "error" => self.error,
            // CripertoShell Pywal compatibility tokens mapped cleanly onto Material You roles
            "color1" | "color9" => self.error,
            "color2" | "color10" => self.tertiary,
            "color3" | "color11" => self.secondary,
            "color4" | "color12" => self.primary,
            "color5" | "color13" => self.primary_container,
            "color6" | "color14" => self.tertiary_container,
            "color7" | "color15" => self.on_surface,
            "color8" => self.outline,
            other => {
                if let Some(&v) = self.custom_tokens.get(other) {
                    v
                } else {
                    return None;
                }
            }
        };
        Some(RgbaColor::from_argb_u32(argb))
    }
}

pub fn hsv_to_rgba(h: f32, s: f32, v: f32, a: f32) -> RgbaColor {
    let h = h.rem_euclid(1.0);
    let s = s.clamp(0.0, 1.0);
    let v = v.clamp(0.0, 1.0);
    let i = (h * 6.0).floor() as i32;
    let f = h * 6.0 - (i as f32);
    let p = v * (1.0 - s);
    let q = v * (1.0 - f * s);
    let t = v * (1.0 - (1.0 - f) * s);
    let (r, g, b) = match i.rem_euclid(6) {
        0 => (v, t, p),
        1 => (q, v, p),
        2 => (p, v, t),
        3 => (p, q, v),
        4 => (t, p, v),
        _ => (v, p, q),
    };
    RgbaColor {
        r,
        g,
        b,
        a: a.clamp(0.0, 1.0),
    }
}

pub fn parse_hex_color(s: &str) -> Option<RgbaColor> {
    let hex = s.trim().strip_prefix('#').unwrap_or_else(|| s.trim());
    if hex.len() == 6 && hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        let v = u32::from_str_radix(hex, 16).ok()?;
        return Some(RgbaColor::from_argb_u32(0xFF00_0000 | v));
    }
    if hex.len() == 8 && hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        let v = u32::from_str_radix(hex, 16).ok()?;
        return Some(RgbaColor::from_argb_u32(v));
    }
    None
}

pub fn parse_any_color(input: &str, palette: &MaterialYouPalette) -> RgbaColor {
    let mut s = input.trim();
    if (s.starts_with('"') && s.ends_with('"')) || (s.starts_with('\'') && s.ends_with('\'')) {
        if s.len() >= 2 {
            s = s[1..s.len() - 1].trim();
        }
    }
    if s.is_empty() {
        return RgbaColor::from_argb_u32(palette.on_surface);
    }
    if let Some(c) = parse_hex_color(s) {
        return c;
    }

    let lower = s.to_ascii_lowercase();
    if let Some(inner) = lower
        .strip_prefix("rgba")
        .or_else(|| lower.strip_prefix("rgb"))
        .and_then(|r| r.trim().strip_prefix('('))
        .and_then(|r| r.strip_suffix(')'))
    {
        let parts: Vec<f32> = inner
            .split(',')
            .filter_map(|p| p.trim().parse::<f32>().ok())
            .collect();
        if parts.len() >= 3 {
            let max_rgb = parts[0].max(parts[1]).max(parts[2]);
            let scale = if max_rgb > 1.0 { 1.0 / 255.0 } else { 1.0 };
            let a = parts.get(3).copied().unwrap_or(1.0);
            let a_norm = if a > 1.0 { a / 255.0 } else { a };
            return RgbaColor {
                r: (parts[0] * scale).clamp(0.0, 1.0),
                g: (parts[1] * scale).clamp(0.0, 1.0),
                b: (parts[2] * scale).clamp(0.0, 1.0),
                a: a_norm.clamp(0.0, 1.0),
            };
        }
    }

    if let Some(inner) = lower
        .strip_prefix("hsva")
        .or_else(|| lower.strip_prefix("hsv"))
        .and_then(|r| r.trim().strip_prefix('('))
        .and_then(|r| r.strip_suffix(')'))
    {
        let parts: Vec<f32> = inner
            .split(',')
            .filter_map(|p| p.trim().parse::<f32>().ok())
            .collect();
        if parts.len() >= 3 {
            let h = if parts[0] > 1.0 {
                parts[0] / 360.0
            } else {
                parts[0]
            };
            let s_val = if parts[1] > 1.0 {
                parts[1] / 100.0
            } else {
                parts[1]
            };
            let v_val = if parts[2] > 1.0 {
                parts[2] / 100.0
            } else {
                parts[2]
            };
            let a = parts.get(3).copied().unwrap_or(1.0);
            return hsv_to_rgba(h, s_val, v_val, a);
        }
    }

    palette
        .resolve_token(s)
        .unwrap_or_else(|| RgbaColor::from_argb_u32(palette.on_surface))
}

fn eval_color_arg(node: &AstNode, ctx: &EvalContext) -> RgbaColor {
    match node {
        AstNode::Str(s) | AstNode::Ident(s) => {
            if let Some(c) = parse_hex_color(s) {
                return c;
            }
            if let Some(c) = ctx.palette.resolve_token(s) {
                return c;
            }
            // Might be an identifier pointing to a hex string variable (e.g. wizHex)
            if let Some(val) = ctx.resolve_string_var(s) {
                if let Some(c) = parse_hex_color(&val) {
                    return c;
                }
            }
            parse_any_color(s, &ctx.palette)
        }
        AstNode::Call { .. } => {
            let as_str = node_to_string(node);
            eval_color_expr(&as_str, ctx, RgbaColor::WHITE)
        }
        _ => {
            let val = eval_ast(node, ctx);
            parse_any_color(&val.as_str(), &ctx.palette)
        }
    }
}

/// Evaluates color expressions: mix(cA, cB, t), when/if(cond, cA, cB), pulse(cA, cB, period, ease),
/// battery(cEmpty, cFull), isAudioPlaying(cPlay, cPause), hover/press, or literal hex/token.
pub fn eval_color_expr(expr: &str, ctx: &EvalContext, base_color: RgbaColor) -> RgbaColor {
    let s = expr.trim();
    if s.is_empty() {
        return base_color;
    }
    if parse_hex_color(s).is_some() || ctx.palette.resolve_token(s).is_some() {
        return parse_any_color(s, &ctx.palette);
    }
    let lower = s.to_ascii_lowercase();
    if lower.starts_with("rgb") || lower.starts_with("hsv") {
        return parse_any_color(s, &ctx.palette);
    }

    // Check if the expression contains `#rrggbb` bare literals inside function calls; quote them cleanly for Pratt parser
    let normalized = quote_hex_literals(s);
    let Ok(ast) = parse_ast(&normalized) else {
        return parse_any_color(s, &ctx.palette);
    };

    eval_color_ast(&ast, ctx, base_color)
}

fn eval_color_ast(ast: &AstNode, ctx: &EvalContext, base_color: RgbaColor) -> RgbaColor {
    match ast {
        AstNode::Call { name, args } => {
            let fn_name = name.to_ascii_lowercase();
            match fn_name.as_str() {
                "mix" if args.len() >= 3 => {
                    let c_a = eval_color_arg(&args[0], ctx);
                    let c_b = eval_color_arg(&args[1], ctx);
                    let t = eval_ast(&args[2], ctx).as_num() as f32;
                    c_a.lerp(c_b, t)
                }
                "when" | "if" if args.len() >= 2 => {
                    let cond = eval_ast(&args[0], ctx).as_bool();
                    if cond {
                        eval_color_arg(&args[1], ctx)
                    } else if args.len() >= 3 {
                        eval_color_arg(&args[2], ctx)
                    } else {
                        base_color
                    }
                }
                "pulse" if args.len() >= 2 => {
                    let c_a = eval_color_arg(&args[0], ctx);
                    let c_b = eval_color_arg(&args[1], ctx);
                    let period = args
                        .get(2)
                        .map(|n| eval_ast(n, ctx).as_num().max(0.05))
                        .unwrap_or(2.0);
                    let ease_name = args
                        .get(3)
                        .map(|n| node_to_string(n))
                        .unwrap_or_else(|| "InOutSine".to_string());
                    let phase = (ctx.t / period).fract();
                    let tri = if phase < 0.5 {
                        phase * 2.0
                    } else {
                        (1.0 - phase) * 2.0
                    };
                    let eased = super::easing::evaluate_ease(&ease_name, tri) as f32;
                    c_a.lerp(c_b, eased)
                }
                "battery" if args.len() >= 2 => {
                    let c_a = eval_color_arg(&args[0], ctx);
                    let c_b = eval_color_arg(&args[1], ctx);
                    let t = (ctx.battery / 100.0).clamp(0.0, 1.0) as f32;
                    c_a.lerp(c_b, t)
                }
                "isaudioplaying" if args.len() >= 2 => {
                    if ctx.is_playing {
                        eval_color_arg(&args[0], ctx)
                    } else {
                        eval_color_arg(&args[1], ctx)
                    }
                }
                "hover" if !args.is_empty() => {
                    if args.len() == 1 {
                        if ctx.is_hovered {
                            eval_color_arg(&args[0], ctx)
                        } else {
                            base_color
                        }
                    } else if ctx.is_hovered {
                        eval_color_arg(&args[1], ctx)
                    } else {
                        eval_color_arg(&args[0], ctx)
                    }
                }
                "press" if !args.is_empty() => {
                    if args.len() == 1 {
                        if ctx.is_pressed {
                            eval_color_arg(&args[0], ctx)
                        } else {
                            base_color
                        }
                    } else if ctx.is_pressed {
                        eval_color_arg(&args[1], ctx)
                    } else {
                        eval_color_arg(&args[0], ctx)
                    }
                }
                "once" | "intro" if args.len() >= 2 => eval_color_arg(&args[1], ctx),
                "toggle" if args.len() >= 2 => {
                    let idx = eval_node(&node_to_string(ast), ctx).round() as usize;
                    if idx % 2 == 1 {
                        eval_color_arg(&args[1], ctx)
                    } else {
                        eval_color_arg(&args[0], ctx)
                    }
                }
                "rgb" | "rgba" | "hsv" | "hsva" => parse_any_color(&node_to_string(ast), &ctx.palette),
                _ => {
                    let s_res = eval_ast(ast, ctx).as_str();
                    parse_any_color(&s_res, &ctx.palette)
                }
            }
        }
        AstNode::Ternary {
            cond,
            consequent,
            alternate,
        } => {
            if eval_ast(cond, ctx).as_bool() {
                eval_color_ast(consequent, ctx, base_color)
            } else {
                eval_color_ast(alternate, ctx, base_color)
            }
        }
        _ => eval_color_arg(ast, ctx),
    }
}

/// Converts unquoted `#rrggbb` or `#aarrggbb` inside expressions into `"#rrggbb"` so Pratt parsing works cleanly.
fn quote_hex_literals(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 8);
    let chars: Vec<char> = input.chars().collect();
    let mut i = 0;
    let mut in_quote: Option<char> = None;

    while i < chars.len() {
        let c = chars[i];
        if let Some(q) = in_quote {
            out.push(c);
            if c == q && (i == 0 || chars[i - 1] != '\\') {
                in_quote = None;
            }
            i += 1;
            continue;
        }
        if c == '"' || c == '\'' {
            in_quote = Some(c);
            out.push(c);
            i += 1;
            continue;
        }
        if c == '#' {
            let start = i + 1;
            let mut end = start;
            while end < chars.len() && chars[end].is_ascii_hexdigit() {
                end += 1;
            }
            let hex_len = end - start;
            if hex_len == 6 || hex_len == 8 {
                out.push('"');
                for j in i..end {
                    out.push(chars[j]);
                }
                out.push('"');
                i = end;
                continue;
            }
        }
        out.push(c);
        i += 1;
    }
    out
}
