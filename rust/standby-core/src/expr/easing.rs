use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PropertyType {
    Angle,
    Offset,
    Size,
    Scale,
    Opacity,
    Anim,
    Other,
}

pub fn get_property_type(prop_name: &str) -> PropertyType {
    match prop_name {
        "rotation" | "perspectiveX" | "perspectiveY" | "circleStart" | "circleSweep" => {
            PropertyType::Angle
        }
        "posX" | "posY" | "groupOffsetX" | "groupOffsetY" | "hitboxOffsetX" | "hitboxOffsetY" => {
            PropertyType::Offset
        }
        "width" | "height" | "baseWidth" | "baseHeight" | "groupWidth" | "groupHeight"
        | "fontSize" | "baseFontSize" | "letterSpacing" | "strokeWidth" | "circleRadius"
        | "radiusUniform" | "radiusTopLeft" | "radiusTopRight" | "radiusBottomLeft"
        | "radiusBottomRight" | "depthCutoutThreshold" | "perspectiveDistance" | "maskFeather"
        | "hitboxWidth" | "hitboxHeight" => PropertyType::Size,
        "scaleX" | "scaleY" | "uniformScale" | "parallaxFactor" | "pivotX" | "pivotY" => {
            PropertyType::Scale
        }
        "elementOpacity" | "fillAlpha" | "strokeAlpha" => PropertyType::Opacity,
        "anim" => PropertyType::Anim,
        _ => PropertyType::Other,
    }
}

pub fn get_default_epsilon(prop_name: &str) -> f64 {
    match get_property_type(prop_name) {
        PropertyType::Offset | PropertyType::Size => 0.5,
        PropertyType::Angle => 0.1,
        PropertyType::Scale => 0.001,
        PropertyType::Opacity | PropertyType::Anim => 0.002,
        PropertyType::Other => 0.5,
    }
}

pub fn is_additive_property(prop_name: &str) -> bool {
    matches!(
        get_property_type(prop_name),
        PropertyType::Angle | PropertyType::Offset
    )
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum MotionKind {
    Spring {
        tension: f64,
        damping: f64,
        mass: f64,
        epsilon: f64,
        modulus: f64,
    },
    Ease {
        curve: String,
        duration_ms: u32,
        bezier: Option<[f64; 4]>,
    },
}

impl Default for MotionKind {
    fn default() -> Self {
        Self::Ease {
            curve: "OutCubic".to_string(),
            duration_ms: 150,
            bezier: None,
        }
    }
}

pub fn resolve_easing(spec: &str, prop_name: &str) -> MotionKind {
    let mut s = spec.trim();
    if (s.starts_with('"') && s.ends_with('"')) || (s.starts_with('\'') && s.ends_with('\'')) {
        if s.len() >= 2 {
            s = s[1..s.len() - 1].trim();
        }
    }
    if s.is_empty() {
        return MotionKind::default();
    }

    let lower = s.to_ascii_lowercase();
    let default_eps = get_default_epsilon(prop_name);

    match lower.as_str() {
        "springsmooth" => {
            return MotionKind::Spring {
                tension: 7.5,
                damping: 0.92,
                mass: 1.0,
                epsilon: default_eps,
                modulus: 0.0,
            }
        }
        "springsoft" => {
            return MotionKind::Spring {
                tension: 6.0,
                damping: 0.8,
                mass: 1.0,
                epsilon: default_eps,
                modulus: 0.0,
            }
        }
        "springsnappy" => {
            return MotionKind::Spring {
                tension: 14.0,
                damping: 0.7,
                mass: 1.0,
                epsilon: default_eps,
                modulus: 0.0,
            }
        }
        "springbouncy" => {
            return MotionKind::Spring {
                tension: 10.0,
                damping: 0.35,
                mass: 1.0,
                epsilon: default_eps,
                modulus: 0.0,
            }
        }
        "spring" => {
            return MotionKind::Spring {
                tension: 10.0,
                damping: 0.65,
                mass: 1.0,
                epsilon: default_eps,
                modulus: 0.0,
            }
        }
        _ => {}
    }

    for prefix in [
        "springsmooth",
        "springsoft",
        "springsnappy",
        "springbouncy",
        "spring",
    ] {
        if let Some(rest) = lower.strip_prefix(prefix) {
            let rest_trim = rest.trim();
            if rest_trim.starts_with('(') && rest_trim.ends_with(')') {
                let (base_k, base_d) = match prefix {
                    "springsmooth" => (7.5, 0.92),
                    "springsoft" => (6.0, 0.8),
                    "springsnappy" => (14.0, 0.7),
                    "springbouncy" => (10.0, 0.35),
                    _ => (10.0, 0.65),
                };
                let inner = rest_trim[1..rest_trim.len() - 1].trim();
                if inner.is_empty() {
                    return MotionKind::Spring {
                        tension: base_k,
                        damping: base_d,
                        mass: 1.0,
                        epsilon: default_eps,
                        modulus: 0.0,
                    };
                }
                let parts: Vec<Option<f64>> =
                    inner.split(',').map(|p| p.trim().parse::<f64>().ok()).collect();
                let tension = parts.first().and_then(|v| *v).unwrap_or(base_k);
                let damping = parts.get(1).and_then(|v| *v).unwrap_or(base_d);
                let mass = parts.get(2).and_then(|v| *v).unwrap_or(1.0);
                let epsilon = parts.get(3).and_then(|v| *v).unwrap_or(default_eps);
                let modulus = parts.get(4).and_then(|v| *v).unwrap_or(0.0);
                return MotionKind::Spring {
                    tension,
                    damping,
                    mass,
                    epsilon,
                    modulus,
                };
            }
        }
    }

    let bezier_inner = if let Some(r) = lower.strip_prefix("cubic-bezier") {
        r.trim().strip_prefix('(').and_then(|x| x.strip_suffix(')'))
    } else if let Some(r) = lower.strip_prefix("bezier") {
        r.trim().strip_prefix('(').and_then(|x| x.strip_suffix(')'))
    } else if lower.contains(',') {
        Some(lower.as_str())
    } else {
        None
    };

    if let Some(inner) = bezier_inner {
        let nums: Vec<f64> = inner
            .split(',')
            .filter_map(|p| p.trim().parse::<f64>().ok())
            .collect();
        if nums.len() >= 4 {
            return MotionKind::Ease {
                curve: "BezierSpline".to_string(),
                duration_ms: 150,
                bezier: Some([nums[0], nums[1], nums[2], nums[3]]),
            };
        }
    }

    match lower.as_str() {
        "material" | "standard" | "emphasized" => {
            return MotionKind::Ease {
                curve: "BezierSpline".to_string(),
                duration_ms: 250,
                bezier: Some([0.2, 0.0, 0.0, 1.0]),
            }
        }
        "decelerate" => {
            return MotionKind::Ease {
                curve: "BezierSpline".to_string(),
                duration_ms: 200,
                bezier: Some([0.0, 0.0, 0.2, 1.0]),
            }
        }
        "accelerate" => {
            return MotionKind::Ease {
                curve: "BezierSpline".to_string(),
                duration_ms: 200,
                bezier: Some([0.4, 0.0, 1.0, 1.0]),
            }
        }
        _ => {}
    }

    MotionKind::Ease {
        curve: s.to_string(),
        duration_ms: 150,
        bezier: None,
    }
}

pub fn evaluate_ease(easing_name: &str, t: f64) -> f64 {
    let name: String = easing_name
        .chars()
        .filter(|c| c.is_ascii_alphabetic())
        .map(|c| c.to_ascii_lowercase())
        .collect();
    let mut x = if t.is_finite() { t.clamp(0.0, 1.0) } else { 0.0 };

    match name.as_str() {
        "linear" => x,
        "inquad" => x * x,
        "outquad" => 1.0 - (1.0 - x) * (1.0 - x),
        "inoutquad" => {
            if x < 0.5 {
                2.0 * x * x
            } else {
                1.0 - (-2.0 * x + 2.0).powi(2) / 2.0
            }
        }
        "incubic" => x * x * x,
        "outcubic" | "easeout" => 1.0 - (1.0 - x).powi(3),
        "inoutcubic" | "easeinout" | "inout" => {
            if x < 0.5 {
                4.0 * x * x * x
            } else {
                1.0 - (-2.0 * x + 2.0).powi(3) / 2.0
            }
        }
        "inquart" => x.powi(4),
        "outquart" => 1.0 - (1.0 - x).powi(4),
        "inoutquart" => {
            if x < 0.5 {
                8.0 * x.powi(4)
            } else {
                1.0 - (-2.0 * x + 2.0).powi(4) / 2.0
            }
        }
        "inquint" => x.powi(5),
        "outquint" => 1.0 - (1.0 - x).powi(5),
        "inoutquint" => {
            if x < 0.5 {
                16.0 * x.powi(5)
            } else {
                1.0 - (-2.0 * x + 2.0).powi(5) / 2.0
            }
        }
        "insine" => 1.0 - ((x * std::f64::consts::PI) / 2.0).cos(),
        "outsine" => ((x * std::f64::consts::PI) / 2.0).sin(),
        "inoutsine" | "sine" => -((std::f64::consts::PI * x).cos() - 1.0) / 2.0,
        "inexpo" => {
            if x == 0.0 {
                0.0
            } else {
                2.0_f64.powf(10.0 * x - 10.0)
            }
        }
        "outexpo" => {
            if (x - 1.0).abs() < 1e-12 {
                1.0
            } else {
                1.0 - 2.0_f64.powf(-10.0 * x)
            }
        }
        "inoutexpo" => {
            if x == 0.0 {
                0.0
            } else if (x - 1.0).abs() < 1e-12 {
                1.0
            } else if x < 0.5 {
                2.0_f64.powf(20.0 * x - 10.0) / 2.0
            } else {
                (2.0 - 2.0_f64.powf(-20.0 * x + 10.0)) / 2.0
            }
        }
        "incirc" => 1.0 - (1.0 - x * x).max(0.0).sqrt(),
        "outcirc" => (1.0 - (x - 1.0).powi(2)).max(0.0).sqrt(),
        "inoutcirc" => {
            if x < 0.5 {
                (1.0 - (1.0 - (2.0 * x).powi(2)).max(0.0).sqrt()) / 2.0
            } else {
                ((1.0 - (-2.0 * x + 2.0).powi(2)).max(0.0).sqrt() + 1.0) / 2.0
            }
        }
        "inback" => {
            let c1 = 1.70158;
            let c3 = c1 + 1.0;
            c3 * x * x * x - c1 * x * x
        }
        "outback" => {
            let c1 = 1.70158;
            let c3 = c1 + 1.0;
            1.0 + c3 * (x - 1.0).powi(3) + c1 * (x - 1.0).powi(2)
        }
        "inoutback" => {
            let c1 = 1.70158;
            let c2 = c1 * 1.525;
            if x < 0.5 {
                ((2.0 * x).powi(2) * ((c2 + 1.0) * 2.0 * x - c2)) / 2.0
            } else {
                ((2.0 * x - 2.0).powi(2) * ((c2 + 1.0) * (x * 2.0 - 2.0) + c2) + 2.0) / 2.0
            }
        }
        "outbounce" => {
            let n1 = 7.5625;
            let d1 = 2.75;
            if x < 1.0 / d1 {
                n1 * x * x
            } else if x < 2.0 / d1 {
                x -= 1.5 / d1;
                n1 * x * x + 0.75
            } else if x < 2.5 / d1 {
                x -= 2.25 / d1;
                n1 * x * x + 0.9375
            } else {
                x -= 2.625 / d1;
                n1 * x * x + 0.984375
            }
        }
        "inbounce" => 1.0 - evaluate_ease("outbounce", 1.0 - x),
        "inoutbounce" => {
            if x < 0.5 {
                (1.0 - evaluate_ease("outbounce", 1.0 - 2.0 * x)) / 2.0
            } else {
                (1.0 + evaluate_ease("outbounce", 2.0 * x - 1.0)) / 2.0
            }
        }
        _ => x,
    }
}

pub fn eval_cubic_bezier(x1: f64, y1: f64, x2: f64, y2: f64, x: f64) -> f64 {
    let x = x.clamp(0.0, 1.0);
    if x <= 0.0 {
        return 0.0;
    }
    if x >= 1.0 {
        return 1.0;
    }
    let sample_x = |t: f64| {
        let inv = 1.0 - t;
        3.0 * inv * inv * t * x1 + 3.0 * inv * t * t * x2 + t * t * t
    };
    let sample_y = |t: f64| {
        let inv = 1.0 - t;
        3.0 * inv * inv * t * y1 + 3.0 * inv * t * t * y2 + t * t * t
    };
    let mut lo = 0.0;
    let mut hi = 1.0;
    let mut t = x;
    for _ in 0..18 {
        let cur = sample_x(t);
        if (cur - x).abs() < 1e-6 {
            break;
        }
        if cur < x {
            lo = t;
        } else {
            hi = t;
        }
        t = 0.5 * (lo + hi);
    }
    sample_y(t)
}

#[derive(Debug, Clone)]
pub struct SpringSolver {
    pub value: f64,
    pub velocity: f64,
    pub target: f64,
    pub tension: f64,
    pub damping: f64,
    pub mass: f64,
    pub epsilon: f64,
}

impl SpringSolver {
    pub fn new(initial: f64, tension: f64, damping: f64, mass: f64, epsilon: f64) -> Self {
        Self {
            value: initial,
            velocity: 0.0,
            target: initial,
            tension: tension.max(0.1),
            damping: damping.clamp(0.05, 5.0),
            mass: mass.max(0.05),
            epsilon: epsilon.max(1e-5),
        }
    }

    pub fn set_target(&mut self, target: f64) {
        self.target = target;
    }

    pub fn is_settled(&self) -> bool {
        (self.target - self.value).abs() <= self.epsilon && self.velocity.abs() <= self.epsilon
    }

    pub fn step(&mut self, dt_sec: f64) -> f64 {
        if self.is_settled() {
            self.value = self.target;
            self.velocity = 0.0;
            return self.value;
        }

        let dt_total = dt_sec.clamp(0.0, 0.1);
        let substeps = ((dt_total / 0.008).ceil() as usize).max(1);
        let h = dt_total / (substeps as f64);
        let omega = (self.tension * 18.0 / self.mass).sqrt();
        let zeta = self.damping;

        for _ in 0..substeps {
            let disp = self.value - self.target;
            let accel = -omega * omega * disp - 2.0 * zeta * omega * self.velocity;
            self.velocity += accel * h;
            self.value += self.velocity * h;
        }

        if self.is_settled() {
            self.value = self.target;
            self.velocity = 0.0;
        }
        self.value
    }
}
