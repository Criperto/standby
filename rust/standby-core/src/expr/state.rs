use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, Default)]
pub struct StateStore {
    pub values: HashMap<String, f64>,
    pub declared_defaults: HashMap<String, f64>,
    pub scope_ancestors: HashMap<String, String>,
}

fn is_reserved_builtin(name: &str) -> bool {
    matches!(
        name,
        "battery"
            | "hasMedia"
            | "isPlaying"
            | "hasCover"
            | "isCharging"
            | "hasBattery"
            | "isHovered"
            | "isPressed"
            | "baseValue"
            | "t"
            | "mediaProgress"
            | "mediaPosition"
            | "mediaLength"
            | "isShuffle"
            | "isLoop"
            | "canNext"
            | "canPrev"
            | "screenW"
            | "screenH"
            | "volume"
            | "hour"
            | "minute"
            | "second"
            | "millisecond"
            | "lux"
            | "wizOn"
            | "wizDim"
    )
}

pub fn is_valid_state_name(name: &str) -> bool {
    let mut chars = name.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !(first.is_ascii_alphabetic() || first == '_') {
        return false;
    }
    chars.all(|c| c.is_ascii_alphanumeric() || c == '_') && !is_reserved_builtin(name)
}

impl StateStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn resolve_key(&self, name: &str, scope_id: &str) -> String {
        if name.is_empty() {
            return String::new();
        }
        let mut s = if scope_id.is_empty() { "@" } else { scope_id };
        let mut visited = HashSet::new();
        loop {
            let k = format!("{s}|{name}");
            if self.values.contains_key(&k) {
                return k;
            }
            if s == "@" || !visited.insert(s.to_string()) {
                break;
            }
            s = self
                .scope_ancestors
                .get(s)
                .map(|p| p.as_str())
                .unwrap_or("@");
        }
        let suffix = format!("|{name}");
        for k in self.values.keys() {
            if k.ends_with(&suffix) {
                return k.clone();
            }
        }
        let root_scope = if scope_id.is_empty() { "@" } else { scope_id };
        format!("{root_scope}|{name}")
    }

    pub fn has(&self, name: &str, scope_id: &str) -> bool {
        if name.is_empty() {
            return false;
        }
        let mut s = if scope_id.is_empty() { "@" } else { scope_id };
        let mut visited = HashSet::new();
        loop {
            let k = format!("{s}|{name}");
            if self.values.contains_key(&k) {
                return true;
            }
            if s == "@" || !visited.insert(s.to_string()) {
                break;
            }
            s = self
                .scope_ancestors
                .get(s)
                .map(|p| p.as_str())
                .unwrap_or("@");
        }
        let suffix = format!("|{name}");
        self.values.keys().any(|k| k.ends_with(&suffix))
    }

    pub fn get(&self, name: &str, scope_id: &str) -> f64 {
        if name.is_empty() {
            return 0.0;
        }
        let k = self.resolve_key(name, scope_id);
        self.values.get(&k).copied().unwrap_or(0.0)
    }

    pub fn set(&mut self, name: &str, val: f64, scope_id: &str) {
        if name.is_empty() {
            return;
        }
        let k = self.resolve_key(name, scope_id);
        let safe = if val.is_finite() { val } else { 0.0 };
        self.values.insert(k, safe);
    }

    pub fn flip(&mut self, name: &str, scope_id: &str) {
        let cur = self.get(name, scope_id);
        self.set(name, if cur != 0.0 { 0.0 } else { 1.0 }, scope_id);
    }

    pub fn inc(
        &mut self,
        name: &str,
        step: f64,
        min: f64,
        max: f64,
        wrap: bool,
        scope_id: &str,
    ) {
        let cur = self.get(name, scope_id);
        let mut val = cur + step;
        if val > max {
            val = if wrap && min.is_finite() { min } else { max };
        } else if val < min {
            val = if wrap && max.is_finite() { max } else { min };
        }
        self.set(name, val, scope_id);
    }

    pub fn cyclestate(&mut self, name: &str, states: &[f64], scope_id: &str) {
        if states.is_empty() {
            return;
        }
        let cur = self.get(name, scope_id);
        let idx = states
            .iter()
            .position(|&x| (x - cur).abs() < 1e-9)
            .map(|i| (i + 1) % states.len())
            .unwrap_or(0);
        self.set(name, states[idx], scope_id);
    }

    pub fn reset(&mut self, scope_id: Option<&str>, var_name: Option<&str>) {
        if scope_id.is_none() && var_name.is_none() {
            self.values = self.declared_defaults.clone();
            return;
        }
        let s = scope_id.unwrap_or("@");
        if let Some(vn) = var_name {
            if !vn.is_empty() {
                let k = self.resolve_key(vn, s);
                let def = self.declared_defaults.get(&k).copied().unwrap_or(0.0);
                self.values.insert(k, def);
                return;
            }
        }
        let prefix = format!("{s}|");
        for (k, &v) in &self.declared_defaults {
            if k.starts_with(&prefix) || (s == "@" && k.starts_with("@|")) {
                self.values.insert(k.clone(), v);
            }
        }
    }

    pub fn declare(&mut self, scope_id: &str, vars: &HashMap<String, f64>) {
        let s = if scope_id.is_empty() { "@" } else { scope_id };
        for (name, &val) in vars {
            if !is_valid_state_name(name) {
                continue;
            }
            let k = format!("{s}|{name}");
            let safe = if val.is_finite() { val } else { 0.0 };
            self.declared_defaults.insert(k.clone(), safe);
            self.values.entry(k).or_insert(safe);
        }
    }

    pub fn set_scope_parent(&mut self, scope_id: &str, parent_scope_id: Option<&str>) {
        if !scope_id.is_empty() {
            self.scope_ancestors.insert(
                scope_id.to_string(),
                parent_scope_id.unwrap_or("@").to_string(),
            );
        }
    }

    pub fn to_flat_map(&self, scope_id: &str) -> HashMap<String, f64> {
        let mut out = HashMap::new();
        for (k, &v) in &self.values {
            if let Some((_, name)) = k.split_once('|') {
                out.entry(name.to_string()).or_insert(v);
            }
        }
        // Let scope-specific values overwrite globals
        for name in out.keys().cloned().collect::<Vec<_>>() {
            let val = self.get(&name, scope_id);
            out.insert(name, val);
        }
        out
    }
}
