use serde::{Deserialize, Serialize};

use super::eval::{eval_node, EvalContext};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum ActionSideEffect {
    MediaCommand {
        command: String,
    },
    ScheduleDelay {
        element_id: String,
        event_name: String,
        delay_ms: u64,
        action_str: String,
    },
    CancelDelay {
        element_id: String,
        event_name: String,
    },
    FireTarget {
        target_alias: String,
        target_event: String,
        depth: u8,
    },
    WizCommand {
        command: String,
        device_id: String,
        arg: Option<String>,
    },
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionExecutionResult {
    pub state_changed: bool,
    pub side_effects: Vec<ActionSideEffect>,
}

const ALLOWED_ACTIONS: &[&str] = &[
    "set",
    "flip",
    "inc",
    "cyclestate",
    "reset",
    "delay",
    "cancel",
    "media",
    "fire",
    "wiz",
];

pub fn split_commands(action_str: &str) -> Vec<String> {
    let mut cmds = Vec::new();
    let mut cur = String::new();
    let mut depth = 0usize;
    let mut in_quote: Option<char> = None;
    let mut prev_backslash = false;

    for ch in action_str.chars() {
        if let Some(q) = in_quote {
            cur.push(ch);
            if ch == q && !prev_backslash {
                in_quote = None;
            }
            prev_backslash = ch == '\\' && !prev_backslash;
            continue;
        }
        prev_backslash = false;

        match ch {
            '"' | '\'' => {
                in_quote = Some(ch);
                cur.push(ch);
            }
            '(' => {
                depth += 1;
                cur.push(ch);
            }
            ')' => {
                depth = depth.saturating_sub(1);
                cur.push(ch);
            }
            ';' if depth == 0 => {
                let t = cur.trim();
                if !t.is_empty() {
                    cmds.push(t.to_string());
                }
                cur.clear();
            }
            _ => cur.push(ch),
        }
    }

    let last = cur.trim();
    if !last.is_empty() {
        cmds.push(last.to_string());
    }
    if cmds.len() > 16 {
        cmds.truncate(16);
    }
    cmds
}

pub fn split_args(args_str: &str) -> Vec<String> {
    let mut args = Vec::new();
    let mut cur = String::new();
    let mut depth = 0usize;
    let mut in_quote: Option<char> = None;
    let mut prev_backslash = false;

    for ch in args_str.chars() {
        if let Some(q) = in_quote {
            cur.push(ch);
            if ch == q && !prev_backslash {
                in_quote = None;
            }
            prev_backslash = ch == '\\' && !prev_backslash;
            continue;
        }
        prev_backslash = false;

        match ch {
            '"' | '\'' => {
                in_quote = Some(ch);
                cur.push(ch);
            }
            '(' => {
                depth += 1;
                cur.push(ch);
            }
            ')' => {
                depth = depth.saturating_sub(1);
                cur.push(ch);
            }
            ',' if depth == 0 => {
                args.push(cur.trim().to_string());
                cur.clear();
            }
            _ => cur.push(ch),
        }
    }
    let last = cur.trim();
    if !last.is_empty() {
        args.push(last.to_string());
    }
    args
}

fn unquote(s: &str) -> String {
    let t = s.trim();
    if t.len() >= 2
        && ((t.starts_with('"') && t.ends_with('"')) || (t.starts_with('\'') && t.ends_with('\'')))
    {
        return t[1..t.len() - 1].trim().to_string();
    }
    t.to_string()
}

pub fn execute_actions(
    action_str: &str,
    element_id: &str,
    event_name: &str,
    depth: u8,
    ctx: &mut EvalContext,
) -> ActionExecutionResult {
    let mut result = ActionExecutionResult::default();
    let trimmed = action_str.trim();
    if trimmed.is_empty() || depth >= 8 {
        return result;
    }

    let commands = split_commands(trimmed);
    for cmd_str in commands {
        let (cmd_name, args) = if let (Some(open), Some(close)) =
            (cmd_str.find('('), cmd_str.rfind(')'))
        {
            if close > open {
                let name = cmd_str[..open].trim().to_ascii_lowercase();
                let args_raw = cmd_str[open + 1..close].trim();
                (name, split_args(args_raw))
            } else {
                continue;
            }
        } else if cmd_str.eq_ignore_ascii_case("cancel")
            || cmd_str.eq_ignore_ascii_case("cancel()")
        {
            ("cancel".to_string(), Vec::new())
        } else {
            continue;
        };

        if !ALLOWED_ACTIONS.contains(&cmd_name.as_str()) {
            continue;
        }

        let scope = ctx.scope_id.clone();
        match cmd_name.as_str() {
            "set" if args.len() >= 2 => {
                let name = unquote(&args[0]);
                let val = eval_node(&args[1], ctx);
                ctx.state_store.set(&name, val, &scope);
                result.state_changed = true;
            }
            "flip" if !args.is_empty() => {
                let name = unquote(&args[0]);
                ctx.state_store.flip(&name, &scope);
                result.state_changed = true;
            }
            "inc" if !args.is_empty() => {
                let name = unquote(&args[0]);
                let step = args.get(1).map(|a| eval_node(a, ctx)).unwrap_or(1.0);
                let min = args
                    .get(2)
                    .filter(|a| !a.is_empty())
                    .map(|a| eval_node(a, ctx))
                    .unwrap_or(f64::NEG_INFINITY);
                let max = args
                    .get(3)
                    .filter(|a| !a.is_empty())
                    .map(|a| eval_node(a, ctx))
                    .unwrap_or(f64::INFINITY);
                let wrap = args
                    .get(4)
                    .map(|a| eval_node(a, ctx) != 0.0)
                    .unwrap_or(false);
                ctx.state_store.inc(&name, step, min, max, wrap, &scope);
                result.state_changed = true;
            }
            "cyclestate" if args.len() >= 2 => {
                let name = unquote(&args[0]);
                let vals: Vec<f64> = args[1..].iter().map(|a| eval_node(a, ctx)).collect();
                ctx.state_store.cyclestate(&name, &vals, &scope);
                result.state_changed = true;
            }
            "reset" => {
                let target_var = args.first().map(|a| unquote(a)).filter(|s| !s.is_empty());
                ctx.state_store
                    .reset(Some(&scope), target_var.as_deref());
                result.state_changed = true;
            }
            "delay" if args.len() >= 2 => {
                let delay_ms = eval_node(&args[0], ctx).max(0.0).round() as u64;
                let delayed_action = args[1..].join(",");
                result.side_effects.push(ActionSideEffect::ScheduleDelay {
                    element_id: element_id.to_string(),
                    event_name: event_name.to_string(),
                    delay_ms,
                    action_str: delayed_action,
                });
            }
            "cancel" => {
                result.side_effects.push(ActionSideEffect::CancelDelay {
                    element_id: element_id.to_string(),
                    event_name: event_name.to_string(),
                });
            }
            "media" if !args.is_empty() => {
                let m_cmd = unquote(&args[0]);
                if matches!(
                    m_cmd.as_str(),
                    "play_pause" | "next" | "prev" | "shuffle" | "loop"
                ) {
                    result
                        .side_effects
                        .push(ActionSideEffect::MediaCommand { command: m_cmd });
                }
            }
            "fire" if args.len() >= 2 => {
                let target_alias = unquote(&args[0]);
                let target_event = unquote(&args[1]);
                result.side_effects.push(ActionSideEffect::FireTarget {
                    target_alias,
                    target_event,
                    depth: depth + 1,
                });
            }
            "wiz" if args.len() >= 2 => {
                let w_cmd = unquote(&args[0]);
                let device_id = unquote(&args[1]);
                let arg = args.get(2).map(|a| unquote(a)).filter(|s| !s.is_empty());
                if matches!(w_cmd.as_str(), "toggle" | "on" | "off" | "scene" | "dim" | "rgb") {
                    result.side_effects.push(ActionSideEffect::WizCommand {
                        command: w_cmd,
                        device_id,
                        arg,
                    });
                }
            }
            _ => {}
        }
    }

    result
}
