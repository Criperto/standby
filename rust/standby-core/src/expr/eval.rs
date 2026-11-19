use regex::{Regex, RegexBuilder};
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{LazyLock, Mutex};

use super::ast::{eval_cad, is_cad_expr, node_to_string, parse_ast, split_chained, AstNode, ExprVal};
use super::color::MaterialYouPalette;
use super::easing::{evaluate_ease, is_additive_property};
use super::state::StateStore;

const MAX_REGEX_CACHE: usize = 512;

struct RegexCache {
    map: HashMap<String, Option<Regex>>,
    order: VecDeque<String>,
}

impl RegexCache {
    fn new() -> Self {
        Self {
            map: HashMap::new(),
            order: VecDeque::new(),
        }
    }

    fn get_or_compile(&mut self, pattern: &str) -> Option<Regex> {
        if let Some(hit) = self.map.get(pattern) {
            return hit.clone();
        }
        let compiled = if pattern.len() > 2048 {
            None
        } else {
            RegexBuilder::new(pattern)
                .size_limit(2 * 1024 * 1024)
                .build()
                .ok()
        };
        if self.map.len() >= MAX_REGEX_CACHE {
            if let Some(old) = self.order.pop_front() {
                self.map.remove(&old);
            }
        }
        self.order.push_back(pattern.to_string());
        self.map.insert(pattern.to_string(), compiled.clone());
        compiled
    }
}

static REGEX_CACHE: LazyLock<Mutex<RegexCache>> = LazyLock::new(|| Mutex::new(RegexCache::new()));

fn compile_cached_regex(pattern: &str) -> Option<Regex> {
    REGEX_CACHE
        .lock()
        .ok()
        .and_then(|mut c| c.get_or_compile(pattern))
}

#[derive(Debug, Clone)]
pub struct EvalContext {
    pub scope_id: String,
    pub base_value: f64,
    pub t: f64,
    pub hour: i32,
    pub minute: i32,
    pub second: f64,
    pub millisecond: f64,
    pub battery: f64,
    pub has_battery: bool,
    pub is_charging: bool,
    pub has_media: bool,
    pub is_playing: bool,
    pub has_cover: bool,
    pub media_progress: f64,
    pub media_position: f64,
    pub media_length: f64,
    pub is_shuffle: bool,
    pub is_loop: bool,
    pub can_next: bool,
    pub can_prev: bool,
    pub media_title: String,
    pub media_artist: String,
    pub weather_temp: String,
    pub date_text: String,
    pub datetime_text: String,
    pub screen_w: f64,
    pub screen_h: f64,
    pub volume: f64,
    pub lux: f64,
    pub wiz_on: bool,
    pub wiz_dim: f64,
    pub wiz_name: String,
    pub wiz_hex: String,
    pub is_hovered: bool,
    pub is_pressed: bool,
    pub state_store: StateStore,
    pub custom_vars: HashMap<String, ExprVal>,
    pub element_props: HashMap<String, HashMap<String, ExprVal>>,
    pub anim_slots: HashMap<String, f64>,
    pub palette: MaterialYouPalette,
}

impl Default for EvalContext {
    fn default() -> Self {
        Self {
            scope_id: "@".to_string(),
            base_value: 0.0,
            t: 0.0,
            hour: 12,
            minute: 0,
            second: 0.0,
            millisecond: 0.0,
            battery: 85.0,
            has_battery: true,
            is_charging: false,
            has_media: false,
            is_playing: false,
            has_cover: false,
            media_progress: 0.0,
            media_position: 0.0,
            media_length: 0.0,
            is_shuffle: false,
            is_loop: false,
            can_next: false,
            can_prev: false,
            media_title: String::new(),
            media_artist: String::new(),
            weather_temp: String::new(),
            date_text: String::new(),
            datetime_text: String::new(),
            screen_w: 2400.0,
            screen_h: 1080.0,
            volume: 0.7,
            lux: 100.0,
            wiz_on: false,
            wiz_dim: 100.0,
            wiz_name: "WiZ".to_string(),
            wiz_hex: "#ffffff".to_string(),
            is_hovered: false,
            is_pressed: false,
            state_store: StateStore::new(),
            custom_vars: HashMap::new(),
            element_props: HashMap::new(),
            anim_slots: HashMap::new(),
            palette: MaterialYouPalette::default(),
        }
    }
}

impl EvalContext {
    pub fn resolve_ident(&self, name: &str) -> ExprVal {
        if let Some(v) = self.custom_vars.get(name) {
            return v.clone();
        }
        match name {
            "battery" | "batteryLevel" => ExprVal::Num(self.battery),
            "hasBattery" => ExprVal::Bool(self.has_battery),
            "isCharging" => ExprVal::Bool(self.is_charging),
            "hasMedia" => ExprVal::Bool(self.has_media),
            "isPlaying" | "isAudioPlaying" => ExprVal::Bool(self.is_playing),
            "hasCover" => ExprVal::Bool(self.has_cover),
            "mediaProgress" => ExprVal::Num(self.media_progress),
            "mediaPosition" => ExprVal::Num(self.media_position),
            "mediaLength" => ExprVal::Num(self.media_length),
            "isShuffle" => ExprVal::Num(if self.is_shuffle { 1.0 } else { 0.0 }),
            "isLoop" => ExprVal::Num(if self.is_loop { 1.0 } else { 0.0 }),
            "canNext" => ExprVal::Bool(self.can_next),
            "canPrev" => ExprVal::Bool(self.can_prev),
            "mediaTitle" | "trackTitle" => ExprVal::Str(self.media_title.clone()),
            "mediaArtist" | "trackArtist" => ExprVal::Str(self.media_artist.clone()),
            "weatherTemp" => ExprVal::Str(self.weather_temp.clone()),
            "screenW" => ExprVal::Num(self.screen_w),
            "screenH" => ExprVal::Num(self.screen_h),
            "volume" | "audioVol" => ExprVal::Num(self.volume),
            "lux" | "ambientLux" => ExprVal::Num(self.lux),
            "wizOn" => ExprVal::Bool(self.wiz_on),
            "wizDim" => ExprVal::Num(self.wiz_dim),
            "wizName" => ExprVal::Str(self.wiz_name.clone()),
            "wizHex" => ExprVal::Str(self.wiz_hex.clone()),
            "t" => ExprVal::Num(self.t),
            "hour" => ExprVal::Num(self.hour as f64),
            "minute" => ExprVal::Num(self.minute as f64),
            "second" => ExprVal::Num(self.second),
            "millisecond" => ExprVal::Num(self.millisecond),
            "baseValue" => ExprVal::Num(self.base_value),
            "isHovered" => ExprVal::Num(if self.is_hovered { 1.0 } else { 0.0 }),
            "isPressed" => ExprVal::Num(if self.is_pressed { 1.0 } else { 0.0 }),
            "isCapsLock" | "isWrongPassword" => ExprVal::Bool(false),
            _ => {
                if self.state_store.has(name, &self.scope_id) {
                    ExprVal::Num(self.state_store.get(name, &self.scope_id))
                } else {
                    ExprVal::Num(0.0)
                }
            }
        }
    }

    pub fn resolve_string_var(&self, name: &str) -> Option<String> {
        match self.resolve_ident(name) {
            ExprVal::Str(s) => Some(s),
            _ => None,
        }
    }

    pub fn read_element_prop(
        &self,
        alias: &str,
        prop: &str,
        active_guard: &mut HashSet<String>,
    ) -> ExprVal {
        let key = format!("{alias}.{prop}");
        if !active_guard.insert(key.clone()) {
            return ExprVal::Num(0.0);
        }
        let val = self
            .element_props
            .get(alias)
            .and_then(|m| m.get(prop))
            .cloned()
            .unwrap_or(ExprVal::Num(0.0));
        active_guard.remove(&key);
        val
    }
}

fn ping_pong(t_sec: f64, period: f64, easing: &str) -> f64 {
    let p = period.max(0.01);
    let phase = (t_sec / p).rem_euclid(1.0);
    let tri = if phase < 0.5 {
        phase * 2.0
    } else {
        (1.0 - phase) * 2.0
    };
    evaluate_ease(easing, tri)
}

pub fn eval_ast(node: &AstNode, ctx: &EvalContext) -> ExprVal {
    let mut guard = HashSet::new();
    eval_ast_guarded(node, ctx, &mut guard)
}

fn eval_ast_guarded(
    node: &AstNode,
    ctx: &EvalContext,
    prop_guard: &mut HashSet<String>,
) -> ExprVal {
    match node {
        AstNode::Num(n) => ExprVal::Num(*n),
        AstNode::Str(s) => ExprVal::Str(s.clone()),
        AstNode::Bool(b) => ExprVal::Bool(*b),
        AstNode::Ident(name) => ctx.resolve_ident(name),
        AstNode::Unary { op, expr } => {
            let v = eval_ast_guarded(expr, ctx, prop_guard);
            match op {
                '!' => ExprVal::Bool(!v.as_bool()),
                '-' => ExprVal::Num(-v.as_num()),
                '+' => ExprVal::Num(v.as_num()),
                _ => v,
            }
        }
        AstNode::Binary { op, left, right } => {
            if op == "&&" {
                let l = eval_ast_guarded(left, ctx, prop_guard).as_bool();
                if !l {
                    return ExprVal::Bool(false);
                }
                return ExprVal::Bool(eval_ast_guarded(right, ctx, prop_guard).as_bool());
            }
            if op == "||" {
                let l = eval_ast_guarded(left, ctx, prop_guard).as_bool();
                if l {
                    return ExprVal::Bool(true);
                }
                return ExprVal::Bool(eval_ast_guarded(right, ctx, prop_guard).as_bool());
            }

            let lv = eval_ast_guarded(left, ctx, prop_guard);
            let rv = eval_ast_guarded(right, ctx, prop_guard);

            match op.as_str() {
                "+" => match (&lv, &rv) {
                    (ExprVal::Str(ls), _) => ExprVal::Str(format!("{ls}{}", rv.as_str())),
                    (_, ExprVal::Str(rs)) => ExprVal::Str(format!("{}{rs}", lv.as_str())),
                    _ => ExprVal::Num(lv.as_num() + rv.as_num()),
                },
                "-" => ExprVal::Num(lv.as_num() - rv.as_num()),
                "*" => ExprVal::Num(lv.as_num() * rv.as_num()),
                "/" => {
                    let r = rv.as_num();
                    ExprVal::Num(if r != 0.0 { lv.as_num() / r } else { 0.0 })
                }
                "%" => {
                    let r = rv.as_num();
                    ExprVal::Num(if r != 0.0 { lv.as_num() % r } else { 0.0 })
                }
                "==" => match (&lv, &rv) {
                    (ExprVal::Str(a), ExprVal::Str(b)) => ExprVal::Bool(a == b),
                    (ExprVal::Bool(a), ExprVal::Bool(b)) => ExprVal::Bool(a == b),
                    _ => ExprVal::Bool((lv.as_num() - rv.as_num()).abs() < 1e-9),
                },
                "!=" => match (&lv, &rv) {
                    (ExprVal::Str(a), ExprVal::Str(b)) => ExprVal::Bool(a != b),
                    (ExprVal::Bool(a), ExprVal::Bool(b)) => ExprVal::Bool(a != b),
                    _ => ExprVal::Bool((lv.as_num() - rv.as_num()).abs() >= 1e-9),
                },
                "<" => ExprVal::Bool(lv.as_num() < rv.as_num()),
                "<=" => ExprVal::Bool(lv.as_num() <= rv.as_num()),
                ">" => ExprVal::Bool(lv.as_num() > rv.as_num()),
                ">=" => ExprVal::Bool(lv.as_num() >= rv.as_num()),
                _ => ExprVal::Num(0.0),
            }
        }
        AstNode::Ternary {
            cond,
            consequent,
            alternate,
        } => {
            if eval_ast_guarded(cond, ctx, prop_guard).as_bool() {
                eval_ast_guarded(consequent, ctx, prop_guard)
            } else {
                eval_ast_guarded(alternate, ctx, prop_guard)
            }
        }
        AstNode::Call { name, args } => eval_call(name, args, ctx, prop_guard),
    }
}

fn arg_as_label(node: &AstNode, ctx: &EvalContext, guard: &mut HashSet<String>) -> String {
    match node {
        AstNode::Ident(s) | AstNode::Str(s) => s.clone(),
        _ => eval_ast_guarded(node, ctx, guard).as_str(),
    }
}

fn eval_call(
    name: &str,
    args: &[AstNode],
    ctx: &EvalContext,
    guard: &mut HashSet<String>,
) -> ExprVal {
    let fn_name = name.to_ascii_lowercase();
    match fn_name.as_str() {
        "when" | "if" => {
            if args.len() >= 2 {
                if eval_ast_guarded(&args[0], ctx, guard).as_bool() {
                    eval_ast_guarded(&args[1], ctx, guard)
                } else if args.len() >= 3 {
                    eval_ast_guarded(&args[2], ctx, guard)
                } else {
                    ExprVal::Num(0.0)
                }
            } else {
                ExprVal::Num(0.0)
            }
        }
        "clamp" => {
            let v = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let mn = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let mx = args
                .get(2)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(1.0);
            let (lo, hi) = if mn <= mx { (mn, mx) } else { (mx, mn) };
            ExprVal::Num(v.clamp(lo, hi))
        }
        "lerp" => {
            let a = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let b = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            let t = args
                .get(2)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.5);
            ExprVal::Num(a + (b - a) * t)
        }
        "map" => {
            let val = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let in_min = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let in_max = args
                .get(2)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(100.0);
            let out_min = args
                .get(3)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let out_max = args
                .get(4)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            if (in_max - in_min).abs() < 1e-12 {
                ExprVal::Num(out_min)
            } else {
                ExprVal::Num(out_min + (out_max - out_min) * ((val - in_min) / (in_max - in_min)))
            }
        }
        "min" => {
            let a = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let b = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            ExprVal::Num(a.min(b))
        }
        "max" => {
            let a = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let b = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            ExprVal::Num(a.max(b))
        }
        "abs" => ExprVal::Num(
            args.first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num().abs())
                .unwrap_or(0.0),
        ),
        "round" => ExprVal::Num(
            args.first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num().round())
                .unwrap_or(0.0),
        ),
        "floor" => ExprVal::Num(
            args.first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num().floor())
                .unwrap_or(0.0),
        ),
        "ceil" => ExprVal::Num(
            args.first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num().ceil())
                .unwrap_or(0.0),
        ),
        "sqrt" => {
            let x = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            ExprVal::Num(if x >= 0.0 { x.sqrt() } else { 0.0 })
        }
        "sign" => {
            let x = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let s = if x > 0.0 {
                1.0
            } else if x < 0.0 {
                -1.0
            } else {
                0.0
            };
            ExprVal::Num(s)
        }
        "pow" => {
            let b = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let e = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            let res = b.powf(e);
            ExprVal::Num(if res.is_finite() { res } else { 0.0 })
        }
        "mod" => {
            let a = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let b = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            ExprVal::Num(if b != 0.0 { a % b } else { 0.0 })
        }
        "step" => {
            let edge = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let x = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            ExprVal::Num(if x < edge { 0.0 } else { 1.0 })
        }
        "smoothstep" => {
            let e0 = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let e1 = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            let x = args
                .get(2)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let denom = if (e1 - e0).abs() < 1e-12 {
                1.0
            } else {
                e1 - e0
            };
            let t = ((x - e0) / denom).clamp(0.0, 1.0);
            ExprVal::Num(t * t * (3.0 - 2.0 * t))
        }
        "sin" => {
            let rad = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let amp = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            ExprVal::Num(rad.sin() * amp)
        }
        "cos" => {
            let rad = args
                .first()
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(0.0);
            let amp = args
                .get(1)
                .map(|n| eval_ast_guarded(n, ctx, guard).as_num())
                .unwrap_or(1.0);
            ExprVal::Num(rad.cos() * amp)
        }
        "ease" => {
            if args.len() < 2 {
                return ExprVal::Num(0.0);
            }
            let (ease_name, t_val) = match (&args[0], &args[1]) {
                (AstNode::Str(s), _) | (AstNode::Ident(s), _) => {
                    (s.clone(), eval_ast_guarded(&args[1], ctx, guard).as_num())
                }
                (_, AstNode::Str(s)) | (_, AstNode::Ident(s)) => {
                    (s.clone(), eval_ast_guarded(&args[0], ctx, guard).as_num())
                }
                _ => {
                    let v0 = eval_ast_guarded(&args[0], ctx, guard);
                    if let ExprVal::Str(s) = v0 {
                        (s, eval_ast_guarded(&args[1], ctx, guard).as_num())
                    } else {
                        let v1 = eval_ast_guarded(&args[1], ctx, guard);
                        (v1.as_str(), v0.as_num())
                    }
                }
            };
            ExprVal::Num(evaluate_ease(&ease_name, t_val))
        }
        "state" => {
            if let Some(arg) = args.first() {
                let s_name = arg_as_label(arg, ctx, guard);
                ExprVal::Num(ctx.state_store.get(&s_name, &ctx.scope_id))
            } else {
                ExprVal::Num(0.0)
            }
        }
        "prop" => {
            if args.len() >= 2 {
                let alias = arg_as_label(&args[0], ctx, guard);
                let prop_name = arg_as_label(&args[1], ctx, guard);
                ctx.read_element_prop(&alias, &prop_name, guard)
            } else {
                ExprVal::Num(0.0)
            }
        }
        "anim" => {
            if let Some(cond_node) = args.first() {
                let cond_str = node_to_string(cond_node);
                let motion_raw = args
                    .get(1)
                    .map(node_to_string)
                    .unwrap_or_else(|| "spring".to_string());
                let motion_unquoted = motion_raw
                    .trim()
                    .trim_matches('"')
                    .trim_matches('\'')
                    .replace(' ', "");
                let delay_ms = args
                    .get(2)
                    .map(|n| eval_ast_guarded(n, ctx, guard).as_num().round() as i64)
                    .unwrap_or(0);
                let key = format!("{}|{}|{}|{}", ctx.scope_id, cond_str.trim(), motion_unquoted, delay_ms);
                if let Some(&slot_val) = ctx.anim_slots.get(&key) {
                    return ExprVal::Num(slot_val);
                }
                let cond_val = eval_ast_guarded(cond_node, ctx, guard).as_bool();
                ExprVal::Num(if cond_val { 1.0 } else { 0.0 })
            } else {
                ExprVal::Num(0.0)
            }
        }
        "morph" => {
            if args.len() >= 3 {
                let cond_node = &args[0];
                let cond_str = node_to_string(cond_node);
                let motion_raw = args
                    .get(3)
                    .map(node_to_string)
                    .unwrap_or_else(|| "spring".to_string());
                let motion_unquoted = motion_raw
                    .trim()
                    .trim_matches('"')
                    .trim_matches('\'')
                    .replace(' ', "");
                let delay_ms = args
                    .get(4)
                    .map(|n| eval_ast_guarded(n, ctx, guard).as_num().round() as i64)
                    .unwrap_or(0);
                let key = format!("{}|{}|{}|{}", ctx.scope_id, cond_str.trim(), motion_unquoted, delay_ms);
                let t_val = if let Some(&slot_val) = ctx.anim_slots.get(&key) {
                    slot_val
                } else if eval_ast_guarded(cond_node, ctx, guard).as_bool() {
                    1.0
                } else {
                    0.0
                };
                let from_v = eval_ast_guarded(&args[1], ctx, guard).as_num();
                let to_v = eval_ast_guarded(&args[2], ctx, guard).as_num();
                ExprVal::Num(from_v + (to_v - from_v) * t_val)
            } else {
                ExprVal::Num(0.0)
            }
        }
        "follow" => {
            if let Some(target) = args.first() {
                eval_ast_guarded(target, ctx, guard)
            } else {
                ExprVal::Num(0.0)
            }
        }
        // Analog clock functions
        "timesecond" => {
            let smooth = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_bool())
                .unwrap_or(false);
            let ms = if smooth { ctx.millisecond } else { 0.0 };
            ExprVal::Num((ctx.second + ms / 1000.0) * 6.0)
        }
        "timeminute" => {
            let smooth = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_bool())
                .unwrap_or(false);
            let s = if smooth { ctx.second } else { 0.0 };
            ExprVal::Num(((ctx.minute as f64) + s / 60.0) * 6.0)
        }
        "timehour" => {
            let smooth = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_bool())
                .unwrap_or(false);
            let m = if smooth { ctx.minute as f64 } else { 0.0 };
            let s = if smooth { ctx.second } else { 0.0 };
            ExprVal::Num((((ctx.hour % 12) as f64) + m / 60.0 + s / 3600.0) * 30.0)
        }
        "timehour24" => {
            let smooth = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_bool())
                .unwrap_or(false);
            let m = if smooth { ctx.minute as f64 } else { 0.0 };
            let s = if smooth { ctx.second } else { 0.0 };
            ExprVal::Num(((ctx.hour as f64) + m / 60.0 + s / 3600.0) * 15.0)
        }
        // Kinematics (pure functions of global time t)
        "spin" => {
            let deg_per_sec = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(45.0);
            ExprVal::Num((ctx.t * deg_per_sec).rem_euclid(360.0))
        }
        "pulse" | "osc" => {
            let min_v = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let max_v = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(1.0);
            let default_period = if fn_name == "pulse" { 2.0 } else { 3.0 };
            let period = args
                .get(2)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(default_period);
            let default_ease = if fn_name == "pulse" {
                "InOutSine"
            } else {
                "InOutQuad"
            };
            let ease = args
                .get(3)
                .map(|a| arg_as_label(a, ctx, guard))
                .unwrap_or_else(|| default_ease.to_string());
            let f = ping_pong(ctx.t, period, &ease);
            ExprVal::Num(min_v + (max_v - min_v) * f)
        }
        "wiggle" => {
            let freq = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(2.0);
            let amp = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(10.0);
            let r = ctx.t * freq * std::f64::consts::TAU;
            let wave =
                0.53 * r.sin() + 0.31 * (1.414 * r + 1.2).sin() + 0.16 * (0.618 * r + 2.4).cos();
            ExprVal::Num(wave * amp)
        }
        "add" => {
            let step = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(6.0);
            let interval = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num().max(0.01))
                .unwrap_or(1.0);
            let ticks = (ctx.t / interval).floor();
            let raw = ticks * step;
            ExprVal::Num(raw.rem_euclid(360_000.0))
        }
        "random" => {
            let mn = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let mx = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(1.0);
            let interval = args
                .get(2)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num().max(0.05))
                .unwrap_or(1.0);
            let bucket = (ctx.t / interval).floor() as u64;
            // Deterministic hash noise per time bucket
            let mut x = bucket.wrapping_mul(0x9E37_79B9_7F4A_7C15);
            x ^= x >> 30;
            x = x.wrapping_mul(0xBF58_476D_1CE4_E5B9);
            x ^= x >> 27;
            x = x.wrapping_mul(0x94D0_49BB_1331_11EB);
            x ^= x >> 31;
            let norm = (x as f64) / (u64::MAX as f64);
            ExprVal::Num(mn + (mx - mn) * norm)
        }
        // System helpers
        "battery" => {
            let mn = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let mx = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(100.0);
            ExprVal::Num(mn + (mx - mn) * (ctx.battery / 100.0).clamp(0.0, 1.0))
        }
        "audiovol" => {
            let mn = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let mx = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(1.0);
            ExprVal::Num(mn + (mx - mn) * ctx.volume.clamp(0.0, 1.0))
        }
        "isaudioplaying" => {
            if ctx.is_playing {
                args.first()
                    .map(|a| eval_ast_guarded(a, ctx, guard))
                    .unwrap_or(ExprVal::Num(1.0))
            } else {
                args.get(1)
                    .map(|a| eval_ast_guarded(a, ctx, guard))
                    .unwrap_or(ExprVal::Num(0.0))
            }
        }
        // Interactive fallbacks in pure evaluation
        "hover" => {
            if args.len() == 1 {
                if ctx.is_hovered {
                    eval_ast_guarded(&args[0], ctx, guard)
                } else {
                    ExprVal::Num(ctx.base_value)
                }
            } else if args.len() >= 2 {
                if ctx.is_hovered {
                    eval_ast_guarded(&args[1], ctx, guard)
                } else {
                    eval_ast_guarded(&args[0], ctx, guard)
                }
            } else {
                ExprVal::Num(ctx.base_value)
            }
        }
        "press" => {
            if args.len() == 1 {
                if ctx.is_pressed {
                    eval_ast_guarded(&args[0], ctx, guard)
                } else {
                    ExprVal::Num(ctx.base_value)
                }
            } else if args.len() >= 2 {
                if ctx.is_pressed {
                    eval_ast_guarded(&args[1], ctx, guard)
                } else {
                    eval_ast_guarded(&args[0], ctx, guard)
                }
            } else {
                ExprVal::Num(ctx.base_value)
            }
        }
        "once" | "intro" => args
            .get(1)
            .map(|a| eval_ast_guarded(a, ctx, guard))
            .unwrap_or(ExprVal::Num(ctx.base_value)),
        "toggle" => args
            .first()
            .map(|a| eval_ast_guarded(a, ctx, guard))
            .unwrap_or(ExprVal::Num(0.0)),
        "cycle" => args
            .first()
            .map(|a| eval_ast_guarded(a, ctx, guard))
            .unwrap_or(ExprVal::Num(0.0)),
        "steptoggle" => ExprVal::Num(ctx.base_value),
        // ── Regex Engine (O(n) linear time, cached) ──
        "regexmatch" | "regextest" => {
            if args.len() < 2 {
                return ExprVal::Bool(false);
            }
            let text = eval_ast_guarded(&args[0], ctx, guard).as_str();
            let pattern = eval_ast_guarded(&args[1], ctx, guard).as_str();
            let matched = compile_cached_regex(&pattern)
                .map(|re| re.is_match(&text))
                .unwrap_or(false);
            ExprVal::Bool(matched)
        }
        "regexextract" => {
            if args.len() < 2 {
                return ExprVal::Str(String::new());
            }
            let text = eval_ast_guarded(&args[0], ctx, guard).as_str();
            let pattern = eval_ast_guarded(&args[1], ctx, guard).as_str();
            let group_idx = args
                .get(2)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num().max(0.0) as usize)
                .unwrap_or(0);
            let extracted = compile_cached_regex(&pattern)
                .and_then(|re| re.captures(&text))
                .and_then(|caps| caps.get(group_idx))
                .map(|m| m.as_str().to_string())
                .unwrap_or_default();
            ExprVal::Str(extracted)
        }
        "regexreplace" => {
            if args.len() < 3 {
                return args
                    .first()
                    .map(|a| eval_ast_guarded(a, ctx, guard))
                    .unwrap_or_else(|| ExprVal::Str(String::new()));
            }
            let text = eval_ast_guarded(&args[0], ctx, guard).as_str();
            let pattern = eval_ast_guarded(&args[1], ctx, guard).as_str();
            let repl = eval_ast_guarded(&args[2], ctx, guard).as_str();
            let replaced = compile_cached_regex(&pattern)
                .map(|re| re.replace_all(&text, repl.as_str()).into_owned())
                .unwrap_or(text);
            ExprVal::Str(replaced)
        }
        // ── String Helpers ──
        "lower" => ExprVal::Str(
            args.first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_str().to_lowercase())
                .unwrap_or_default(),
        ),
        "upper" => ExprVal::Str(
            args.first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_str().to_uppercase())
                .unwrap_or_default(),
        ),
        "trim" => ExprVal::Str(
            args.first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_str().trim().to_string())
                .unwrap_or_default(),
        ),
        "len" => ExprVal::Num(
            args.first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_str().chars().count() as f64)
                .unwrap_or(0.0),
        ),
        "contains" => {
            if args.len() < 2 {
                return ExprVal::Bool(false);
            }
            let s = eval_ast_guarded(&args[0], ctx, guard).as_str();
            let sub = eval_ast_guarded(&args[1], ctx, guard).as_str();
            ExprVal::Bool(s.contains(&sub))
        }
        "substr" => {
            if args.len() < 2 {
                return ExprVal::Str(String::new());
            }
            let s = eval_ast_guarded(&args[0], ctx, guard).as_str();
            let start = eval_ast_guarded(&args[1], ctx, guard).as_num().max(0.0) as usize;
            let chars: Vec<char> = s.chars().collect();
            if start >= chars.len() {
                return ExprVal::Str(String::new());
            }
            let count = args
                .get(2)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num().max(0.0) as usize)
                .unwrap_or(chars.len() - start);
            let end = (start + count).min(chars.len());
            ExprVal::Str(chars[start..end].iter().collect())
        }
        "replace" => {
            if args.len() < 3 {
                return args
                    .first()
                    .map(|a| eval_ast_guarded(a, ctx, guard))
                    .unwrap_or_else(|| ExprVal::Str(String::new()));
            }
            let s = eval_ast_guarded(&args[0], ctx, guard).as_str();
            let old = eval_ast_guarded(&args[1], ctx, guard).as_str();
            let new = eval_ast_guarded(&args[2], ctx, guard).as_str();
            ExprVal::Str(s.replace(&old, &new))
        }
        "formatnum" => {
            let n = args
                .first()
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num())
                .unwrap_or(0.0);
            let dec = args
                .get(1)
                .map(|a| eval_ast_guarded(a, ctx, guard).as_num().clamp(0.0, 6.0) as usize)
                .unwrap_or(0);
            ExprVal::Str(format!("{:.1$}", n, dec))
        }
        "concat" => {
            let mut out = String::new();
            for a in args {
                out.push_str(&eval_ast_guarded(a, ctx, guard).as_str());
            }
            ExprVal::Str(out)
        }
        _ => args
            .first()
            .map(|a| eval_ast_guarded(a, ctx, guard))
            .unwrap_or(ExprVal::Num(0.0)),
    }
}

pub fn eval_value(expr: &str, ctx: &EvalContext) -> ExprVal {
    let s = expr.trim();
    if s.is_empty() {
        return ExprVal::Num(ctx.base_value);
    }
    if is_cad_expr(s) {
        return ExprVal::Num(eval_cad(s, ctx.base_value));
    }
    if let Ok(ast) = parse_ast(s) {
        return eval_ast(&ast, ctx);
    }
    if let Ok(n) = s.parse::<f64>() {
        return ExprVal::Num(n);
    }
    ExprVal::Str(s.to_string())
}

pub fn eval_node(expr: &str, ctx: &EvalContext) -> f64 {
    let s = expr.trim();
    if s.is_empty() {
        return 0.0;
    }
    if is_cad_expr(s) {
        return eval_cad(s, ctx.base_value);
    }
    if let Ok(ast) = parse_ast(s) {
        return eval_ast(&ast, ctx).as_num();
    }
    s.parse::<f64>().ok().filter(|v| v.is_finite()).unwrap_or(0.0)
}

pub fn eval_condition(cond: &str, ctx: &EvalContext) -> bool {
    let s = cond.trim();
    if s.is_empty() {
        return false;
    }
    if s == "true" || s == "1" {
        return true;
    }
    if s == "false" || s == "0" {
        return false;
    }
    let Ok(ast) = parse_ast(s) else {
        return false;
    };
    eval_ast(&ast, ctx).as_bool()
}

/// Evaluates chained numeric expressions such as `hover(0, 15) + spin(45)` or `baseValue + osc(-10, 10, 2)`
pub fn eval_property_numeric(expr: &str, prop_name: &str, ctx: &EvalContext) -> f64 {
    let s = expr.trim();
    if s.is_empty() {
        return ctx.base_value;
    }
    if is_cad_expr(s) {
        return eval_cad(s, ctx.base_value);
    }

    let chunks = split_chained(s);
    if chunks.len() <= 1 {
        let val = eval_node(s, ctx);
        let lower = s.to_ascii_lowercase();
        let is_kinematic_root = lower.starts_with("spin(")
            || lower.starts_with("osc(")
            || lower.starts_with("wiggle(")
            || lower.starts_with("add(");
        if is_kinematic_root && is_additive_property(prop_name) {
            return ctx.base_value + val;
        }
        return val;
    }

    // Evaluate full expression directly first when Pratt operators connect valid nodes
    if let Ok(ast) = parse_ast(s) {
        return eval_ast(&ast, ctx).as_num();
    }

    let mut acc = 0.0;
    for (idx, chunk) in chunks.iter().enumerate() {
        let v = eval_node(&chunk.expr, ctx);
        if idx == 0 {
            acc = v;
        } else {
            match chunk.op {
                '-' => acc -= v,
                _ => acc += v,
            }
        }
    }
    acc
}

/// Expands `$MACROS` and `${expr}` inside `custom_text` strings.
pub fn interpolate_text(template: &str, ctx: &EvalContext) -> String {
    let mut s = template.to_string();
    let bat = format!("{}%", ctx.battery.round() as i64);
    let chg = if ctx.is_charging { "Cargando" } else { "Batería" };
    let wiz_on = if ctx.wiz_on { "ON" } else { "OFF" };
    let wiz_dim = format!("{}%", ctx.wiz_dim.round() as i64);
    let hr = format!("{:02}", ctx.hour.clamp(0, 23));
    let mn = format!("{:02}", ctx.minute.clamp(0, 59));
    let sc = format!("{:02}", (ctx.second as i32).clamp(0, 59));

    for (macro_key, val) in [
        ("$BATTERY", bat.as_str()),
        ("$CHARGING", chg),
        ("$TRACK", ctx.media_title.as_str()),
        ("$ARTIST", ctx.media_artist.as_str()),
        ("$WEATHER_TEMP", ctx.weather_temp.as_str()),
        ("$DATETIME", ctx.datetime_text.as_str()),
        ("$DATE", ctx.date_text.as_str()),
        ("$HOUR", hr.as_str()),
        ("$MINUTE", mn.as_str()),
        ("$SECOND", sc.as_str()),
        ("$WIZ_ON", wiz_on),
        ("$WIZ_DIM", wiz_dim.as_str()),
        ("$WIZ_NAME", ctx.wiz_name.as_str()),
        ("$WIZ_HEX", ctx.wiz_hex.as_str()),
    ] {
        if s.contains(macro_key) {
            s = s.replace(macro_key, val);
        }
    }

    // Expand embedded ${...} expressions safely (supports nested braces/quotes)
    let mut out = String::with_capacity(s.len());
    let chars: Vec<char> = s.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '$' && i + 1 < chars.len() && chars[i + 1] == '{' {
            let start = i + 2;
            let mut j = start;
            let mut depth = 1usize;
            let mut in_quote: Option<char> = None;
            while j < chars.len() && depth > 0 {
                let c = chars[j];
                if let Some(q) = in_quote {
                    if c == q && (j == 0 || chars[j - 1] != '\\') {
                        in_quote = None;
                    }
                } else if c == '"' || c == '\'' {
                    in_quote = Some(c);
                } else if c == '{' {
                    depth += 1;
                } else if c == '}' {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
                j += 1;
            }
            if depth == 0 {
                let inner: String = chars[start..j].iter().collect();
                let val = eval_value(&inner, ctx);
                out.push_str(&val.as_str());
                i = j + 1;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

pub fn validate_expression(expr: &str) -> Result<(), String> {
    let s = expr.trim();
    if s.is_empty() || is_cad_expr(s) {
        return Ok(());
    }
    // Try direct parse first
    if let Err(e) = parse_ast(s) {
        // Also allow top-level chained expressions (e.g. "once(0,1), hover(1,1.1)")
        let chunks = split_chained(s);
        if chunks.is_empty() {
            return Err(e.to_string());
        }
        for c in &chunks {
            parse_ast(&c.expr).map_err(|ce| ce.to_string())?;
        }
    }
    // Validate any regex patterns statically if literal strings are used
    validate_regex_calls(s)
}

fn validate_regex_calls(expr: &str) -> Result<(), String> {
    if let Ok(ast) = parse_ast(expr) {
        check_ast_regex(&ast)?;
    } else {
        for c in split_chained(expr) {
            if let Ok(ast) = parse_ast(&c.expr) {
                check_ast_regex(&ast)?;
            }
        }
    }
    Ok(())
}

fn check_ast_regex(node: &AstNode) -> Result<(), String> {
    match node {
        AstNode::Call { name, args } => {
            let lower = name.to_ascii_lowercase();
            if matches!(
                lower.as_str(),
                "regexmatch" | "regextest" | "regexextract" | "regexreplace"
            ) {
                if let Some(AstNode::Str(pat)) = args.get(1) {
                    RegexBuilder::new(pat)
                        .size_limit(2 * 1024 * 1024)
                        .build()
                        .map_err(|e| format!("Regex inválido '{pat}': {e}"))?;
                }
            }
            for a in args {
                check_ast_regex(a)?;
            }
        }
        AstNode::Unary { expr, .. } => check_ast_regex(expr)?,
        AstNode::Binary { left, right, .. } => {
            check_ast_regex(left)?;
            check_ast_regex(right)?;
        }
        AstNode::Ternary {
            cond,
            consequent,
            alternate,
        } => {
            check_ast_regex(cond)?;
            check_ast_regex(consequent)?;
            check_ast_regex(alternate)?;
        }
        _ => {}
    }
    Ok(())
}
