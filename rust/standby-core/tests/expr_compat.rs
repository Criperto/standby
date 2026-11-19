use serde_json::Value;
use standby_core::expr::actions::{execute_actions, ActionSideEffect};
use standby_core::expr::ast::{analyze_dependencies, node_to_string, parse_ast, AstNode};
use standby_core::expr::color::{eval_color_expr, MaterialYouPalette, RgbaColor};
use standby_core::expr::easing::SpringSolver;
use standby_core::expr::eval::{
    eval_condition, eval_node, eval_value, interpolate_text, validate_expression, EvalContext,
};
use standby_core::expr::state::StateStore;
use std::collections::HashMap;

fn build_context_from_json(ctx_val: &Value, base_val: Option<f64>) -> EvalContext {
    let mut ctx = EvalContext::default();
    if let Some(bv) = base_val {
        ctx.base_value = bv;
    }
    let Some(obj) = ctx_val.as_object() else {
        return ctx;
    };

    let scope_id = obj
        .get("scopeId")
        .and_then(|v| v.as_str())
        .unwrap_or("@")
        .to_string();
    ctx.scope_id = scope_id.clone();

    let mut store = StateStore::new();
    if let Some(state_obj) = obj.get("state").and_then(|v| v.as_object()) {
        let mut map = HashMap::new();
        for (k, v) in state_obj {
            if let Some(n) = v.as_f64() {
                map.insert(k.clone(), n);
            }
        }
        store.declare(&scope_id, &map);
    }

    for (k, v) in obj {
        match k.as_str() {
            "scopeId" | "state" => {}
            "baseValue" => ctx.base_value = v.as_f64().unwrap_or(ctx.base_value),
            "t" => ctx.t = v.as_f64().unwrap_or(0.0),
            "hour" => ctx.hour = v.as_i64().unwrap_or(0) as i32,
            "minute" => ctx.minute = v.as_i64().unwrap_or(0) as i32,
            "second" => ctx.second = v.as_f64().unwrap_or(0.0),
            "millisecond" => ctx.millisecond = v.as_f64().unwrap_or(0.0),
            "battery" => ctx.battery = v.as_f64().unwrap_or(85.0),
            "hasBattery" => ctx.has_battery = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "isCharging" => ctx.is_charging = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "hasMedia" => ctx.has_media = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "isPlaying" => ctx.is_playing = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "hasCover" => ctx.has_cover = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "mediaProgress" => ctx.media_progress = v.as_f64().unwrap_or(0.0),
            "mediaPosition" => ctx.media_position = v.as_f64().unwrap_or(0.0),
            "mediaLength" => ctx.media_length = v.as_f64().unwrap_or(0.0),
            "isShuffle" => ctx.is_shuffle = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "isLoop" => ctx.is_loop = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "canNext" => ctx.can_next = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "canPrev" => ctx.can_prev = v.as_bool().unwrap_or(v.as_f64().unwrap_or(0.0) != 0.0),
            "screenW" => ctx.screen_w = v.as_f64().unwrap_or(1920.0),
            "screenH" => ctx.screen_h = v.as_f64().unwrap_or(1080.0),
            "volume" | "audioVol" => ctx.volume = v.as_f64().unwrap_or(0.7),
            "isCapsLock" | "isWrongPassword" => {}
            other => {
                if let Some(sub_obj) = v.as_object() {
                    let mut pmap = HashMap::new();
                    for (pk, pv) in sub_obj {
                        if let Some(n) = pv.as_f64() {
                            pmap.insert(pk.clone(), standby_core::expr::ast::ExprVal::Num(n));
                        } else if let Some(s) = pv.as_str() {
                            pmap.insert(pk.clone(), standby_core::expr::ast::ExprVal::Str(s.to_string()));
                        }
                    }
                    ctx.element_props.insert(other.to_string(), pmap);
                } else if let Some(n) = v.as_f64() {
                    store.set(other, n, &scope_id);
                } else if let Some(b) = v.as_bool() {
                    store.set(other, if b { 1.0 } else { 0.0 }, &scope_id);
                }
            }
        }
    }
    ctx.state_store = store;
    ctx
}

#[test]
fn all_cripertoshell_expr_cases_pass() {
    let raw = include_str!("fixtures/expr_cases.json");
    let cases: Vec<Value> = serde_json::from_str(raw).expect("valid fixture json");
    assert!(cases.len() >= 50, "expected full expr_cases suite");

    for case in &cases {
        let id = case["id"].as_str().unwrap();
        let kind = case["type"].as_str().unwrap();
        let expr = case["expr"].as_str().unwrap();
        let base_val = case.get("currentVal").and_then(|v| v.as_f64());
        let ctx = build_context_from_json(&case["context"], base_val);

        match kind {
            "condition" => {
                let expected = case["expected"].as_bool().unwrap();
                let got = eval_condition(expr, &ctx);
                assert_eq!(got, expected, "case {id} ({expr}) failed");
            }
            "node" => {
                let expected = case["expected"].as_f64().unwrap();
                let got = eval_node(expr, &ctx);
                assert!(
                    (got - expected).abs() < 1e-6,
                    "case {id} ({expr}): expected {expected}, got {got}"
                );
            }
            "parse" => {
                let ast = parse_ast(expr).expect("parse should succeed");
                let AstNode::Call { args, .. } = ast else {
                    panic!("case {id}: expected Call AST");
                };
                let expected_count = case["expectedArgsCount"].as_u64().unwrap() as usize;
                let expected_last = case["expectedLastArg"].as_str().unwrap();
                assert_eq!(args.len(), expected_count, "case {id} args count");
                assert_eq!(
                    node_to_string(args.last().unwrap()),
                    expected_last,
                    "case {id} last arg"
                );
            }
            other => panic!("unknown case type {other} in {id}"),
        }
    }
}

#[test]
fn regex_and_string_functions_work() {
    let mut ctx = EvalContext::default();
    ctx.media_title = "Daft Punk - Harder Better (Official Video) [2001]".to_string();
    ctx.media_artist = "  Daft Punk  ".to_string();

    assert!(eval_condition(r#"regexMatch(mediaTitle, "(?i)official\s+video")"#, &ctx));
    assert!(!eval_condition(r#"regexMatch(mediaTitle, "^Taylor")"#, &ctx));

    let extracted = eval_value(r#"regexExtract(mediaTitle, "\[(\d{4})\]", 1)"#, &ctx);
    assert_eq!(extracted.as_str(), "2001");

    let cleaned = eval_value(
        r#"trim(regexReplace(mediaTitle, "\s*\([^)]*\)\s*|\s*\[[^\]]*\]", ""))"#,
        &ctx,
    );
    assert_eq!(cleaned.as_str(), "Daft Punk - Harder Better");

    assert_eq!(eval_value("upper(trim(mediaArtist))", &ctx).as_str(), "DAFT PUNK");
    assert_eq!(eval_node("len(trim(mediaArtist))", &ctx), 9.0);
    assert!(eval_condition(r#"contains(mediaTitle, "Harder")"#, &ctx));

    // Embedded ${...} and $MACROS in custom_text
    let rendered = interpolate_text(
        r#"Sonando: ${regexReplace(mediaTitle, "\s*\(.*?\)", "")} • $BATTERY ($CHARGING)"#,
        &ctx,
    );
    assert_eq!(
        rendered,
        "Sonando: Daft Punk - Harder Better [2001] • 85% (Batería)"
    );

    // Static regex validation catches malformed patterns
    assert!(validate_expression(r#"regexMatch(mediaTitle, "[unclosed")"#).is_err());
    assert!(validate_expression(r#"regexMatch(mediaTitle, "ok+")"#).is_ok());
}

#[test]
fn color_expressions_and_material_you_tokens() {
    let mut ctx = EvalContext::default();
    ctx.palette = MaterialYouPalette::default();
    ctx.battery = 50.0;
    ctx.is_playing = true;

    let white_black_mid = eval_color_expr("mix(#000000, #ffffff, 0.5)", &ctx, RgbaColor::BLACK);
    assert!((white_black_mid.r - 0.5).abs() < 0.02);

    let playing_col = eval_color_expr("when(isPlaying, primary, onSurface)", &ctx, RgbaColor::WHITE);
    assert_eq!(playing_col.to_argb_u32(), ctx.palette.primary);

    let bat_mid = eval_color_expr("battery(#ff0000, #00ff00)", &ctx, RgbaColor::WHITE);
    assert!((bat_mid.r - 0.5).abs() < 0.02 && (bat_mid.g - 0.5).abs() < 0.02);
}

#[test]
fn spring_solver_converges_and_actions_execute() {
    let mut spring = SpringSolver::new(0.0, 10.0, 0.7, 1.0, 0.002);
    spring.set_target(100.0);
    for _ in 0..120 {
        spring.step(0.016);
    }
    assert!(spring.is_settled());
    assert!((spring.value - 100.0).abs() < 0.01);

    let mut ctx = EvalContext::default();
    ctx.scope_id = "grp_wiz".to_string();
    ctx.state_store.declare("grp_wiz", &HashMap::from([("mode".into(), 0.0)]));

    let res = execute_actions(
        r#"flip(mode); wiz("toggle", "wiz_desk"); delay(5000, set(mode, 0))"#,
        "btn_1",
        "onTap",
        0,
        &mut ctx,
    );
    assert!(res.state_changed);
    assert_eq!(ctx.state_store.get("mode", "grp_wiz"), 1.0);
    assert_eq!(res.side_effects.len(), 2);
    assert!(matches!(
        &res.side_effects[0],
        ActionSideEffect::WizCommand { command, device_id, .. }
        if command == "toggle" && device_id == "wiz_desk"
    ));

    // Dependency analyzer detects minute vs second vs 60fps tick modes
    let static_deps = analyze_dependencies("battery > 20");
    assert!(!static_deps.needs_frame_t && !static_deps.needs_seconds);
    let anim_deps = analyze_dependencies("spin(45)");
    assert!(anim_deps.needs_frame_t);
}
