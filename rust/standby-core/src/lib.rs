pub mod expr;
pub mod geometry;
pub mod mask;
pub mod presets;
pub mod smarthome;
pub mod triggers;

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use expr::actions::execute_actions;
use expr::ast::ExprVal;
use expr::color::{eval_color_expr, MaterialYouPalette, RgbaColor};
use expr::eval::{
    eval_condition, eval_property_numeric, interpolate_text, validate_expression, EvalContext,
};
use expr::state::StateStore;
use geometry::{
    align_object_to_screen, align_selected_objects, compute_snap, get_object_world_transform,
    recalculate_group_auto_layout, set_pivot_keep_visual_position, AlignMode, ScreenAlignAnchor,
};
use mask::{
    compute_mask_cache_key, compute_occlusion_ratio, evaluate_depth_limit, process_mask_rgba,
    MaskProcessOptions,
};
use presets::{
    import_cripertoshell_preset, pack_standby_bundle, sanitize_and_validate_preset,
    unpack_standby_bundle, BundleAsset, SceneObject,
};
use smarthome::wiz::{wiz_scene_catalog, WizDriver, WIZ_PORT};
use smarthome::SmartDeviceDriver;
use triggers::{evaluate_standby_state, TriggerRules, TriggerSnapshot};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{msg}")]
    Message { msg: String },
}

impl From<String> for CoreError {
    fn from(msg: String) -> Self {
        Self::Message { msg }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TickMode {
    MinuteOnly,
    Second,
    Frame60,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiBundleAsset {
    pub archive_path: String,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiExtractedBundle {
    pub preset_json: String,
    pub assets: Vec<FfiBundleAsset>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiProcessedMask {
    pub width: u32,
    pub height: u32,
    pub rgba_bytes: Vec<u8>,
    pub occupancy_grid: Vec<u32>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FfiEvalContextDto {
    #[serde(default = "default_root_scope")]
    pub scope_id: String,
    #[serde(default)]
    pub base_value: f64,
    #[serde(default)]
    pub t: f64,
    #[serde(default = "default_12")]
    pub hour: i32,
    #[serde(default)]
    pub minute: i32,
    #[serde(default)]
    pub second: f64,
    #[serde(default)]
    pub millisecond: f64,
    #[serde(default = "default_bat")]
    pub battery: f64,
    #[serde(default = "default_true")]
    pub has_battery: bool,
    #[serde(default)]
    pub is_charging: bool,
    #[serde(default)]
    pub has_media: bool,
    #[serde(default)]
    pub is_playing: bool,
    #[serde(default)]
    pub has_cover: bool,
    #[serde(default)]
    pub media_progress: f64,
    #[serde(default)]
    pub media_position: f64,
    #[serde(default)]
    pub media_length: f64,
    #[serde(default)]
    pub is_shuffle: bool,
    #[serde(default)]
    pub is_loop: bool,
    #[serde(default)]
    pub can_next: bool,
    #[serde(default)]
    pub can_prev: bool,
    #[serde(default)]
    pub media_title: String,
    #[serde(default)]
    pub media_artist: String,
    #[serde(default)]
    pub weather_temp: String,
    #[serde(default)]
    pub date_text: String,
    #[serde(default)]
    pub datetime_text: String,
    #[serde(default = "default_sw")]
    pub screen_w: f64,
    #[serde(default = "default_sh")]
    pub screen_h: f64,
    #[serde(default = "default_vol")]
    pub volume: f64,
    #[serde(default = "default_lux")]
    pub lux: f64,
    #[serde(default)]
    pub wiz_on: bool,
    #[serde(default = "default_bat")]
    pub wiz_dim: f64,
    #[serde(default)]
    pub wiz_name: String,
    #[serde(default)]
    pub wiz_hex: String,
    #[serde(default)]
    pub is_hovered: bool,
    #[serde(default)]
    pub is_pressed: bool,
    #[serde(default)]
    pub state_values: HashMap<String, f64>,
    #[serde(default)]
    pub scope_ancestors: HashMap<String, String>,
    #[serde(default)]
    pub element_props: HashMap<String, HashMap<String, serde_json::Value>>,
    #[serde(default)]
    pub anim_slots: HashMap<String, f64>,
    #[serde(default)]
    pub palette: Option<MaterialYouPalette>,
}

fn default_root_scope() -> String {
    "@".to_string()
}
fn default_12() -> i32 {
    12
}
fn default_bat() -> f64 {
    100.0
}
fn default_true() -> bool {
    true
}
fn default_sw() -> f64 {
    2400.0
}
fn default_sh() -> f64 {
    1080.0
}
fn default_vol() -> f64 {
    0.7
}
fn default_lux() -> f64 {
    100.0
}

fn json_val_to_expr_val(v: &serde_json::Value) -> ExprVal {
    match v {
        serde_json::Value::Number(n) => ExprVal::Num(n.as_f64().unwrap_or(0.0)),
        serde_json::Value::Bool(b) => ExprVal::Bool(*b),
        serde_json::Value::String(s) => ExprVal::Str(s.clone()),
        _ => ExprVal::Num(0.0),
    }
}

fn dto_to_eval_ctx(dto: FfiEvalContextDto) -> EvalContext {
    let mut store = StateStore::new();
    store.values = dto.state_values;
    store.scope_ancestors = dto.scope_ancestors;
    let element_props = dto
        .element_props
        .into_iter()
        .map(|(k, m)| {
            let inner = m
                .into_iter()
                .map(|(pk, pv)| (pk, json_val_to_expr_val(&pv)))
                .collect();
            (k, inner)
        })
        .collect();

    EvalContext {
        scope_id: dto.scope_id,
        base_value: dto.base_value,
        t: dto.t,
        hour: dto.hour,
        minute: dto.minute,
        second: dto.second,
        millisecond: dto.millisecond,
        battery: dto.battery,
        has_battery: dto.has_battery,
        is_charging: dto.is_charging,
        has_media: dto.has_media,
        is_playing: dto.is_playing,
        has_cover: dto.has_cover,
        media_progress: dto.media_progress,
        media_position: dto.media_position,
        media_length: dto.media_length,
        is_shuffle: dto.is_shuffle,
        is_loop: dto.is_loop,
        can_next: dto.can_next,
        can_prev: dto.can_prev,
        media_title: dto.media_title,
        media_artist: dto.media_artist,
        weather_temp: dto.weather_temp,
        date_text: dto.date_text,
        datetime_text: dto.datetime_text,
        screen_w: dto.screen_w,
        screen_h: dto.screen_h,
        volume: dto.volume,
        lux: dto.lux,
        wiz_on: dto.wiz_on,
        wiz_dim: dto.wiz_dim,
        wiz_name: dto.wiz_name,
        wiz_hex: dto.wiz_hex,
        is_hovered: dto.is_hovered,
        is_pressed: dto.is_pressed,
        state_store: store,
        custom_vars: HashMap::new(),
        element_props,
        anim_slots: dto.anim_slots,
        palette: dto.palette.unwrap_or_default(),
    }
}

fn parse_ctx_json(ctx_json: &str) -> EvalContext {
    if ctx_json.trim().is_empty() {
        return EvalContext::default();
    }
    serde_json::from_str::<FfiEvalContextDto>(ctx_json)
        .map(dto_to_eval_ctx)
        .unwrap_or_default()
}

#[uniffi::export]
pub fn core_version() -> String {
    format!("standby-core v{}", env!("CARGO_PKG_VERSION"))
}

#[uniffi::export]
pub fn eval_expression_numeric(expr: String, prop_name: String, ctx_json: String) -> f64 {
    let ctx = parse_ctx_json(&ctx_json);
    eval_property_numeric(&expr, &prop_name, &ctx)
}

#[uniffi::export]
pub fn eval_expression_condition(cond: String, ctx_json: String) -> bool {
    let ctx = parse_ctx_json(&ctx_json);
    eval_condition(&cond, &ctx)
}

#[uniffi::export]
pub fn eval_expression_color_argb(expr: String, base_argb: u32, ctx_json: String) -> u32 {
    let ctx = parse_ctx_json(&ctx_json);
    let base = RgbaColor::from_argb_u32(base_argb);
    eval_color_expr(&expr, &ctx, base).to_argb_u32()
}

#[uniffi::export]
pub fn interpolate_custom_text(template: String, ctx_json: String) -> String {
    let ctx = parse_ctx_json(&ctx_json);
    interpolate_text(&template, &ctx)
}

#[uniffi::export]
pub fn validate_expression_str(expr: String) -> Option<String> {
    validate_expression(&expr).err()
}

#[uniffi::export]
pub fn analyze_preset_tick_mode(preset_json: String) -> TickMode {
    let report = sanitize_and_validate_preset(&preset_json);
    if report.needs_frame_tick {
        TickMode::Frame60
    } else if report.needs_second_tick {
        TickMode::Second
    } else {
        TickMode::MinuteOnly
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ActionOutputDto {
    state_values: HashMap<String, f64>,
    state_changed: bool,
    side_effects: Vec<expr::actions::ActionSideEffect>,
}

#[uniffi::export]
pub fn execute_widget_actions(
    action_str: String,
    element_id: String,
    event_name: String,
    depth: u8,
    ctx_json: String,
) -> String {
    let mut ctx = parse_ctx_json(&ctx_json);
    let res = execute_actions(&action_str, &element_id, &event_name, depth, &mut ctx);
    let out = ActionOutputDto {
        state_values: ctx.state_store.values,
        state_changed: res.state_changed,
        side_effects: res.side_effects,
    };
    serde_json::to_string(&out).unwrap_or_else(|_| "{}".to_string())
}

#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn compute_snap_json(
    raw_cx: f64,
    raw_cy: f64,
    my_w: f64,
    my_h: f64,
    selected_ids: Vec<String>,
    objects_json: String,
    screen_w: f64,
    screen_h: f64,
    threshold: f64,
    snap_enabled: bool,
) -> String {
    let objects: Vec<SceneObject> = serde_json::from_str(&objects_json).unwrap_or_default();
    let res = compute_snap(
        raw_cx,
        raw_cy,
        my_w,
        my_h,
        &selected_ids,
        &objects,
        screen_w,
        screen_h,
        threshold,
        snap_enabled,
    );
    serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string())
}

#[uniffi::export]
pub fn set_pivot_keep_visual_json(
    obj_json: String,
    objects_json: String,
    new_px: f64,
    new_py: f64,
    screen_w: f64,
    screen_h: f64,
) -> Result<String, CoreError> {
    let mut obj: SceneObject = serde_json::from_str(&obj_json).map_err(|e| e.to_string())?;
    let objects: Vec<SceneObject> = serde_json::from_str(&objects_json).unwrap_or_default();
    set_pivot_keep_visual_position(&mut obj, &objects, new_px, new_py, screen_w, screen_h);
    serde_json::to_string(&obj).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn align_object_screen_json(
    obj_json: String,
    objects_json: String,
    anchor: String,
    screen_w: f64,
    screen_h: f64,
) -> Result<String, CoreError> {
    let mut obj: SceneObject = serde_json::from_str(&obj_json).map_err(|e| e.to_string())?;
    let objects: Vec<SceneObject> = serde_json::from_str(&objects_json).unwrap_or_default();
    let anchor_enum = match anchor.as_str() {
        "top_left" => ScreenAlignAnchor::TopLeft,
        "top_center" => ScreenAlignAnchor::TopCenter,
        "top_right" => ScreenAlignAnchor::TopRight,
        "middle_left" => ScreenAlignAnchor::MiddleLeft,
        "center" => ScreenAlignAnchor::Center,
        "middle_right" => ScreenAlignAnchor::MiddleRight,
        "bottom_left" => ScreenAlignAnchor::BottomLeft,
        "bottom_center" => ScreenAlignAnchor::BottomCenter,
        "bottom_right" => ScreenAlignAnchor::BottomRight,
        _ => ScreenAlignAnchor::Center,
    };
    align_object_to_screen(&mut obj, &objects, anchor_enum, screen_w, screen_h);
    serde_json::to_string(&obj).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn align_selected_objects_json(
    mode: String,
    selected_ids: Vec<String>,
    objects_json: String,
    screen_w: f64,
    screen_h: f64,
) -> Result<String, CoreError> {
    let mut objects: Vec<SceneObject> =
        serde_json::from_str(&objects_json).map_err(|e| e.to_string())?;
    let align_mode = match mode.as_str() {
        "left" => AlignMode::Left,
        "center_h" => AlignMode::CenterH,
        "right" => AlignMode::Right,
        "top" => AlignMode::Top,
        "center_v" => AlignMode::CenterV,
        "bottom" => AlignMode::Bottom,
        "distribute_h" => AlignMode::DistributeH,
        "distribute_v" => AlignMode::DistributeV,
        _ => return Err(CoreError::Message { msg: format!("Modo de alineación inválido: {mode}") }),
    };
    align_selected_objects(align_mode, &selected_ids, &mut objects, screen_w, screen_h);
    serde_json::to_string(&objects).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn get_object_world_transform_json(
    obj_json: String,
    objects_json: String,
    screen_w: f64,
    screen_h: f64,
    is_animating: bool,
) -> Result<String, CoreError> {
    let obj: SceneObject = serde_json::from_str(&obj_json).map_err(|e| e.to_string())?;
    let objects: Vec<SceneObject> = serde_json::from_str(&objects_json).unwrap_or_default();
    let wt = get_object_world_transform(&obj, &objects, screen_w, screen_h, is_animating);
    serde_json::to_string(&wt).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn recalculate_group_layout_json(
    group_id: String,
    objects_json: String,
) -> Result<String, CoreError> {
    let mut objects: Vec<SceneObject> =
        serde_json::from_str(&objects_json).map_err(|e| e.to_string())?;
    recalculate_group_auto_layout(&group_id, &mut objects);
    serde_json::to_string(&objects).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn validate_preset_json(json_str: String) -> String {
    let report = sanitize_and_validate_preset(&json_str);
    serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string())
}

#[uniffi::export]
pub fn import_cripertoshell_json(json_str: String) -> Result<String, CoreError> {
    let preset = import_cripertoshell_preset(&json_str)?;
    serde_json::to_string_pretty(&preset).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn pack_standby_zip(
    preset_json: String,
    assets: Vec<FfiBundleAsset>,
) -> Result<Vec<u8>, CoreError> {
    let internal: Vec<BundleAsset> = assets
        .into_iter()
        .map(|a| BundleAsset {
            archive_path: a.archive_path,
            bytes: a.bytes,
        })
        .collect();
    pack_standby_bundle(&preset_json, &internal).map_err(Into::into)
}

#[uniffi::export]
pub fn unpack_standby_zip(zip_bytes: Vec<u8>) -> Result<FfiExtractedBundle, CoreError> {
    let extracted = unpack_standby_bundle(&zip_bytes)?;
    Ok(FfiExtractedBundle {
        preset_json: extracted.preset_json,
        assets: extracted
            .assets
            .into_iter()
            .map(|a| FfiBundleAsset {
                archive_path: a.archive_path,
                bytes: a.bytes,
            })
            .collect(),
    })
}

#[uniffi::export]
pub fn compute_mask_key(image_tag: String, model_name: String, invert: bool, choke: f32) -> String {
    compute_mask_cache_key(&image_tag, &model_name, invert, choke)
}

#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn process_mask_rgba_ffi(
    alpha_bytes: Vec<u8>,
    orig_rgba: Vec<u8>,
    width: u32,
    height: u32,
    invert: bool,
    choke: f32,
    smooth_radius: f32,
    is_low_res_model: bool,
) -> Result<FfiProcessedMask, CoreError> {
    let opts = MaskProcessOptions {
        invert,
        choke,
        smooth_radius,
        is_low_res_model,
    };
    let out = process_mask_rgba(&alpha_bytes, &orig_rgba, width, height, &opts)?;
    Ok(FfiProcessedMask {
        width: out.width,
        height: out.height,
        rgba_bytes: out.rgba_bytes,
        occupancy_grid: out.occupancy_grid,
    })
}

#[uniffi::export]
pub fn compute_occlusion_ratio_ffi(grid: Vec<u32>, u0: f32, v0: f32, u1: f32, v1: f32) -> f32 {
    compute_occlusion_ratio(&grid, u0, v0, u1, v1)
}

#[uniffi::export]
pub fn evaluate_depth_limit_ffi(ratio: f32, threshold_pct: f32, prev_over: bool) -> bool {
    evaluate_depth_limit(ratio, threshold_pct, prev_over)
}

#[uniffi::export]
pub fn evaluate_standby_rules_json(
    rules_json: String,
    snapshot_json: String,
) -> Result<String, CoreError> {
    let rules: TriggerRules = serde_json::from_str(&rules_json).map_err(|e| e.to_string())?;
    let snap: TriggerSnapshot = serde_json::from_str(&snapshot_json).map_err(|e| e.to_string())?;
    let dec = evaluate_standby_state(&rules, &snap);
    serde_json::to_string(&dec).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn wiz_discover_json(
    subnet_prefix: Option<String>,
    timeout_ms: u32,
    sweep_subnet: bool,
) -> Result<String, CoreError> {
    let drv = WizDriver;
    let devs = drv.discover(subnet_prefix.as_deref(), timeout_ms, sweep_subnet)?;
    serde_json::to_string(&devs).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn wiz_probe_json(ip: String, port: u16) -> Result<String, CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    let dev = drv.probe_device(&ip, target_port, 700)?;
    serde_json::to_string(&dev).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn wiz_get_state_json(ip: String, port: u16) -> Result<String, CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    let st = drv.get_state(&ip, target_port)?;
    serde_json::to_string(&st).map_err(|e| CoreError::Message { msg: e.to_string() })
}

#[uniffi::export]
pub fn wiz_set_power_ffi(ip: String, port: u16, on: bool) -> Result<(), CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    drv.set_power(&ip, target_port, on).map_err(Into::into)
}

#[uniffi::export]
pub fn wiz_set_brightness_ffi(ip: String, port: u16, dimming: u8) -> Result<(), CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    drv.set_brightness(&ip, target_port, dimming).map_err(Into::into)
}

#[uniffi::export]
pub fn wiz_set_rgb_ffi(
    ip: String,
    port: u16,
    r: u8,
    g: u8,
    b: u8,
    dimming: Option<u8>,
) -> Result<(), CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    drv.set_rgb(&ip, target_port, r, g, b, dimming)
        .map_err(Into::into)
}

#[uniffi::export]
pub fn wiz_set_temp_ffi(
    ip: String,
    port: u16,
    kelvin: u16,
    dimming: Option<u8>,
) -> Result<(), CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    drv.set_temp(&ip, target_port, kelvin, dimming)
        .map_err(Into::into)
}

#[uniffi::export]
pub fn wiz_set_scene_ffi(
    ip: String,
    port: u16,
    scene_id: u16,
    speed: Option<u8>,
    dimming: Option<u8>,
) -> Result<(), CoreError> {
    let drv = WizDriver;
    let target_port = if port == 0 { WIZ_PORT } else { port };
    drv.set_scene(&ip, target_port, scene_id, speed, dimming)
        .map_err(Into::into)
}

#[uniffi::export]
pub fn wiz_scenes_catalog_json() -> String {
    serde_json::to_string(&wiz_scene_catalog()).unwrap_or_else(|_| "[]".to_string())
}
