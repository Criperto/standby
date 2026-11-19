use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::io::{Cursor, Read, Write};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

use crate::expr::eval::validate_expression;

pub const CURRENT_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackgroundConfig {
    #[serde(default = "default_bg_mode")]
    pub mode: String, // "black" | "wallpaper"
    #[serde(default)]
    pub wallpaper_uri: Option<String>,
    #[serde(default = "default_bg_brightness")]
    pub brightness: f64,
    #[serde(default)]
    pub blur_radius: f64,
    #[serde(default = "default_night_dim")]
    pub night_dim_factor: f64,
    #[serde(default)]
    pub shader_id: Option<String>,
    #[serde(default)]
    pub shader_params: HashMap<String, f64>,
}

fn default_bg_mode() -> String {
    "black".to_string()
}
fn default_bg_brightness() -> f64 {
    0.35
}
fn default_night_dim() -> f64 {
    0.5
}

impl Default for BackgroundConfig {
    fn default() -> Self {
        Self {
            mode: default_bg_mode(),
            wallpaper_uri: None,
            brightness: default_bg_brightness(),
            blur_radius: 0.0,
            night_dim_factor: default_night_dim(),
            shader_id: None,
            shader_params: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PresetMeta {
    #[serde(default)]
    pub motion_defaults: HashMap<String, String>,
    #[serde(default)]
    pub target_aspect: Option<String>,
    #[serde(default)]
    pub created_by: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GradientStop {
    #[serde(default)]
    pub pos: f64,
    #[serde(default = "default_accent_choice")]
    pub color_choice: String,
    #[serde(default)]
    pub is_custom: bool,
    #[serde(default = "default_blue_hex")]
    pub hex: String,
    #[serde(default = "default_one")]
    pub alpha: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GradientSpec {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_linear", rename = "type")]
    pub grad_type: String, // linear | radial | angular | diamond
    #[serde(default = "default_half")]
    pub p0x: f64,
    #[serde(default)]
    pub p0y: f64,
    #[serde(default = "default_half")]
    pub p1x: f64,
    #[serde(default = "default_one")]
    pub p1y: f64,
    #[serde(default = "default_one")]
    pub p2x: f64,
    #[serde(default)]
    pub p2y: f64,
    #[serde(default)]
    pub stops: Vec<GradientStop>,
}

impl Default for GradientSpec {
    fn default() -> Self {
        Self {
            enabled: false,
            grad_type: default_linear(),
            p0x: 0.5,
            p0y: 0.0,
            p1x: 0.5,
            p1y: 1.0,
            p2x: 1.0,
            p2y: 0.0,
            stops: vec![
                GradientStop {
                    pos: 0.0,
                    color_choice: "primary".to_string(),
                    is_custom: false,
                    hex: "#A8C7FA".to_string(),
                    alpha: 1.0,
                },
                GradientStop {
                    pos: 1.0,
                    color_choice: "tertiary".to_string(),
                    is_custom: false,
                    hex: "#DDBCE0".to_string(),
                    alpha: 1.0,
                },
            ],
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HitboxConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_auto")]
    pub mode: String, // auto | rect | circle | ellipse
    #[serde(default = "default_true")]
    pub consume: bool,
    #[serde(default)]
    pub enabled_when: String,
    #[serde(default)]
    pub padding: f64,
    #[serde(default)]
    pub offset_x: f64,
    #[serde(default)]
    pub offset_y: f64,
    #[serde(default)]
    pub width: Option<f64>,
    #[serde(default)]
    pub height: Option<f64>,
}

impl Default for HitboxConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            mode: default_auto(),
            consume: true,
            enabled_when: String::new(),
            padding: 0.0,
            offset_x: 0.0,
            offset_y: 0.0,
            width: None,
            height: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ActionsConfig {
    #[serde(default)]
    pub on_tap: String,
    #[serde(default)]
    pub on_tap_outside: String,
    #[serde(default)]
    pub on_double_tap: String,
    #[serde(default)]
    pub on_long_press: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SceneObject {
    pub id: String,
    #[serde(rename = "type")]
    pub obj_type: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub alias: String,
    #[serde(default)]
    pub z_index: i32,
    #[serde(default = "default_true")]
    pub visible: bool,
    #[serde(default)]
    pub locked: bool,

    // Hierarchy & groups
    #[serde(default)]
    pub group_id: Option<String>,
    #[serde(default)]
    pub group_offset_x: f64,
    #[serde(default)]
    pub group_offset_y: f64,
    #[serde(default)]
    pub group_width: f64,
    #[serde(default)]
    pub group_height: f64,
    #[serde(default)]
    pub children_ids: Vec<String>,
    #[serde(default)]
    pub collapsed: bool,
    #[serde(default)]
    pub state: HashMap<String, f64>,
    #[serde(default = "default_none")]
    pub auto_layout: String, // none | row | column
    #[serde(default = "default_gap")]
    pub auto_layout_gap: f64,
    #[serde(default = "default_center")]
    pub auto_layout_align: String, // start | center | end

    // Transform & Pivot
    #[serde(default = "default_half", rename = "posX_ratio")]
    pub pos_x_ratio: f64,
    #[serde(default = "default_half", rename = "posY_ratio")]
    pub pos_y_ratio: f64,
    #[serde(default = "default_w")]
    pub base_width: f64,
    #[serde(default = "default_h")]
    pub base_height: f64,
    #[serde(default = "default_one")]
    pub scale_x: f64,
    #[serde(default = "default_one")]
    pub scale_y: f64,
    #[serde(default = "default_one")]
    pub uniform_scale: f64,
    #[serde(default)]
    pub rotation: f64,
    #[serde(default = "default_half")]
    pub pivot_x: f64,
    #[serde(default = "default_half")]
    pub pivot_y: f64,
    #[serde(default = "default_one")]
    pub element_opacity: f64,

    // 3D perspective & parallax
    #[serde(default)]
    pub perspective_x: f64,
    #[serde(default)]
    pub perspective_y: f64,
    #[serde(default = "default_persp_dist")]
    pub perspective_distance: f64,
    #[serde(default)]
    pub parallax_follow: bool,
    #[serde(default = "default_one")]
    pub parallax_factor: f64,

    // Shape geometry
    #[serde(default = "default_rect")]
    pub shape_type: String, // rectangle | circle | triangle | polygon | star
    #[serde(default = "default_radius")]
    pub radius_uniform: f64,
    #[serde(default)]
    pub radius_independent: bool,
    #[serde(default = "default_radius")]
    pub radius_top_left: f64,
    #[serde(default = "default_radius")]
    pub radius_top_right: f64,
    #[serde(default = "default_radius")]
    pub radius_bottom_right: f64,
    #[serde(default = "default_radius")]
    pub radius_bottom_left: f64,
    #[serde(default)]
    pub circle_start: f64,
    #[serde(default = "default_sweep")]
    pub circle_sweep: f64,
    #[serde(default)]
    pub circle_ratio: f64,
    #[serde(default = "default_poly_sides")]
    pub polygon_sides: i32,
    #[serde(default)]
    pub polygon_radius: f64,
    #[serde(default = "default_star_points")]
    pub star_points: i32,
    #[serde(default = "default_star_ratio")]
    pub star_ratio: f64,
    #[serde(default)]
    pub star_radius: f64,

    // Typography
    #[serde(default)]
    pub font_choice: i32,
    #[serde(default = "default_true")]
    pub use_system_font: bool,
    #[serde(default = "default_font_family")]
    pub custom_font_family: String,
    #[serde(default)]
    pub font_source_id: Option<String>,
    #[serde(default)]
    pub font_file_name: Option<String>,
    #[serde(default = "default_font_weight")]
    pub font_weight: i32,
    #[serde(default)]
    pub font_italic: bool,
    #[serde(default = "default_font_size")]
    pub base_font_size: f64,
    #[serde(default)]
    pub letter_spacing: f64,
    #[serde(default = "default_center")]
    pub text_align: String,
    #[serde(default)]
    pub time_format: i32,
    #[serde(default)]
    pub date_format: i32,
    #[serde(default)]
    pub custom_content: String,

    // Fill, Stroke, Gradient, Shadow
    #[serde(default = "default_white_choice")]
    pub text_color_choice: String,
    #[serde(default)]
    pub is_custom_color: bool,
    #[serde(default = "default_white_hex")]
    pub custom_hex: String,
    #[serde(default = "default_one")]
    pub fill_alpha: f64,
    #[serde(default)]
    pub stroke_enabled: bool,
    #[serde(default = "default_stroke_w")]
    pub stroke_width: f64,
    #[serde(default = "default_black_choice")]
    pub stroke_color_choice: String,
    #[serde(default)]
    pub is_custom_stroke_color: bool,
    #[serde(default = "default_black_hex")]
    pub custom_stroke_hex: String,
    #[serde(default)]
    pub gradient: Option<GradientSpec>,
    #[serde(default)]
    pub stroke_gradient: Option<GradientSpec>,
    #[serde(default)]
    pub tint_gradient: Option<GradientSpec>,

    #[serde(default)]
    pub shadow_enabled: bool,
    #[serde(default)]
    pub shadow_offset_x: f64,
    #[serde(default = "default_shadow_y")]
    pub shadow_offset_y: f64,
    #[serde(default = "default_shadow_blur")]
    pub shadow_blur: f64,
    #[serde(default = "default_black_choice")]
    pub shadow_color_choice: String,
    #[serde(default)]
    pub is_custom_shadow_color: bool,
    #[serde(default = "default_black_hex")]
    pub custom_shadow_hex: String,
    #[serde(default = "default_shadow_alpha")]
    pub custom_shadow_alpha: f64,

    // Blur & Modular Shader / Liquid Glass
    #[serde(default)]
    pub blur_enabled: bool,
    #[serde(default = "default_shadow_alpha")]
    pub blur_amount: f64,
    #[serde(default = "default_blur_max")]
    pub blur_max: f64,
    #[serde(default = "default_glass_tint")]
    pub glass_tint_opacity: f64,

    #[serde(default)]
    pub liquid_glass_enabled: bool,
    #[serde(default = "default_bezel")]
    pub liquid_bezel: f64,
    #[serde(default = "default_thickness")]
    pub liquid_thickness: f64,
    #[serde(default = "default_ior")]
    pub liquid_ior: f64,
    #[serde(default = "default_dispersion")]
    pub liquid_dispersion: f64,
    #[serde(default = "default_frost")]
    pub liquid_frost: f64,
    #[serde(default = "default_saturation")]
    pub liquid_saturation: f64,
    #[serde(default = "default_rim")]
    pub liquid_rim: f64,
    #[serde(default = "default_light_angle")]
    pub liquid_light_angle: f64,
    #[serde(default = "default_tint_amount")]
    pub liquid_tint_amount: f64,
    #[serde(default)]
    pub shader_id: Option<String>,
    #[serde(default)]
    pub shader_params: HashMap<String, f64>,

    // Masking & Depth Cutout
    #[serde(default)]
    pub is_mask_group: bool,
    #[serde(default)]
    pub is_mask: bool,
    #[serde(default)]
    pub mask_target_id: Option<String>,
    #[serde(default)]
    pub mask_target_ids: Vec<String>,
    #[serde(default = "default_cutout")]
    pub mask_mode: String, // clipping | cutout
    #[serde(default)]
    pub mask_collapsed: bool,
    #[serde(default)]
    pub mask_feather: f64,
    #[serde(default = "default_mask_model")]
    pub mask_model_name: String,
    #[serde(default)]
    pub invert_mask: bool,
    #[serde(default = "default_choke")]
    pub mask_choke: f64,
    #[serde(default = "default_depth_mode")]
    pub depth_cutout_mode: String, // normal | limit_50
    #[serde(default = "default_depth_thresh")]
    pub depth_cutout_threshold: f64,

    // Icons, Media, WiZ
    #[serde(default)]
    pub svg_source: Option<String>, // "material:<icon>" | "asset:<file.svg>"
    #[serde(default)]
    pub icon_name: String,
    #[serde(default = "default_media_action")]
    pub media_action: String,
    #[serde(default = "default_true")]
    pub hover_press_effects: bool,
    #[serde(default)]
    pub hide_when_no_cover: bool,
    #[serde(default)]
    pub hide_when_no_media: bool,
    #[serde(default)]
    pub wiz_device_id: Option<String>,
    #[serde(default)]
    pub wiz_action: Option<String>,
    #[serde(default)]
    pub wiz_scene_id: Option<i32>,
    #[serde(default = "default_wiz_style")]
    pub wiz_style: String,
    #[serde(default = "default_wiz_title")]
    pub wiz_title_template: String,
    #[serde(default = "default_wiz_subtitle")]
    pub wiz_subtitle_template: String,
    #[serde(default = "default_true")]
    pub wiz_show_percent: bool,
    #[serde(default)]
    pub wiz_show_icon: bool,
    #[serde(default)]
    pub wiz_use_lamp_color: bool,
    #[serde(default = "default_wiz_bg_choice")]
    pub wiz_bg_color_choice: String,
    #[serde(default)]
    pub wiz_is_custom_bg_color: bool,
    #[serde(default = "default_wiz_bg_hex")]
    pub wiz_custom_bg_hex: String,
    #[serde(default = "default_wiz_bg_alpha")]
    pub wiz_bg_alpha: f64,
    #[serde(default = "default_wiz_bar_choice")]
    pub wiz_bar_color_choice: String,
    #[serde(default)]
    pub wiz_is_custom_bar_color: bool,
    #[serde(default = "default_wiz_bar_hex")]
    pub wiz_custom_bar_hex: String,
    #[serde(default = "default_wiz_bar_alpha")]
    pub wiz_bar_alpha: f64,
    #[serde(default = "default_accent_choice")]
    pub wiz_accent_color_choice: String,
    #[serde(default)]
    pub wiz_is_custom_accent_color: bool,
    #[serde(default = "default_blue_hex")]
    pub wiz_custom_accent_hex: String,

    // Dynamic expressions, visibility & interaction
    #[serde(default)]
    pub visible_condition: String,
    #[serde(default = "default_fade_ms")]
    pub fade_in_ms: u32,
    #[serde(default = "default_fade_ms")]
    pub fade_out_ms: u32,
    #[serde(default)]
    pub fade_delay_ms: u32,
    #[serde(default)]
    pub expressions: HashMap<String, String>,
    #[serde(default)]
    pub motion: HashMap<String, String>,
    #[serde(default)]
    pub hitbox: Option<HitboxConfig>,
    #[serde(default)]
    pub actions: Option<ActionsConfig>,

    // Runtime cached measurements (optional in JSON, ignored if absent)
    #[serde(default)]
    pub active_content_width: Option<f64>,
    #[serde(default)]
    pub active_content_height: Option<f64>,
}

fn default_true() -> bool {
    true
}
fn default_half() -> f64 {
    0.5
}
fn default_one() -> f64 {
    1.0
}
fn default_w() -> f64 {
    300.0
}
fn default_h() -> f64 {
    120.0
}
fn default_persp_dist() -> f64 {
    800.0
}
fn default_rect() -> String {
    "rectangle".to_string()
}
fn default_radius() -> f64 {
    16.0
}
fn default_sweep() -> f64 {
    360.0
}
fn default_poly_sides() -> i32 {
    3
}
fn default_star_points() -> i32 {
    5
}
fn default_star_ratio() -> f64 {
    0.382
}
fn default_font_family() -> String {
    "Roboto".to_string()
}
fn default_font_weight() -> i32 {
    400
}
fn default_font_size() -> f64 {
    110.0
}
fn default_center() -> String {
    "center".to_string()
}
fn default_none() -> String {
    "none".to_string()
}
fn default_gap() -> f64 {
    12.0
}
fn default_white_choice() -> String {
    "white".to_string()
}
fn default_white_hex() -> String {
    "#ffffff".to_string()
}
fn default_black_choice() -> String {
    "black".to_string()
}
fn default_black_hex() -> String {
    "#000000".to_string()
}
fn default_accent_choice() -> String {
    "primary".to_string()
}
fn default_blue_hex() -> String {
    "#A8C7FA".to_string()
}
fn default_linear() -> String {
    "linear".to_string()
}
fn default_auto() -> String {
    "auto".to_string()
}
fn default_stroke_w() -> f64 {
    2.0
}
fn default_shadow_y() -> f64 {
    6.0
}
fn default_shadow_blur() -> f64 {
    0.75
}
fn default_shadow_alpha() -> f64 {
    0.85
}
fn default_blur_max() -> f64 {
    48.0
}
fn default_glass_tint() -> f64 {
    0.6
}
fn default_bezel() -> f64 {
    12.0
}
fn default_thickness() -> f64 {
    24.0
}
fn default_ior() -> f64 {
    1.5
}
fn default_dispersion() -> f64 {
    0.06
}
fn default_frost() -> f64 {
    1.5
}
fn default_saturation() -> f64 {
    1.2
}
fn default_rim() -> f64 {
    0.3
}
fn default_light_angle() -> f64 {
    315.0
}
fn default_tint_amount() -> f64 {
    0.25
}
fn default_cutout() -> String {
    "cutout".to_string()
}
fn default_mask_model() -> String {
    "birefnet-general-lite".to_string()
}
fn default_choke() -> f64 {
    1.5
}
fn default_depth_mode() -> String {
    "limit_50".to_string()
}
fn default_depth_thresh() -> f64 {
    50.0
}
fn default_media_action() -> String {
    "play_pause".to_string()
}
fn default_wiz_style() -> String {
    "pill_slider".to_string()
}
fn default_wiz_title() -> String {
    "$WIZ_NAME".to_string()
}
fn default_wiz_subtitle() -> String {
    "Escritorio".to_string()
}
fn default_wiz_bg_choice() -> String {
    "surfaceContainer".to_string()
}
fn default_wiz_bg_hex() -> String {
    "#202924".to_string()
}
fn default_wiz_bg_alpha() -> f64 {
    0.94
}
fn default_wiz_bar_choice() -> String {
    "primaryContainer".to_string()
}
fn default_wiz_bar_hex() -> String {
    "#3B5249".to_string()
}
fn default_wiz_bar_alpha() -> f64 {
    0.96
}
fn default_fade_ms() -> u32 {
    150
}

impl Default for SceneObject {
    fn default() -> Self {
        Self {
            id: String::new(),
            obj_type: "clock".to_string(),
            name: String::new(),
            alias: String::new(),
            z_index: 1,
            visible: true,
            locked: false,
            group_id: None,
            group_offset_x: 0.0,
            group_offset_y: 0.0,
            group_width: 240.0,
            group_height: 120.0,
            children_ids: Vec::new(),
            collapsed: false,
            state: HashMap::new(),
            auto_layout: default_none(),
            auto_layout_gap: default_gap(),
            auto_layout_align: default_center(),
            pos_x_ratio: 0.5,
            pos_y_ratio: 0.5,
            base_width: 300.0,
            base_height: 120.0,
            scale_x: 1.0,
            scale_y: 1.0,
            uniform_scale: 1.0,
            rotation: 0.0,
            pivot_x: 0.5,
            pivot_y: 0.5,
            element_opacity: 1.0,
            perspective_x: 0.0,
            perspective_y: 0.0,
            perspective_distance: 800.0,
            parallax_follow: false,
            parallax_factor: 1.0,
            shape_type: default_rect(),
            radius_uniform: 16.0,
            radius_independent: false,
            radius_top_left: 16.0,
            radius_top_right: 16.0,
            radius_bottom_right: 16.0,
            radius_bottom_left: 16.0,
            circle_start: 0.0,
            circle_sweep: 360.0,
            circle_ratio: 0.0,
            polygon_sides: 3,
            polygon_radius: 0.0,
            star_points: 5,
            star_ratio: 0.382,
            star_radius: 0.0,
            font_choice: 1,
            use_system_font: true,
            custom_font_family: default_font_family(),
            font_source_id: None,
            font_file_name: None,
            font_weight: 400,
            font_italic: false,
            base_font_size: 110.0,
            letter_spacing: 0.0,
            text_align: default_center(),
            time_format: 0,
            date_format: 0,
            custom_content: String::new(),
            text_color_choice: default_white_choice(),
            is_custom_color: false,
            custom_hex: default_white_hex(),
            fill_alpha: 1.0,
            stroke_enabled: false,
            stroke_width: 2.0,
            stroke_color_choice: default_black_choice(),
            is_custom_stroke_color: false,
            custom_stroke_hex: default_black_hex(),
            gradient: None,
            stroke_gradient: None,
            tint_gradient: None,
            shadow_enabled: false,
            shadow_offset_x: 0.0,
            shadow_offset_y: 6.0,
            shadow_blur: 0.75,
            shadow_color_choice: default_black_choice(),
            is_custom_shadow_color: false,
            custom_shadow_hex: default_black_hex(),
            custom_shadow_alpha: 0.85,
            blur_enabled: false,
            blur_amount: 0.85,
            blur_max: 48.0,
            glass_tint_opacity: 0.6,
            liquid_glass_enabled: false,
            liquid_bezel: 12.0,
            liquid_thickness: 24.0,
            liquid_ior: 1.5,
            liquid_dispersion: 0.06,
            liquid_frost: 1.5,
            liquid_saturation: 1.2,
            liquid_rim: 0.3,
            liquid_light_angle: 315.0,
            liquid_tint_amount: 0.25,
            shader_id: None,
            shader_params: HashMap::new(),
            is_mask_group: false,
            is_mask: false,
            mask_target_id: None,
            mask_target_ids: Vec::new(),
            mask_mode: default_cutout(),
            mask_collapsed: false,
            mask_feather: 0.0,
            mask_model_name: default_mask_model(),
            invert_mask: false,
            mask_choke: 1.5,
            depth_cutout_mode: default_depth_mode(),
            depth_cutout_threshold: 50.0,
            svg_source: None,
            icon_name: String::new(),
            media_action: default_media_action(),
            hover_press_effects: true,
            hide_when_no_cover: false,
            hide_when_no_media: false,
            wiz_device_id: None,
            wiz_action: None,
            wiz_scene_id: None,
            wiz_style: default_wiz_style(),
            wiz_title_template: default_wiz_title(),
            wiz_subtitle_template: default_wiz_subtitle(),
            wiz_show_percent: true,
            wiz_show_icon: false,
            wiz_use_lamp_color: false,
            wiz_bg_color_choice: default_wiz_bg_choice(),
            wiz_is_custom_bg_color: false,
            wiz_custom_bg_hex: default_wiz_bg_hex(),
            wiz_bg_alpha: default_wiz_bg_alpha(),
            wiz_bar_color_choice: default_wiz_bar_choice(),
            wiz_is_custom_bar_color: false,
            wiz_custom_bar_hex: default_wiz_bar_hex(),
            wiz_bar_alpha: default_wiz_bar_alpha(),
            wiz_accent_color_choice: default_accent_choice(),
            wiz_is_custom_accent_color: false,
            wiz_custom_accent_hex: default_blue_hex(),
            visible_condition: String::new(),
            fade_in_ms: 150,
            fade_out_ms: 150,
            fade_delay_ms: 0,
            expressions: HashMap::new(),
            motion: HashMap::new(),
            hitbox: None,
            actions: None,
            active_content_width: None,
            active_content_height: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StandByPreset {
    #[serde(default = "default_schema_ver")]
    pub schema_version: u32,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub background: BackgroundConfig,
    #[serde(default)]
    pub state: HashMap<String, f64>,
    #[serde(default)]
    pub meta: PresetMeta,
    #[serde(default)]
    pub objects: Vec<SceneObject>,
}

fn default_schema_ver() -> u32 {
    CURRENT_SCHEMA_VERSION
}

impl Default for StandByPreset {
    fn default() -> Self {
        Self {
            schema_version: CURRENT_SCHEMA_VERSION,
            name: "Sin título".to_string(),
            description: String::new(),
            author: String::new(),
            background: BackgroundConfig::default(),
            state: HashMap::new(),
            meta: PresetMeta::default(),
            objects: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ValidationReport {
    pub valid: bool,
    pub sanitized_json: String,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    pub missing_fontsource_ids: Vec<String>,
    pub referenced_svgs: Vec<String>,
    pub needs_frame_tick: bool,
    pub needs_second_tick: bool,
}

const VALID_OBJECT_TYPES: &[&str] = &[
    "clock",
    "clock_hours",
    "clock_minutes",
    "clock_seconds",
    "clock_separator",
    "clock_ampm",
    "clock_h1",
    "clock_h2",
    "clock_m1",
    "clock_m2",
    "clock_s1",
    "clock_s2",
    "date",
    "datetime",
    "custom_text",
    "shape",
    "svg_icon",
    "icon_svg",
    "battery",
    "battery_icon",
    "battery_percent",
    "battery_bar",
    "weather",
    "weather_temp",
    "weather_icon",
    "media",
    "media_art",
    "media_cover",
    "media_title",
    "media_artist",
    "media_position",
    "media_duration",
    "media_prev",
    "media_play_pause",
    "media_next",
    "media_shuffle",
    "media_loop",
    "media_button",
    "media_progress",
    "wiz_button",
    "wiz_device",
    "smart_device",
    "group",
    "wallpaper_mask",
];

fn break_group_cycles(objects: &mut [SceneObject]) -> Vec<String> {
    let mut warnings = Vec::new();
    let parents: HashMap<String, Option<String>> = objects
        .iter()
        .map(|o| (o.id.clone(), o.group_id.clone()))
        .collect();

    for obj in objects.iter_mut() {
        let mut cur = obj.group_id.clone();
        let mut visited = HashSet::new();
        visited.insert(obj.id.clone());
        let mut hops = 0usize;
        let mut cyclic = false;
        while let Some(pid) = cur {
            if pid.is_empty() {
                break;
            }
            if !parents.contains_key(&pid) {
                warnings.push(format!("Objeto '{}': groupId huérfano '{}' limpiado", obj.id, pid));
                cyclic = true;
                break;
            }
            if !visited.insert(pid.clone()) || hops > 64 {
                warnings.push(format!("Objeto '{}': ciclo de grupos detectado, groupId limpiado", obj.id));
                cyclic = true;
                break;
            }
            cur = parents.get(&pid).and_then(|p| p.clone());
            hops += 1;
        }
        if cyclic {
            obj.group_id = None;
        }
    }
    warnings
}

pub fn sanitize_and_validate_preset(json_str: &str) -> ValidationReport {
    let mut report = ValidationReport::default();
    let mut preset: StandByPreset = match serde_json::from_str(json_str) {
        Ok(p) => p,
        Err(e) => {
            report.valid = false;
            report.errors.push(format!("JSON inválido: {e}"));
            return report;
        }
    };

    if preset.name.trim().is_empty() {
        preset.name = "Sin título".to_string();
        report.warnings.push("El preset no tenía nombre; asignado 'Sin título'".to_string());
    }
    if preset.schema_version == 0 {
        preset.schema_version = CURRENT_SCHEMA_VERSION;
    }

    // Remove objects without id
    let before = preset.objects.len();
    preset.objects.retain(|o| !o.id.trim().is_empty());
    if preset.objects.len() != before {
        report
            .warnings
            .push("Se descartaron objetos sin 'id' válido".to_string());
    }

    // Check unique IDs and valid types
    let mut seen_ids = HashSet::new();
    let mut fontsource_set = HashSet::new();
    let mut svg_set = HashSet::new();

    for obj in &mut preset.objects {
        if !seen_ids.insert(obj.id.clone()) {
            report
                .errors
                .push(format!("ID duplicado '{}'", obj.id));
        }
        if !VALID_OBJECT_TYPES.contains(&obj.obj_type.as_str()) {
            report
                .errors
                .push(format!("Tipo de objeto no soportado '{}' en '{}'", obj.obj_type, obj.id));
        }

        // Normalize legacy maskTargetId -> maskTargetIds
        if let Some(tid) = obj.mask_target_id.take() {
            if !tid.is_empty() && !obj.mask_target_ids.contains(&tid) {
                obj.mask_target_ids.push(tid);
            }
        }
        obj.mask_feather = obj.mask_feather.clamp(0.0, 150.0);
        obj.element_opacity = obj.element_opacity.clamp(0.0, 1.0);
        obj.fill_alpha = obj.fill_alpha.clamp(0.0, 1.0);

        if let Some(fid) = &obj.font_source_id {
            if !fid.trim().is_empty() {
                fontsource_set.insert(fid.trim().to_string());
            }
        }
        if let Some(svg) = &obj.svg_source {
            if let Some(asset) = svg.strip_prefix("asset:") {
                if !asset.trim().is_empty() {
                    svg_set.insert(asset.trim().to_string());
                }
            }
        }

        // Validate visibleCondition and expressions
        if !obj.visible_condition.trim().is_empty() {
            if let Err(err) = validate_expression(&obj.visible_condition) {
                report.errors.push(format!(
                    "Objeto '{}' visibleCondition inválida: {err}",
                    obj.id
                ));
            }
            let deps = crate::expr::ast::analyze_dependencies(&obj.visible_condition);
            report.needs_frame_tick |= deps.needs_frame_t;
            report.needs_second_tick |= deps.needs_seconds;
        }
        if obj.obj_type == "clock" && matches!(obj.time_format, 1 | 5) {
            report.needs_second_tick = true;
        }

        for (prop, expr) in &obj.expressions {
            if expr.trim().is_empty() {
                continue;
            }
            // Color expressions allow bare #rrggbb
            let normalized = if prop.to_ascii_lowercase().contains("color") {
                expr.replace('#', "\"#").replace("\"\"#", "\"#")
            } else {
                expr.clone()
            };
            if !prop.to_ascii_lowercase().contains("color") {
                if let Err(err) = validate_expression(&normalized) {
                    report.errors.push(format!(
                        "Objeto '{}' expresión inválida en '{}': {err}",
                        obj.id, prop
                    ));
                }
            }
            let deps = crate::expr::ast::analyze_dependencies(expr);
            report.needs_frame_tick |= deps.needs_frame_t;
            report.needs_second_tick |= deps.needs_seconds;
        }
    }

    let cycle_warnings = break_group_cycles(&mut preset.objects);
    report.warnings.extend(cycle_warnings);

    // Ensure childrenIds stay synchronized with groupId
    let mut children_map: HashMap<String, Vec<String>> = HashMap::new();
    for obj in &preset.objects {
        if let Some(gid) = &obj.group_id {
            children_map
                .entry(gid.clone())
                .or_default()
                .push(obj.id.clone());
        }
    }
    for obj in &mut preset.objects {
        if obj.obj_type == "group" {
            obj.children_ids = children_map.remove(&obj.id).unwrap_or_default();
        }
    }

    report.missing_fontsource_ids = fontsource_set.into_iter().collect();
    report.missing_fontsource_ids.sort();
    report.referenced_svgs = svg_set.into_iter().collect();
    report.referenced_svgs.sort();

    report.valid = report.errors.is_empty();
    report.sanitized_json = serde_json::to_string_pretty(&preset).unwrap_or_default();
    report
}

fn remap_pywal_color_choice(choice: &str) -> String {
    match choice.trim().to_ascii_lowercase().as_str() {
        "accent" | "color4" | "color12" => "primary".to_string(),
        "text" | "color7" | "color15" => "onSurface".to_string(),
        "color1" | "color9" => "error".to_string(),
        "color2" | "color10" => "tertiary".to_string(),
        "color3" | "color11" => "secondary".to_string(),
        "color5" | "color13" => "primaryContainer".to_string(),
        "color6" | "color14" => "tertiaryContainer".to_string(),
        "color8" => "outline".to_string(),
        other => other.to_string(),
    }
}

/// Imports a CripertoShell lockscreen preset JSON, stripping password/power objects
/// and desktop-only fields while preserving clocks, shapes, gradients, masks, Liquid Glass, and expressions.
pub fn import_cripertoshell_preset(raw_json: &str) -> Result<StandByPreset, String> {
    let root: Value =
        serde_json::from_str(raw_json).map_err(|e| format!("Error parseando JSON CripertoShell: {e}"))?;

    let name = root
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("Importado")
        .to_string();

    let state: HashMap<String, f64> = root
        .get("state")
        .and_then(|v| serde_json::from_value(v.clone()).ok())
        .unwrap_or_default();

    let meta: PresetMeta = root
        .get("meta")
        .and_then(|v| serde_json::from_value(v.clone()).ok())
        .unwrap_or_default();

    let raw_objects = root
        .get("objects")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "El JSON no contiene el arreglo 'objects'".to_string())?;

    let mut discarded_ids = HashSet::new();
    for item in raw_objects {
        let obj_type = item.get("type").and_then(|v| v.as_str()).unwrap_or("");
        let is_pw_group = item
            .get("isPasswordShapesGroup")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        if is_pw_group
            || obj_type.starts_with("password_")
            || obj_type.starts_with("power_")
            || matches!(
                obj_type,
                "auth_feedback"
            )
        {
            if let Some(id) = item.get("id").and_then(|v| v.as_str()) {
                discarded_ids.insert(id.to_string());
            }
        }
    }

    // Cascade-discard children whose parent group is exclusively a password/power group
    let mut changed = true;
    while changed {
        changed = false;
        for item in raw_objects {
            if let (Some(id), Some(gid)) = (
                item.get("id").and_then(|v| v.as_str()),
                item.get("groupId").and_then(|v| v.as_str()),
            ) {
                if discarded_ids.contains(gid) && discarded_ids.insert(id.to_string()) {
                    changed = true;
                }
            }
        }
    }

    let mut has_wallpaper_mask = false;
    let mut has_liquid_glass = false;
    let mut objects = Vec::new();

    for item in raw_objects {
        let id = item.get("id").and_then(|v| v.as_str()).unwrap_or("");
        if id.is_empty() || discarded_ids.contains(id) {
            continue;
        }
        let mut obj: SceneObject = match serde_json::from_value(item.clone()) {
            Ok(o) => o,
            Err(_) => continue,
        };
        if !VALID_OBJECT_TYPES.contains(&obj.obj_type.as_str()) {
            continue;
        }
        if obj.obj_type == "wallpaper_mask" {
            has_wallpaper_mask = true;
            if obj.mask_model_name == "birefnet-general" {
                obj.mask_model_name = "birefnet-general-lite".to_string();
            }
        }
        if obj.liquid_glass_enabled {
            has_liquid_glass = true;
            obj.shader_id = Some("liquid_glass".to_string());
        }
        obj.text_color_choice = remap_pywal_color_choice(&obj.text_color_choice);
        obj.stroke_color_choice = remap_pywal_color_choice(&obj.stroke_color_choice);
        obj.shadow_color_choice = remap_pywal_color_choice(&obj.shadow_color_choice);

        if let Some(grad) = &mut obj.gradient {
            for stop in &mut grad.stops {
                stop.color_choice = remap_pywal_color_choice(&stop.color_choice);
            }
        }
        if let Some(grad) = &mut obj.stroke_gradient {
            for stop in &mut grad.stops {
                stop.color_choice = remap_pywal_color_choice(&stop.color_choice);
            }
        }
        if let Some(grad) = &mut obj.tint_gradient {
            for stop in &mut grad.stops {
                stop.color_choice = remap_pywal_color_choice(&stop.color_choice);
            }
        }

        // Remove discarded IDs from maskTargetIds & childrenIds
        obj.mask_target_ids.retain(|tid| !discarded_ids.contains(tid));
        obj.children_ids.retain(|cid| !discarded_ids.contains(cid));
        if obj
            .group_id
            .as_deref()
            .map(|g| discarded_ids.contains(g))
            .unwrap_or(false)
        {
            obj.group_id = None;
        }

        objects.push(obj);
    }

    let bg_mode = if has_wallpaper_mask || has_liquid_glass {
        "wallpaper".to_string()
    } else {
        "black".to_string()
    };

    let mut preset = StandByPreset {
        schema_version: CURRENT_SCHEMA_VERSION,
        name,
        description: "Importado desde CripertoShell".to_string(),
        author: "Criperto".to_string(),
        background: BackgroundConfig {
            mode: bg_mode,
            ..BackgroundConfig::default()
        },
        state,
        meta,
        objects,
    };

    let report = sanitize_and_validate_preset(
        &serde_json::to_string(&preset).map_err(|e| e.to_string())?,
    );
    if let Ok(cleaned) = serde_json::from_str::<StandByPreset>(&report.sanitized_json) {
        preset = cleaned;
    }
    Ok(preset)
}

#[derive(Debug, Clone)]
pub struct BundleAsset {
    pub archive_path: String, // e.g. "fonts/inter.ttf", "svgs/lamp.svg", "wallpaper.jpg"
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct ExtractedBundle {
    pub preset_json: String,
    pub assets: Vec<BundleAsset>,
}

pub fn pack_standby_bundle(preset_json: &str, assets: &[BundleAsset]) -> Result<Vec<u8>, String> {
    // Validate JSON before packing
    let report = sanitize_and_validate_preset(preset_json);
    if !report.valid {
        return Err(format!("Preset inválido: {}", report.errors.join("; ")));
    }

    let mut buf = Cursor::new(Vec::new());
    {
        let mut zip = ZipWriter::new(&mut buf);
        let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
        zip.start_file("preset.json", opts)
            .map_err(|e| format!("Error creando preset.json en bundle: {e}"))?;
        zip.write_all(report.sanitized_json.as_bytes())
            .map_err(|e| e.to_string())?;

        for asset in assets {
            // Prevent path traversal inside zip archive
            let clean_path = asset
                .archive_path
                .trim_start_matches('/')
                .replace("..", "_");
            if clean_path.is_empty() || clean_path == "preset.json" {
                continue;
            }
            zip.start_file(&clean_path, opts)
                .map_err(|e| format!("Error empaquetando '{clean_path}': {e}"))?;
            zip.write_all(&asset.bytes).map_err(|e| e.to_string())?;
        }
        zip.finish().map_err(|e| e.to_string())?;
    }
    Ok(buf.into_inner())
}

pub fn unpack_standby_bundle(zip_bytes: &[u8]) -> Result<ExtractedBundle, String> {
    let reader = Cursor::new(zip_bytes);
    let mut archive = ZipArchive::new(reader).map_err(|e| format!("Archivo .standby corrupto: {e}"))?;

    let mut preset_json = None;
    let mut assets = Vec::new();

    for idx in 0..archive.len() {
        let mut file = archive.by_index(idx).map_err(|e| e.to_string())?;
        if file.is_dir() {
            continue;
        }
        let name = file.name().to_string();
        if name.contains("..") || name.starts_with('/') {
            continue;
        }
        // Cap individual file size at 64 MB
        if file.size() > 64 * 1024 * 1024 {
            return Err(format!("Archivo '{name}' excede el límite de 64 MB"));
        }
        let mut bytes = Vec::with_capacity(file.size() as usize);
        file.read_to_end(&mut bytes).map_err(|e| e.to_string())?;

        if name == "preset.json" {
            preset_json = Some(
                String::from_utf8(bytes)
                    .map_err(|_| "preset.json no es UTF-8 válido".to_string())?,
            );
        } else {
            assets.push(BundleAsset {
                archive_path: name,
                bytes,
            });
        }
    }

    let json = preset_json.ok_or_else(|| "El bundle .standby no contiene preset.json".to_string())?;
    Ok(ExtractedBundle {
        preset_json: json,
        assets,
    })
}
