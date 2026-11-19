use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

use crate::presets::SceneObject;

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Point2D {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldTransform {
    pub anchor_x: f64,
    pub anchor_y: f64,
    pub visual_center_x: f64,
    pub visual_center_y: f64,
    pub rotation_deg: f64,
    pub scale_x: f64,
    pub scale_y: f64,
    pub width: f64,
    pub height: f64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ObjectBounds {
    pub cx: f64,
    pub cy: f64,
    pub w: f64,
    pub h: f64,
    pub left: f64,
    pub right: f64,
    pub top: f64,
    pub bottom: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapGuide {
    pub axis: String, // "v" | "h"
    pub pos: f64,
    pub min: f64,
    pub max: f64,
    pub is_equal_spacing: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpacingLabel {
    pub axis: String, // "h" | "v"
    pub start: f64,
    pub length: f64,
    pub cross_pos: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SnapResult {
    pub x: f64,
    pub y: f64,
    pub snapped_center_x: bool,
    pub snapped_center_y: bool,
    pub snapped_third_left: bool,
    pub snapped_third_right: bool,
    pub snapped_third_top: bool,
    pub snapped_third_bottom: bool,
    pub guides: Vec<SnapGuide>,
    pub spacings: Vec<SpacingLabel>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScreenAlignAnchor {
    TopLeft,
    TopCenter,
    TopRight,
    MiddleLeft,
    Center,
    MiddleRight,
    BottomLeft,
    BottomCenter,
    BottomRight,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AlignMode {
    Left,
    CenterH,
    Right,
    Top,
    CenterV,
    Bottom,
    DistributeH,
    DistributeV,
}

pub fn anchor_to_visual_center(
    anchor_x: f64,
    anchor_y: f64,
    pivot_x: f64,
    pivot_y: f64,
    width: f64,
    height: f64,
    rot_deg: f64,
) -> Point2D {
    let rad = rot_deg.to_radians();
    let ux = (0.5 - pivot_x) * width;
    let uy = (0.5 - pivot_y) * height;
    let rx = ux * rad.cos() - uy * rad.sin();
    let ry = ux * rad.sin() + uy * rad.cos();
    Point2D {
        x: anchor_x + rx,
        y: anchor_y + ry,
    }
}

pub fn visual_center_to_anchor(
    center_x: f64,
    center_y: f64,
    pivot_x: f64,
    pivot_y: f64,
    width: f64,
    height: f64,
    rot_deg: f64,
) -> Point2D {
    let rad = rot_deg.to_radians();
    let ux = (0.5 - pivot_x) * width;
    let uy = (0.5 - pivot_y) * height;
    let rx = ux * rad.cos() - uy * rad.sin();
    let ry = ux * rad.sin() + uy * rad.cos();
    Point2D {
        x: center_x - rx,
        y: center_y - ry,
    }
}

pub fn pivot_offset(
    pivot_x: f64,
    pivot_y: f64,
    width: f64,
    height: f64,
    rot_deg: f64,
) -> Point2D {
    let rad = rot_deg.to_radians();
    let ux = (pivot_x - 0.5) * width;
    let uy = (pivot_y - 0.5) * height;
    Point2D {
        x: ux * rad.cos() - uy * rad.sin(),
        y: ux * rad.sin() + uy * rad.cos(),
    }
}

pub fn effective_object_dimensions(obj: &SceneObject) -> (f64, f64) {
    let sx = obj.scale_x * obj.uniform_scale;
    let sy = obj.scale_y * obj.uniform_scale;
    if obj.obj_type == "group" {
        let gw = if obj.group_width > 0.0 {
            obj.group_width
        } else {
            200.0
        };
        let gh = if obj.group_height > 0.0 {
            obj.group_height
        } else {
            200.0
        };
        return ((gw * sx).max(20.0), (gh * sy).max(20.0));
    }
    if obj.obj_type == "clock" {
        let fs = obj.base_font_size * sy;
        let cw = obj
            .active_content_width
            .filter(|&v| v > 0.0)
            .unwrap_or_else(|| (fs * 2.8).round());
        let ch = obj
            .active_content_height
            .filter(|&v| v > 0.0)
            .unwrap_or_else(|| (fs * 1.05).round());
        return ((cw * obj.scale_x).max(24.0), ch.max(24.0));
    }
    let bw = obj
        .active_content_width
        .filter(|&v| v > 0.0)
        .unwrap_or(if obj.base_width > 0.0 {
            obj.base_width
        } else {
            240.0
        });
    let bh = obj
        .active_content_height
        .filter(|&v| v > 0.0)
        .unwrap_or(if obj.base_height > 0.0 {
            obj.base_height
        } else {
            100.0
        });
    ((bw * sx).max(16.0), (bh * sy).max(16.0))
}

fn round_if_static(val: f64, is_animating: bool) -> f64 {
    if is_animating {
        val
    } else {
        val.round()
    }
}

pub fn get_group_world_pos(
    grp: &SceneObject,
    by_id: &HashMap<&str, &SceneObject>,
    screen_w: f64,
    screen_h: f64,
    is_animating: bool,
    depth: usize,
) -> (f64, f64, f64, f64, f64) {
    let sw = if screen_w > 0.0 { screen_w } else { 2400.0 };
    let sh = if screen_h > 0.0 { screen_h } else { 1080.0 };
    if depth < 64 {
        if let Some(pid) = grp.group_id.as_deref().filter(|s| !s.is_empty()) {
            if let Some(parent) = by_id.get(pid) {
                let (px, py, prot, psx, psy) =
                    get_group_world_pos(parent, by_id, sw, sh, is_animating, depth + 1);
                let off_x = grp.group_offset_x * psx;
                let off_y = grp.group_offset_y * psy;
                let rad = prot.to_radians();
                let rx = off_x * rad.cos() - off_y * rad.sin();
                let ry = off_x * rad.sin() + off_y * rad.cos();
                let wx = px + round_if_static(rx, is_animating);
                let wy = py + round_if_static(ry, is_animating);
                let my_rot = prot + grp.rotation;
                let my_sx = psx * grp.scale_x * grp.uniform_scale;
                let my_sy = psy * grp.scale_y * grp.uniform_scale;
                return (wx, wy, my_rot, my_sx, my_sy);
            }
        }
    }
    let gx = round_if_static(grp.pos_x_ratio * sw, is_animating);
    let gy = round_if_static(grp.pos_y_ratio * sh, is_animating);
    let sx = grp.scale_x * grp.uniform_scale;
    let sy = grp.scale_y * grp.uniform_scale;
    (gx, gy, grp.rotation, sx, sy)
}

pub fn get_object_world_transform(
    obj: &SceneObject,
    objects: &[SceneObject],
    screen_w: f64,
    screen_h: f64,
    is_animating: bool,
) -> WorldTransform {
    let sw = if screen_w > 0.0 { screen_w } else { 2400.0 };
    let sh = if screen_h > 0.0 { screen_h } else { 1080.0 };
    let by_id: HashMap<&str, &SceneObject> =
        objects.iter().map(|o| (o.id.as_str(), o)).collect();

    let (base_w, base_h) = effective_object_dimensions(obj);

    if obj.obj_type == "group" {
        let (gx, gy, grot, gsx, gsy) =
            get_group_world_pos(obj, &by_id, sw, sh, is_animating, 0);
        let gw = if obj.group_width > 0.0 {
            obj.group_width
        } else {
            200.0
        } * gsx;
        let gh = if obj.group_height > 0.0 {
            obj.group_height
        } else {
            200.0
        } * gsy;
        let vc = anchor_to_visual_center(gx, gy, obj.pivot_x, obj.pivot_y, gw, gh, grot);
        return WorldTransform {
            anchor_x: gx,
            anchor_y: gy,
            visual_center_x: vc.x,
            visual_center_y: vc.y,
            rotation_deg: grot,
            scale_x: gsx,
            scale_y: gsy,
            width: gw,
            height: gh,
        };
    }

    if let Some(gid) = obj.group_id.as_deref().filter(|s| !s.is_empty()) {
        if let Some(grp) = by_id.get(gid) {
            let (gx, gy, grot, gsx, gsy) =
                get_group_world_pos(grp, &by_id, sw, sh, is_animating, 0);
            let off_x = obj.group_offset_x * gsx;
            let off_y = obj.group_offset_y * gsy;
            let rad = grot.to_radians();
            let rx = off_x * rad.cos() - off_y * rad.sin();
            let ry = off_x * rad.sin() + off_y * rad.cos();
            let ax = gx + round_if_static(rx, is_animating);
            let ay = gy + round_if_static(ry, is_animating);
            let rot = grot + obj.rotation;
            let w = base_w * gsx;
            let h = base_h * gsy;
            let vc = anchor_to_visual_center(ax, ay, obj.pivot_x, obj.pivot_y, w, h, rot);
            return WorldTransform {
                anchor_x: ax,
                anchor_y: ay,
                visual_center_x: vc.x,
                visual_center_y: vc.y,
                rotation_deg: rot,
                scale_x: gsx,
                scale_y: gsy,
                width: w,
                height: h,
            };
        }
    }

    let ax = round_if_static(obj.pos_x_ratio * sw, is_animating);
    let ay = round_if_static(obj.pos_y_ratio * sh, is_animating);
    let vc = anchor_to_visual_center(
        ax,
        ay,
        obj.pivot_x,
        obj.pivot_y,
        base_w,
        base_h,
        obj.rotation,
    );
    WorldTransform {
        anchor_x: ax,
        anchor_y: ay,
        visual_center_x: vc.x,
        visual_center_y: vc.y,
        rotation_deg: obj.rotation,
        scale_x: 1.0,
        scale_y: 1.0,
        width: base_w,
        height: base_h,
    }
}

/// Updates an object's pivot without shifting its visual position on screen.
pub fn set_pivot_keep_visual_position(
    obj: &mut SceneObject,
    objects: &[SceneObject],
    new_px: f64,
    new_py: f64,
    screen_w: f64,
    screen_h: f64,
) {
    let sw = if screen_w > 0.0 { screen_w } else { 2400.0 };
    let sh = if screen_h > 0.0 { screen_h } else { 1080.0 };

    let wt = get_object_world_transform(obj, objects, sw, sh, true);
    let old_px = obj.pivot_x;
    let old_py = obj.pivot_y;
    let du_x = (new_px - old_px) * wt.width;
    let du_y = (new_py - old_py) * wt.height;
    let rad = wt.rotation_deg.to_radians();
    let dw_x = du_x * rad.cos() - du_y * rad.sin();
    let dw_y = du_x * rad.sin() + du_y * rad.cos();

    let new_ax = wt.anchor_x + dw_x;
    let new_ay = wt.anchor_y + dw_y;

    obj.pos_x_ratio = ((new_ax / sw) * 10000.0).round() / 10000.0;
    obj.pos_y_ratio = ((new_ay / sh) * 10000.0).round() / 10000.0;

    if let Some(gid) = obj.group_id.as_deref().filter(|s| !s.is_empty()) {
        let by_id: HashMap<&str, &SceneObject> =
            objects.iter().map(|o| (o.id.as_str(), o)).collect();
        if let Some(grp) = by_id.get(gid) {
            let (_, _, grot, gsx, gsy) = get_group_world_pos(grp, &by_id, sw, sh, true, 0);
            let grad = grot.to_radians();
            let unrot_dx = dw_x * grad.cos() + dw_y * grad.sin();
            let unrot_dy = -dw_x * grad.sin() + dw_y * grad.cos();
            let sx_safe = if gsx.abs() > 1e-4 { gsx } else { 1.0 };
            let sy_safe = if gsy.abs() > 1e-4 { gsy } else { 1.0 };
            obj.group_offset_x += unrot_dx / sx_safe;
            obj.group_offset_y += unrot_dy / sy_safe;
        }
    }

    obj.pivot_x = new_px;
    obj.pivot_y = new_py;
}

pub fn get_object_bounds(
    obj: &SceneObject,
    objects: &[SceneObject],
    screen_w: f64,
    screen_h: f64,
) -> ObjectBounds {
    let wt = get_object_world_transform(obj, objects, screen_w, screen_h, false);
    let cx = wt.visual_center_x;
    let cy = wt.visual_center_y;
    let w = wt.width;
    let h = wt.height;
    ObjectBounds {
        cx,
        cy,
        w,
        h,
        left: cx - w * 0.5,
        right: cx + w * 0.5,
        top: cy - h * 0.5,
        bottom: cy + h * 0.5,
    }
}

#[derive(Debug, Clone)]
struct SnapCand {
    snap_pos: f64,
    delta: f64,
    distance: f64,
    guide: SnapGuide,
    is_canvas: bool,
    is_parent: bool,
    canvas_id: &'static str,
}

fn score_cand(c: &SnapCand, diag_max: f64) -> f64 {
    let penalty = if c.distance > diag_max && !c.is_canvas && !c.is_parent {
        (c.distance - diag_max) * 0.05
    } else {
        0.0
    };
    c.delta + penalty
}

#[allow(clippy::too_many_arguments)]
pub fn compute_snap(
    raw_cx: f64,
    raw_cy: f64,
    my_w: f64,
    my_h: f64,
    selected_ids: &[String],
    objects: &[SceneObject],
    screen_w: f64,
    screen_h: f64,
    threshold: f64,
    snap_enabled: bool,
) -> SnapResult {
    let sw = if screen_w > 0.0 { screen_w } else { 2400.0 };
    let sh = if screen_h > 0.0 { screen_h } else { 1080.0 };

    if !snap_enabled {
        return SnapResult {
            x: raw_cx,
            y: raw_cy,
            ..Default::default()
        };
    }

    let thresh = threshold.max(1.0);
    let diag_max = sw.hypot(sh) * 0.4;
    let mut x_cands: Vec<SnapCand> = Vec::new();
    let mut y_cands: Vec<SnapCand> = Vec::new();

    // 1. Canvas Guides (Center & Thirds)
    let cx = sw * 0.5;
    let cy = sh * 0.5;
    let d_cx = (raw_cx - cx).abs();
    if d_cx <= thresh {
        x_cands.push(SnapCand {
            snap_pos: cx,
            delta: d_cx,
            distance: 0.0,
            guide: SnapGuide {
                axis: "v".into(),
                pos: cx,
                min: 0.0,
                max: sh,
                is_equal_spacing: false,
            },
            is_canvas: true,
            is_parent: false,
            canvas_id: "centerX",
        });
    }
    let d_cy = (raw_cy - cy).abs();
    if d_cy <= thresh {
        y_cands.push(SnapCand {
            snap_pos: cy,
            delta: d_cy,
            distance: 0.0,
            guide: SnapGuide {
                axis: "h".into(),
                pos: cy,
                min: 0.0,
                max: sw,
                is_equal_spacing: false,
            },
            is_canvas: true,
            is_parent: false,
            canvas_id: "centerY",
        });
    }

    for (pos, cid) in [(sw / 3.0, "thirdLeft"), ((sw * 2.0) / 3.0, "thirdRight")] {
        let d = (raw_cx - pos).abs();
        if d <= thresh {
            x_cands.push(SnapCand {
                snap_pos: pos,
                delta: d,
                distance: 0.0,
                guide: SnapGuide {
                    axis: "v".into(),
                    pos,
                    min: 0.0,
                    max: sh,
                    is_equal_spacing: false,
                },
                is_canvas: true,
                is_parent: false,
                canvas_id: cid,
            });
        }
    }
    for (pos, cid) in [(sh / 3.0, "thirdTop"), ((sh * 2.0) / 3.0, "thirdBottom")] {
        let d = (raw_cy - pos).abs();
        if d <= thresh {
            y_cands.push(SnapCand {
                snap_pos: pos,
                delta: d,
                distance: 0.0,
                guide: SnapGuide {
                    axis: "h".into(),
                    pos,
                    min: 0.0,
                    max: sw,
                    is_equal_spacing: false,
                },
                is_canvas: true,
                is_parent: false,
                canvas_id: cid,
            });
        }
    }

    let my_left = raw_cx - my_w * 0.5;
    let my_right = raw_cx + my_w * 0.5;
    let my_top = raw_cy - my_h * 0.5;
    let my_bottom = raw_cy + my_h * 0.5;
    let sel_set: HashSet<&str> = selected_ids.iter().map(|s| s.as_str()).collect();

    // 2a. Parent group snap
    if let Some(first_id) = selected_ids.first() {
        if let Some(sel_obj) = objects.iter().find(|o| &o.id == first_id) {
            if let Some(gid) = sel_obj.group_id.as_deref() {
                if let Some(p_grp) = objects.iter().find(|o| o.id == gid) {
                    let pb = get_object_bounds(p_grp, objects, sw, sh);
                    if (raw_cx - pb.cx).abs() <= thresh {
                        x_cands.push(SnapCand {
                            snap_pos: pb.cx,
                            delta: (raw_cx - pb.cx).abs(),
                            distance: 0.0,
                            guide: SnapGuide {
                                axis: "v".into(),
                                pos: pb.cx,
                                min: pb.top,
                                max: pb.bottom,
                                is_equal_spacing: false,
                            },
                            is_canvas: false,
                            is_parent: true,
                            canvas_id: "",
                        });
                    }
                    if (raw_cy - pb.cy).abs() <= thresh {
                        y_cands.push(SnapCand {
                            snap_pos: pb.cy,
                            delta: (raw_cy - pb.cy).abs(),
                            distance: 0.0,
                            guide: SnapGuide {
                                axis: "h".into(),
                                pos: pb.cy,
                                min: pb.left,
                                max: pb.right,
                                is_equal_spacing: false,
                            },
                            is_canvas: false,
                            is_parent: true,
                            canvas_id: "",
                        });
                    }
                    for (from, to, snap) in [
                        (my_left, pb.left, pb.left + my_w * 0.5),
                        (my_right, pb.right, pb.right - my_w * 0.5),
                    ] {
                        let d = (from - to).abs();
                        if d <= thresh {
                            x_cands.push(SnapCand {
                                snap_pos: snap,
                                delta: d,
                                distance: 0.0,
                                guide: SnapGuide {
                                    axis: "v".into(),
                                    pos: to,
                                    min: pb.top,
                                    max: pb.bottom,
                                    is_equal_spacing: false,
                                },
                                is_canvas: false,
                                is_parent: true,
                                canvas_id: "",
                            });
                        }
                    }
                    for (from, to, snap) in [
                        (my_top, pb.top, pb.top + my_h * 0.5),
                        (my_bottom, pb.bottom, pb.bottom - my_h * 0.5),
                    ] {
                        let d = (from - to).abs();
                        if d <= thresh {
                            y_cands.push(SnapCand {
                                snap_pos: snap,
                                delta: d,
                                distance: 0.0,
                                guide: SnapGuide {
                                    axis: "h".into(),
                                    pos: to,
                                    min: pb.left,
                                    max: pb.right,
                                    is_equal_spacing: false,
                                },
                                is_canvas: false,
                                is_parent: true,
                                canvas_id: "",
                            });
                        }
                    }
                }
            }
        }
    }

    // 2b. Object-to-object snapping + 3. Equal spacing
    let mut horiz_neighbors = Vec::new();
    let mut vert_neighbors = Vec::new();

    for other in objects {
        if sel_set.contains(other.id.as_str()) {
            continue;
        }
        if other
            .group_id
            .as_deref()
            .map(|g| sel_set.contains(g))
            .unwrap_or(false)
        {
            continue;
        }
        if other.obj_type == "wallpaper_mask" || !other.visible {
            continue;
        }

        let ob = get_object_bounds(other, objects, sw, sh);
        let dist = (raw_cx - ob.cx).hypot(raw_cy - ob.cy);

        for (from, to, snap, g_min, g_max) in [
            (
                raw_cx,
                ob.cx,
                ob.cx,
                raw_cy.min(ob.cy),
                raw_cy.max(ob.cy),
            ),
            (
                my_left,
                ob.left,
                ob.left + my_w * 0.5,
                my_top.min(ob.top),
                my_bottom.max(ob.bottom),
            ),
            (
                my_right,
                ob.right,
                ob.right - my_w * 0.5,
                my_top.min(ob.top),
                my_bottom.max(ob.bottom),
            ),
            (
                my_left,
                ob.right,
                ob.right + my_w * 0.5,
                my_top.min(ob.top),
                my_bottom.max(ob.bottom),
            ),
            (
                my_right,
                ob.left,
                ob.left - my_w * 0.5,
                my_top.min(ob.top),
                my_bottom.max(ob.bottom),
            ),
        ] {
            let d = (from - to).abs();
            if d <= thresh {
                x_cands.push(SnapCand {
                    snap_pos: snap,
                    delta: d,
                    distance: dist,
                    guide: SnapGuide {
                        axis: "v".into(),
                        pos: to,
                        min: g_min,
                        max: g_max,
                        is_equal_spacing: false,
                    },
                    is_canvas: false,
                    is_parent: false,
                    canvas_id: "",
                });
            }
        }

        for (from, to, snap, g_min, g_max) in [
            (
                raw_cy,
                ob.cy,
                ob.cy,
                raw_cx.min(ob.cx),
                raw_cx.max(ob.cx),
            ),
            (
                my_top,
                ob.top,
                ob.top + my_h * 0.5,
                my_left.min(ob.left),
                my_right.max(ob.right),
            ),
            (
                my_bottom,
                ob.bottom,
                ob.bottom - my_h * 0.5,
                my_left.min(ob.left),
                my_right.max(ob.right),
            ),
            (
                my_top,
                ob.bottom,
                ob.bottom + my_h * 0.5,
                my_left.min(ob.left),
                my_right.max(ob.right),
            ),
            (
                my_bottom,
                ob.top,
                ob.top - my_h * 0.5,
                my_left.min(ob.left),
                my_right.max(ob.right),
            ),
        ] {
            let d = (from - to).abs();
            if d <= thresh {
                y_cands.push(SnapCand {
                    snap_pos: snap,
                    delta: d,
                    distance: dist,
                    guide: SnapGuide {
                        axis: "h".into(),
                        pos: to,
                        min: g_min,
                        max: g_max,
                        is_equal_spacing: false,
                    },
                    is_canvas: false,
                    is_parent: false,
                    canvas_id: "",
                });
            }
        }

        if ob.bottom > my_top && ob.top < my_bottom {
            horiz_neighbors.push(ob);
        }
        if ob.right > my_left && ob.left < my_right {
            vert_neighbors.push(ob);
        }
    }

    horiz_neighbors.sort_by(|a, b| a.cx.total_cmp(&b.cx));
    for win in horiz_neighbors.windows(2) {
        let left = win[0];
        let right = win[1];
        if left.right < raw_cx && right.left > raw_cx {
            let even_x = (left.right + right.left) * 0.5;
            let eq_delta = (raw_cx - even_x).abs();
            if eq_delta <= thresh * 1.5 {
                let eq_dist = (raw_cx - left.cx)
                    .hypot(raw_cy - left.cy)
                    .min((raw_cx - right.cx).hypot(raw_cy - right.cy));
                x_cands.push(SnapCand {
                    snap_pos: even_x,
                    delta: eq_delta * 0.5,
                    distance: eq_dist,
                    guide: SnapGuide {
                        axis: "v".into(),
                        pos: even_x,
                        min: my_top.min(left.top).min(right.top),
                        max: my_bottom.max(left.bottom).max(right.bottom),
                        is_equal_spacing: true,
                    },
                    is_canvas: false,
                    is_parent: false,
                    canvas_id: "",
                });
            }
        }
    }

    vert_neighbors.sort_by(|a, b| a.cy.total_cmp(&b.cy));
    for win in vert_neighbors.windows(2) {
        let top = win[0];
        let bot = win[1];
        if top.bottom < raw_cy && bot.top > raw_cy {
            let even_y = (top.bottom + bot.top) * 0.5;
            let eq_delta = (raw_cy - even_y).abs();
            if eq_delta <= thresh * 1.5 {
                let eq_dist = (raw_cx - top.cx)
                    .hypot(raw_cy - top.cy)
                    .min((raw_cx - bot.cx).hypot(raw_cy - bot.cy));
                y_cands.push(SnapCand {
                    snap_pos: even_y,
                    delta: eq_delta * 0.5,
                    distance: eq_dist,
                    guide: SnapGuide {
                        axis: "h".into(),
                        pos: even_y,
                        min: my_left.min(top.left).min(bot.left),
                        max: my_right.max(top.right).max(bot.right),
                        is_equal_spacing: true,
                    },
                    is_canvas: false,
                    is_parent: false,
                    canvas_id: "",
                });
            }
        }
    }

    let cmp_cand = |a: &SnapCand, b: &SnapCand| {
        let sa = score_cand(a, diag_max);
        let sb = score_cand(b, diag_max);
        sa.total_cmp(&sb)
            .then_with(|| a.distance.total_cmp(&b.distance))
    };
    x_cands.sort_by(cmp_cand);
    y_cands.sort_by(cmp_cand);

    let mut res = SnapResult {
        x: raw_cx,
        y: raw_cy,
        ..Default::default()
    };

    if let Some(best_x) = x_cands.first() {
        res.x = best_x.snap_pos;
        res.guides.push(best_x.guide.clone());
        match best_x.canvas_id {
            "centerX" => res.snapped_center_x = true,
            "thirdLeft" => res.snapped_third_left = true,
            "thirdRight" => res.snapped_third_right = true,
            _ => {}
        }
    }
    if let Some(best_y) = y_cands.first() {
        res.y = best_y.snap_pos;
        res.guides.push(best_y.guide.clone());
        match best_y.canvas_id {
            "centerY" => res.snapped_center_y = true,
            "thirdTop" => res.snapped_third_top = true,
            "thirdBottom" => res.snapped_third_bottom = true,
            _ => {}
        }
    }

    res.spacings = compute_drag_spacing(
        res.x,
        res.y,
        my_w,
        my_h,
        &sel_set,
        objects,
        sw,
        sh,
    );
    res
}

#[allow(clippy::too_many_arguments)]
fn compute_drag_spacing(
    cx: f64,
    cy: f64,
    my_w: f64,
    my_h: f64,
    sel_set: &HashSet<&str>,
    objects: &[SceneObject],
    sw: f64,
    sh: f64,
) -> Vec<SpacingLabel> {
    let my_l = cx - my_w * 0.5;
    let my_r = cx + my_w * 0.5;
    let my_t = cy - my_h * 0.5;
    let my_b = cy + my_h * 0.5;

    let mut near_left: Option<(f64, f64)> = None;
    let mut near_right: Option<(f64, f64)> = None;
    let mut near_top: Option<(f64, f64)> = None;
    let mut near_bottom: Option<(f64, f64)> = None;

    for other in objects {
        if sel_set.contains(other.id.as_str())
            || other.obj_type == "wallpaper_mask"
            || !other.visible
        {
            continue;
        }
        let ob = get_object_bounds(other, objects, sw, sh);
        if ob.bottom > my_t && ob.top < my_b {
            if ob.right <= my_l {
                let gap = my_l - ob.right;
                if near_left.map(|(g, _)| gap < g).unwrap_or(true) {
                    near_left = Some((gap, ob.right));
                }
            }
            if ob.left >= my_r {
                let gap = ob.left - my_r;
                if near_right.map(|(g, _)| gap < g).unwrap_or(true) {
                    near_right = Some((gap, ob.left));
                }
            }
        }
        if ob.right > my_l && ob.left < my_r {
            if ob.bottom <= my_t {
                let gap = my_t - ob.bottom;
                if near_top.map(|(g, _)| gap < g).unwrap_or(true) {
                    near_top = Some((gap, ob.bottom));
                }
            }
            if ob.top >= my_b {
                let gap = ob.top - my_b;
                if near_bottom.map(|(g, _)| gap < g).unwrap_or(true) {
                    near_bottom = Some((gap, ob.top));
                }
            }
        }
    }

    let max_gap = sw.max(sh) * 0.5;
    let mut out = Vec::new();
    if let Some((gap, edge)) = near_left {
        if gap > 0.0 && gap < max_gap {
            out.push(SpacingLabel {
                axis: "h".into(),
                start: edge,
                length: gap,
                cross_pos: cy,
            });
        }
    }
    if let Some((gap, _)) = near_right {
        if gap > 0.0 && gap < max_gap {
            out.push(SpacingLabel {
                axis: "h".into(),
                start: my_r,
                length: gap,
                cross_pos: cy,
            });
        }
    }
    if let Some((gap, edge)) = near_top {
        if gap > 0.0 && gap < max_gap {
            out.push(SpacingLabel {
                axis: "v".into(),
                start: edge,
                length: gap,
                cross_pos: cx,
            });
        }
    }
    if let Some((gap, _)) = near_bottom {
        if gap > 0.0 && gap < max_gap {
            out.push(SpacingLabel {
                axis: "v".into(),
                start: my_b,
                length: gap,
                cross_pos: cx,
            });
        }
    }
    out
}

pub fn align_object_to_screen(
    obj: &mut SceneObject,
    objects: &[SceneObject],
    anchor: ScreenAlignAnchor,
    screen_w: f64,
    screen_h: f64,
) {
    let sw = if screen_w > 0.0 { screen_w } else { 2400.0 };
    let sh = if screen_h > 0.0 { screen_h } else { 1080.0 };
    let wt = get_object_world_transform(obj, objects, sw, sh, true);
    let margin_x = sw * 0.06;
    let margin_top = sh * 0.08;
    let margin_bot = sh * 0.10;

    let target_cx = match anchor {
        ScreenAlignAnchor::TopLeft
        | ScreenAlignAnchor::MiddleLeft
        | ScreenAlignAnchor::BottomLeft => margin_x + wt.width * 0.5,
        ScreenAlignAnchor::TopCenter | ScreenAlignAnchor::Center | ScreenAlignAnchor::BottomCenter => {
            sw * 0.5
        }
        ScreenAlignAnchor::TopRight
        | ScreenAlignAnchor::MiddleRight
        | ScreenAlignAnchor::BottomRight => sw - margin_x - wt.width * 0.5,
    };

    let target_cy = match anchor {
        ScreenAlignAnchor::TopLeft | ScreenAlignAnchor::TopCenter | ScreenAlignAnchor::TopRight => {
            margin_top + wt.height * 0.5
        }
        ScreenAlignAnchor::MiddleLeft | ScreenAlignAnchor::Center | ScreenAlignAnchor::MiddleRight => {
            sh * 0.5
        }
        ScreenAlignAnchor::BottomLeft
        | ScreenAlignAnchor::BottomCenter
        | ScreenAlignAnchor::BottomRight => sh - margin_bot - wt.height * 0.5,
    };

    let anc = visual_center_to_anchor(
        target_cx,
        target_cy,
        obj.pivot_x,
        obj.pivot_y,
        wt.width,
        wt.height,
        wt.rotation_deg,
    );
    obj.pos_x_ratio = ((anc.x / sw) * 10000.0).round() / 10000.0;
    obj.pos_y_ratio = ((anc.y / sh) * 10000.0).round() / 10000.0;
}

pub fn align_selected_objects(
    mode: AlignMode,
    selected_ids: &[String],
    objects: &mut [SceneObject],
    screen_w: f64,
    screen_h: f64,
) {
    let sw = if screen_w > 0.0 { screen_w } else { 2400.0 };
    let sh = if screen_h > 0.0 { screen_h } else { 1080.0 };
    let sel_set: HashSet<String> = selected_ids.iter().cloned().collect();
    if sel_set.is_empty() {
        return;
    }

    let snapshot = objects.to_vec();
    let mut items: Vec<(usize, ObjectBounds, f64, f64, f64)> = Vec::new();

    for (idx, obj) in snapshot.iter().enumerate() {
        if sel_set.contains(&obj.id) && obj.obj_type != "wallpaper_mask" {
            let b = get_object_bounds(obj, &snapshot, sw, sh);
            let wt = get_object_world_transform(obj, &snapshot, sw, sh, true);
            items.push((idx, b, wt.rotation_deg, obj.pivot_x, obj.pivot_y));
        }
    }
    if items.is_empty() {
        return;
    }

    if items.len() == 1 {
        let (idx, b, rot, px, py) = items[0];
        let margin = 24.0;
        let mut cx = b.cx;
        let mut cy = b.cy;
        match mode {
            AlignMode::Left => cx = margin + b.w * 0.5,
            AlignMode::CenterH => cx = sw * 0.5,
            AlignMode::Right => cx = sw - margin - b.w * 0.5,
            AlignMode::Top => cy = margin + b.h * 0.5,
            AlignMode::CenterV => cy = sh * 0.5,
            AlignMode::Bottom => cy = sh - margin - b.h * 0.5,
            AlignMode::DistributeH | AlignMode::DistributeV => return,
        }
        let anc = visual_center_to_anchor(cx, cy, px, py, b.w, b.h, rot);
        objects[idx].pos_x_ratio = ((anc.x / sw) * 10000.0).round() / 10000.0;
        objects[idx].pos_y_ratio = ((anc.y / sh) * 10000.0).round() / 10000.0;
        return;
    }

    let min_l = items.iter().map(|i| i.1.left).fold(f64::INFINITY, f64::min);
    let max_r = items
        .iter()
        .map(|i| i.1.right)
        .fold(f64::NEG_INFINITY, f64::max);
    let min_t = items.iter().map(|i| i.1.top).fold(f64::INFINITY, f64::min);
    let max_b = items
        .iter()
        .map(|i| i.1.bottom)
        .fold(f64::NEG_INFINITY, f64::max);
    let mid_x = (min_l + max_r) * 0.5;
    let mid_y = (min_t + max_b) * 0.5;

    match mode {
        AlignMode::DistributeH if items.len() >= 3 => {
            items.sort_by(|a, b| a.1.cx.total_cmp(&b.1.cx));
            let total_w: f64 = items.iter().map(|i| i.1.w).sum();
            let gap = ((max_r - min_l) - total_w) / ((items.len() - 1) as f64);
            let mut cursor = min_l;
            for (idx, b, rot, px, py) in items {
                let cx = cursor + b.w * 0.5;
                cursor += b.w + gap;
                let anc = visual_center_to_anchor(cx, b.cy, px, py, b.w, b.h, rot);
                objects[idx].pos_x_ratio = ((anc.x / sw) * 10000.0).round() / 10000.0;
            }
        }
        AlignMode::DistributeV if items.len() >= 3 => {
            items.sort_by(|a, b| a.1.cy.total_cmp(&b.1.cy));
            let total_h: f64 = items.iter().map(|i| i.1.h).sum();
            let gap = ((max_b - min_t) - total_h) / ((items.len() - 1) as f64);
            let mut cursor = min_t;
            for (idx, b, rot, px, py) in items {
                let cy = cursor + b.h * 0.5;
                cursor += b.h + gap;
                let anc = visual_center_to_anchor(b.cx, cy, px, py, b.w, b.h, rot);
                objects[idx].pos_y_ratio = ((anc.y / sh) * 10000.0).round() / 10000.0;
            }
        }
        _ => {
            for (idx, b, rot, px, py) in items {
                let mut cx = b.cx;
                let mut cy = b.cy;
                match mode {
                    AlignMode::Left => cx = min_l + b.w * 0.5,
                    AlignMode::CenterH => cx = mid_x,
                    AlignMode::Right => cx = max_r - b.w * 0.5,
                    AlignMode::Top => cy = min_t + b.h * 0.5,
                    AlignMode::CenterV => cy = mid_y,
                    AlignMode::Bottom => cy = max_b - b.h * 0.5,
                    AlignMode::DistributeH | AlignMode::DistributeV => {}
                }
                let anc = visual_center_to_anchor(cx, cy, px, py, b.w, b.h, rot);
                objects[idx].pos_x_ratio = ((anc.x / sw) * 10000.0).round() / 10000.0;
                objects[idx].pos_y_ratio = ((anc.y / sh) * 10000.0).round() / 10000.0;
            }
        }
    }
}

/// Applies Figma-style row/column auto-layout for a parent group when `auto_layout != "none"`.
pub fn recalculate_group_auto_layout(group_id: &str, objects: &mut [SceneObject]) {
    let Some(grp) = objects.iter().find(|o| o.id == group_id).cloned() else {
        return;
    };
    if grp.auto_layout != "row" && grp.auto_layout != "column" {
        return;
    }

    let mut child_indices: Vec<usize> = objects
        .iter()
        .enumerate()
        .filter(|(_, o)| o.group_id.as_deref() == Some(group_id) && o.visible)
        .map(|(i, _)| i)
        .collect();
    if child_indices.is_empty() {
        return;
    }
    child_indices.sort_by_key(|&i| objects[i].z_index);

    let gap = grp.auto_layout_gap.max(0.0);
    let dims: Vec<(usize, f64, f64)> = child_indices
        .into_iter()
        .map(|i| {
            let (w, h) = effective_object_dimensions(&objects[i]);
            (i, w, h)
        })
        .collect();

    if grp.auto_layout == "row" {
        let total_w: f64 =
            dims.iter().map(|d| d.1).sum::<f64>() + gap * ((dims.len().saturating_sub(1)) as f64);
        let max_h = dims.iter().map(|d| d.2).fold(0.0_f64, f64::max);
        let mut cur_x = -total_w * 0.5;
        for (i, w, h) in dims {
            objects[i].group_offset_x = cur_x + w * 0.5;
            objects[i].group_offset_y = match grp.auto_layout_align.as_str() {
                "start" => -max_h * 0.5 + h * 0.5,
                "end" => max_h * 0.5 - h * 0.5,
                _ => 0.0,
            };
            cur_x += w + gap;
        }
        if let Some(g) = objects.iter_mut().find(|o| o.id == group_id) {
            g.group_width = total_w;
            g.group_height = max_h;
        }
    } else {
        let total_h: f64 =
            dims.iter().map(|d| d.2).sum::<f64>() + gap * ((dims.len().saturating_sub(1)) as f64);
        let max_w = dims.iter().map(|d| d.1).fold(0.0_f64, f64::max);
        let mut cur_y = -total_h * 0.5;
        for (i, w, h) in dims {
            objects[i].group_offset_y = cur_y + h * 0.5;
            objects[i].group_offset_x = match grp.auto_layout_align.as_str() {
                "start" => -max_w * 0.5 + w * 0.5,
                "end" => max_w * 0.5 - w * 0.5,
                _ => 0.0,
            };
            cur_y += h + gap;
        }
        if let Some(g) = objects.iter_mut().find(|o| o.id == group_id) {
            g.group_width = max_w;
            g.group_height = total_h;
        }
    }
}
