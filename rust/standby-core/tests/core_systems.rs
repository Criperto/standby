use serde_json::json;
use standby_core::geometry::{
    align_selected_objects, anchor_to_visual_center, compute_snap, get_object_world_transform,
    recalculate_group_auto_layout, set_pivot_keep_visual_position, visual_center_to_anchor,
    AlignMode,
};
use standby_core::mask::{
    compute_mask_cache_key, compute_occlusion_ratio, evaluate_depth_limit, process_mask_rgba,
    MaskProcessOptions,
};
use standby_core::presets::{
    import_cripertoshell_preset, pack_standby_bundle, sanitize_and_validate_preset,
    unpack_standby_bundle, BundleAsset, SceneObject,
};
use standby_core::smarthome::wiz::{wiz_scene_catalog, WizDriver};
use standby_core::smarthome::SmartDeviceDriver;
use standby_core::triggers::{
    evaluate_standby_state, ChargeSource, ChargingRequirement, DateRange, DisplayMode,
    OrientationRequirement, PresenceMode, TimeWindow, TriggerRules, TriggerSnapshot,
};
use std::net::UdpSocket;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::thread;
use std::time::Duration;

#[test]
fn geometry_pivot_keep_visual_and_snapping() {
    let mut obj = SceneObject {
        id: "clock_1".into(),
        obj_type: "clock".into(),
        pos_x_ratio: 0.5,
        pos_y_ratio: 0.5,
        base_width: 400.0,
        base_height: 160.0,
        rotation: 30.0,
        pivot_x: 0.5,
        pivot_y: 0.5,
        active_content_width: Some(400.0),
        active_content_height: Some(160.0),
        ..Default::default()
    };

    let wt_before = get_object_world_transform(&obj, &[], 2400.0, 1080.0, true);
    set_pivot_keep_visual_position(&mut obj, &[], 0.0, 0.0, 2400.0, 1080.0);
    let wt_after = get_object_world_transform(&obj, &[], 2400.0, 1080.0, true);

    assert!((wt_before.visual_center_x - wt_after.visual_center_x).abs() < 0.5);
    assert!((wt_before.visual_center_y - wt_after.visual_center_y).abs() < 0.5);
    assert!((obj.pivot_x - 0.0).abs() < 1e-6);

    // Anchor <-> visual center inverse check
    let vc = anchor_to_visual_center(1200.0, 540.0, 0.25, 0.75, 300.0, 100.0, 45.0);
    let anc = visual_center_to_anchor(vc.x, vc.y, 0.25, 0.75, 300.0, 100.0, 45.0);
    assert!((anc.x - 1200.0).abs() < 1e-6);
    assert!((anc.y - 540.0).abs() < 1e-6);

    // Magnetic snap to center & equal spacing
    let left_box = SceneObject {
        id: "left_box".into(),
        obj_type: "shape".into(),
        pos_x_ratio: 0.25,
        pos_y_ratio: 0.5,
        base_width: 200.0,
        base_height: 100.0,
        ..Default::default()
    };
    let right_box = SceneObject {
        id: "right_box".into(),
        obj_type: "shape".into(),
        pos_x_ratio: 0.75,
        pos_y_ratio: 0.5,
        base_width: 200.0,
        base_height: 100.0,
        ..Default::default()
    };
    let moving = SceneObject {
        id: "moving".into(),
        obj_type: "shape".into(),
        pos_x_ratio: 0.502,
        pos_y_ratio: 0.503,
        base_width: 200.0,
        base_height: 100.0,
        ..Default::default()
    };

    let objs = vec![left_box.clone(), right_box.clone(), moving.clone()];
    let snap = compute_snap(
        1204.0,
        543.0,
        200.0,
        100.0,
        &["moving".into()],
        &objs,
        2400.0,
        1080.0,
        10.0,
        true,
    );
    assert!((snap.x - 1200.0).abs() < 1e-6);
    assert!((snap.y - 540.0).abs() < 1e-6);
    assert!(snap.snapped_center_x || !snap.guides.is_empty());

    // Distribute horizontally
    let mut dist_objs = vec![left_box, moving, right_box];
    align_selected_objects(
        AlignMode::DistributeH,
        &["left_box".into(), "moving".into(), "right_box".into()],
        &mut dist_objs,
        2400.0,
        1080.0,
    );
    assert!((dist_objs[1].pos_x_ratio - 0.5).abs() < 0.001);

    // Group row auto layout
    let grp = SceneObject {
        id: "g1".into(),
        obj_type: "group".into(),
        auto_layout: "row".into(),
        auto_layout_gap: 20.0,
        ..Default::default()
    };
    let c1 = SceneObject {
        id: "c1".into(),
        obj_type: "shape".into(),
        group_id: Some("g1".into()),
        base_width: 100.0,
        base_height: 40.0,
        z_index: 1,
        ..Default::default()
    };
    let c2 = SceneObject {
        id: "c2".into(),
        obj_type: "shape".into(),
        group_id: Some("g1".into()),
        base_width: 100.0,
        base_height: 40.0,
        z_index: 2,
        ..Default::default()
    };
    let mut layout_objs = vec![grp, c1, c2];
    recalculate_group_auto_layout("g1", &mut layout_objs);
    assert!((layout_objs[0].group_width - 220.0).abs() < 1e-6);
    assert!((layout_objs[1].group_offset_x - (-60.0)).abs() < 1e-6);
    assert!((layout_objs[2].group_offset_x - 60.0).abs() < 1e-6);
}

#[test]
fn preset_validation_criperto_import_and_bundle_roundtrip() {
    let criperto = json!({
        "name": "test_lock",
        "objects": [
            {
                "id": "clock_1",
                "type": "clock",
                "textColorChoice": "accent",
                "liquidGlassEnabled": true,
                "expressions": { "rotation": "spin(15)" }
            },
            {
                "id": "pw_1",
                "type": "password_input",
                "groupId": "grp_pw"
            },
            {
                "id": "grp_pw",
                "type": "group",
                "isPasswordShapesGroup": true
            },
            {
                "id": "pwr_1",
                "type": "power_button"
            }
        ]
    })
    .to_string();

    let migrated = import_cripertoshell_preset(&criperto).expect("import succeeds");
    assert_eq!(migrated.objects.len(), 1);
    assert_eq!(migrated.objects[0].id, "clock_1");
    assert_eq!(migrated.objects[0].text_color_choice, "primary");
    assert_eq!(migrated.objects[0].shader_id.as_deref(), Some("liquid_glass"));
    assert_eq!(migrated.background.mode, "wallpaper");

    let json_str = serde_json::to_string(&migrated).unwrap();
    let report = sanitize_and_validate_preset(&json_str);
    assert!(report.valid, "errors: {:?}", report.errors);
    assert!(report.needs_frame_tick);

    // Pack and unpack .standby zip bundle
    let packed = pack_standby_bundle(
        &json_str,
        &[BundleAsset {
            archive_path: "svgs/lamp.svg".into(),
            bytes: b"<svg></svg>".to_vec(),
        }],
    )
    .expect("pack bundle");
    let unpacked = unpack_standby_bundle(&packed).expect("unpack bundle");
    assert_eq!(unpacked.assets.len(), 1);
    assert_eq!(unpacked.assets[0].archive_path, "svgs/lamp.svg");
    assert_eq!(unpacked.assets[0].bytes, b"<svg></svg>");
}

#[test]
fn mask_processing_and_128x72_occlusion_grid() {
    let w = 128u32;
    let h = 72u32;
    let mut alpha = vec![0u8; (w * h) as usize];
    let mut rgba = vec![255u8; (w * h * 4) as usize];
    // Fill left half of image with solid foreground
    for y in 0..h {
        for x in 0..w {
            let idx = (y * w + x) as usize;
            if x < 64 {
                alpha[idx] = 255;
            }
            rgba[idx * 4] = 120;
            rgba[idx * 4 + 1] = 180;
            rgba[idx * 4 + 2] = 220;
            rgba[idx * 4 + 3] = 255;
        }
    }

    let key1 = compute_mask_cache_key("/wp.jpg:100:200", "birefnet-general-lite", false, 1.5);
    let key2 = compute_mask_cache_key("/wp.jpg:100:200", "birefnet-general-lite", true, 1.5);
    assert_ne!(key1, key2);

    let processed = process_mask_rgba(
        &alpha,
        &rgba,
        w,
        h,
        &MaskProcessOptions {
            invert: false,
            choke: 1.5,
            smooth_radius: 0.8,
            is_low_res_model: false,
        },
    )
    .expect("mask processing");

    assert_eq!(processed.occupancy_grid.len(), 288);
    let left_occ = compute_occlusion_ratio(&processed.occupancy_grid, 0.05, 0.2, 0.35, 0.8);
    let right_occ = compute_occlusion_ratio(&processed.occupancy_grid, 0.65, 0.2, 0.95, 0.8);
    assert!(left_occ > 0.9, "left_occ={left_occ}");
    assert!(right_occ < 0.05, "right_occ={right_occ}");

    assert!(evaluate_depth_limit(0.60, 50.0, false));
    assert!(evaluate_depth_limit(0.496, 50.0, true)); // inside ±0.005 hysteresis
    assert!(!evaluate_depth_limit(0.40, 50.0, true));
}

#[test]
fn trigger_rules_and_presence_blackout() {
    let mut rules = TriggerRules {
        enabled: true,
        charging_req: ChargingRequirement::AnyCharging,
        orientation_req: OrientationRequirement::LandscapeOnly,
        time_windows: vec![TimeWindow {
            start_minute_of_day: 22 * 60,
            end_minute_of_day: 8 * 60,
        }],
        weekdays_mask: 0b0111_1111,
        date_ranges: vec![DateRange {
            start_yyyymmdd: 20261225,
            end_yyyymmdd: 20261225,
            is_exclusion: true,
        }],
        presence_mode: PresenceMode::FaceOrLight,
        dark_threshold_lux: 10.0,
        lux_hysteresis: 3.0,
        sleep_delay_sec: 15,
        night_blackout_enabled: true,
        standby_brightness: 0.3,
        night_brightness: 0.06,
        ..Default::default()
    };

    // Not charging -> inactive
    let mut snap = TriggerSnapshot {
        is_charging: false,
        charge_source: ChargeSource::None,
        is_landscape: true,
        local_minute_of_day: 23 * 60,
        local_yyyymmdd: 20261118,
        ambient_lux: Some(50.0),
        ..Default::default()
    };
    let d1 = evaluate_standby_state(&rules, &snap);
    assert!(!d1.should_be_active);

    // Charging + landscape + midnight window + room lit -> active, camera polling OFF
    snap.is_charging = true;
    snap.charge_source = ChargeSource::Ac;
    let d2 = evaluate_standby_state(&rules, &snap);
    assert!(d2.should_be_active);
    assert_eq!(d2.display_mode, DisplayMode::NightActive);
    assert!(!d2.should_poll_camera);
    assert!(d2.extra_dim_alpha > 0.0);

    // Room dark + no face past sleep_delay -> AmoledBlackout, camera polling ON
    snap.ambient_lux = Some(2.0);
    snap.face_detected = Some(false);
    snap.now_epoch_ms = 100_000;
    snap.last_presence_epoch_ms = 50_000;
    let d3 = evaluate_standby_state(&rules, &snap);
    assert!(d3.should_be_active);
    assert_eq!(d3.display_mode, DisplayMode::AmoledBlackout);
    assert!(d3.should_poll_camera);

    // Excluded date -> inactive
    snap.local_yyyymmdd = 20261225;
    let d4 = evaluate_standby_state(&rules, &snap);
    assert!(!d4.should_be_active);

    // Hub open keeps screen awake even in dark
    snap.local_yyyymmdd = 20261118;
    snap.hub_open = true;
    rules.hub_brightness = 0.6;
    let d5 = evaluate_standby_state(&rules, &snap);
    assert_eq!(d5.display_mode, DisplayMode::NightActive);
    assert!((d5.target_brightness - 0.6).abs() < 0.01);

    // Camera restart while previously in blackout: grace window must NOT wake the screen
    snap.hub_open = false;
    snap.face_detected = None;
    snap.now_epoch_ms = 100_000;
    snap.last_presence_epoch_ms = 99_000; // looks like "recent presence" (fresh PresenceState)
    snap.prev_blackout = false;
    let d6 = evaluate_standby_state(&rules, &snap);
    assert_eq!(d6.display_mode, DisplayMode::NightActive); // old behaviour: grace wakes it
    snap.prev_blackout = true;
    let d7 = evaluate_standby_state(&rules, &snap);
    assert_eq!(d7.display_mode, DisplayMode::AmoledBlackout);
    assert!(d7.should_poll_camera);

    // A positive face verdict wakes it from blackout
    snap.face_detected = Some(true);
    let d8 = evaluate_standby_state(&rules, &snap);
    assert_eq!(d8.display_mode, DisplayMode::NightActive);

    // Light returning also wakes it
    snap.face_detected = Some(false);
    snap.ambient_lux = Some(80.0);
    let d9 = evaluate_standby_state(&rules, &snap);
    assert_ne!(d9.display_mode, DisplayMode::AmoledBlackout);
}

#[test]
fn wiz_udp_mock_roundtrip_and_catalog() {
    assert_eq!(wiz_scene_catalog().len(), 32);

    let mock_sock = UdpSocket::bind("127.0.0.1:0").expect("bind mock udp");
    let port = mock_sock.local_addr().unwrap().port();
    mock_sock
        .set_read_timeout(Some(Duration::from_millis(100)))
        .unwrap();

    let stop = Arc::new(AtomicBool::new(false));
    let stop_clone = stop.clone();
    let captured_cmds = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
    let cmds_clone = captured_cmds.clone();

    let srv = thread::spawn(move || {
        let mut buf = [0u8; 2048];
        let mut state_on = true;
        let mut r = 255u64;
        let mut g = 128u64;
        let mut b = 0u64;
        let mut dim = 80u64;

        while !stop_clone.load(Ordering::Relaxed) {
            if let Ok((len, src)) = mock_sock.recv_from(&mut buf) {
                let txt = String::from_utf8_lossy(&buf[..len]).to_string();
                let val: serde_json::Value = serde_json::from_str(&txt).unwrap_or_default();
                let method = val["method"].as_str().unwrap_or("");
                cmds_clone.lock().unwrap().push(method.to_string());
                match method {
                    "getPilot" => {
                        let resp = json!({
                            "method": "getPilot",
                            "result": {
                                "mac": "a8bb50123456",
                                "rssi": -48,
                                "state": state_on,
                                "r": r, "g": g, "b": b,
                                "dimming": dim
                            }
                        });
                        let _ = mock_sock.send_to(resp.to_string().as_bytes(), src);
                    }
                    "getSystemConfig" => {
                        let resp = json!({
                            "method": "getSystemConfig",
                            "result": {
                                "mac": "a8bb50123456",
                                "moduleName": "ESP01_SHRGB1C_31",
                                "roomId": 7
                            }
                        });
                        let _ = mock_sock.send_to(resp.to_string().as_bytes(), src);
                    }
                    "setPilot" => {
                        let p = &val["params"];
                        if let Some(s) = p.get("state").and_then(|v| v.as_bool()) {
                            state_on = s;
                        }
                        if let Some(rv) = p.get("r").and_then(|v| v.as_u64()) {
                            r = rv;
                        }
                        if let Some(gv) = p.get("g").and_then(|v| v.as_u64()) {
                            g = gv;
                        }
                        if let Some(bv) = p.get("b").and_then(|v| v.as_u64()) {
                            b = bv;
                        }
                        if let Some(dv) = p.get("dimming").and_then(|v| v.as_u64()) {
                            dim = dv;
                        }
                    }
                    _ => {}
                }
            }
        }
    });

    let drv = WizDriver;
    let info = drv.probe_device("127.0.0.1", port, 500).expect("probe mock bulb");
    assert_eq!(info.mac, "a8bb50123456");
    assert!(info.supports_rgb);
    assert_eq!(info.room_id, Some(7));

    drv.set_rgb("127.0.0.1", port, 12, 34, 200, Some(65))
        .expect("set_rgb");
    thread::sleep(Duration::from_millis(40));
    let st = drv.get_state("127.0.0.1", port).expect("get_state");
    assert_eq!((st.r, st.g, st.b, st.brightness), (Some(12), Some(34), Some(200), 65));

    drv.set_power("127.0.0.1", port, false).expect("set_power");
    thread::sleep(Duration::from_millis(40));
    let st2 = drv.get_state("127.0.0.1", port).expect("get_state");
    assert!(!st2.is_on);

    stop.store(true, Ordering::Relaxed);
    let _ = srv.join();
}
