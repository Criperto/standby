use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChargingRequirement {
    AnyCharging,
    AcOnly,
    UsbOnly,
    WirelessOnly,
    AlwaysIgnoreCharging,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChargeSource {
    None,
    Ac,
    Usb,
    Wireless,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OrientationRequirement {
    LandscapeOnly,
    AnyOrientation,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PresenceMode {
    Disabled,
    LightOnly,
    FaceOnly,
    FaceOrLight,
    FaceAndLight,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DisplayMode {
    Inactive,
    Active,
    NightActive,
    AmoledBlackout,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TimeWindow {
    pub start_minute_of_day: u16, // 0..1439
    pub end_minute_of_day: u16,   // 0..1439
}

impl TimeWindow {
    pub fn contains(&self, minute: u16) -> bool {
        if self.start_minute_of_day == self.end_minute_of_day {
            return true;
        }
        if self.start_minute_of_day < self.end_minute_of_day {
            minute >= self.start_minute_of_day && minute < self.end_minute_of_day
        } else {
            // Crosses midnight (e.g. 22:00 -> 07:00)
            minute >= self.start_minute_of_day || minute < self.end_minute_of_day
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DateRange {
    pub start_yyyymmdd: u32,
    pub end_yyyymmdd: u32,
    pub is_exclusion: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggerRules {
    pub enabled: bool,
    pub charging_req: ChargingRequirement,
    pub orientation_req: OrientationRequirement,
    pub time_windows: Vec<TimeWindow>,
    /// Bitmask: bit 0 = Monday .. bit 6 = Sunday (0x7F = every day)
    pub weekdays_mask: u8,
    pub date_ranges: Vec<DateRange>,
    pub night_window: Option<TimeWindow>,
    pub use_system_brightness: bool,
    pub standby_brightness: f32, // 0.01..1.0
    pub night_brightness: f32,   // 0.01..1.0
    pub hub_brightness: f32,     // 0.05..1.0
    pub presence_mode: PresenceMode,
    pub dark_threshold_lux: f32,
    pub lux_hysteresis: f32,
    pub sleep_delay_sec: u32,
    pub camera_poll_interval_sec: u32,
    pub night_blackout_enabled: bool,
}

impl Default for TriggerRules {
    fn default() -> Self {
        Self {
            enabled: true,
            charging_req: ChargingRequirement::AnyCharging,
            orientation_req: OrientationRequirement::LandscapeOnly,
            time_windows: Vec::new(),
            weekdays_mask: 0x7F,
            date_ranges: Vec::new(),
            night_window: Some(TimeWindow {
                start_minute_of_day: 23 * 60,
                end_minute_of_day: 7 * 60,
            }),
            use_system_brightness: false,
            standby_brightness: 0.25,
            night_brightness: 0.05,
            hub_brightness: 0.55,
            presence_mode: PresenceMode::Disabled,
            dark_threshold_lux: 8.0,
            lux_hysteresis: 3.0,
            sleep_delay_sec: 20,
            camera_poll_interval_sec: 1,
            night_blackout_enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggerSnapshot {
    pub is_charging: bool,
    pub charge_source: ChargeSource,
    pub is_landscape: bool,
    pub now_epoch_ms: u64,
    pub local_minute_of_day: u16,
    pub local_weekday_mon0: u8, // 0=Mon .. 6=Sun
    pub local_yyyymmdd: u32,
    pub ambient_lux: Option<f32>,
    pub face_detected: Option<bool>,
    pub last_presence_epoch_ms: u64,
    pub hub_open: bool,
    pub manual_launch: bool,
    pub prev_dark_room: bool,
    /// True when the previous decision was AMOLED blackout. While true, the screen must NOT wake
    /// up from the presence grace period (e.g. after a camera restart); a fresh positive signal
    /// (light or a detected face) is required first.
    #[serde(default)]
    pub prev_blackout: bool,
}

impl Default for TriggerSnapshot {
    fn default() -> Self {
        Self {
            is_charging: false,
            charge_source: ChargeSource::None,
            is_landscape: true,
            now_epoch_ms: 0,
            local_minute_of_day: 12 * 60,
            local_weekday_mon0: 0,
            local_yyyymmdd: 20261118,
            ambient_lux: None,
            face_detected: None,
            last_presence_epoch_ms: 0,
            hub_open: false,
            manual_launch: false,
            prev_dark_room: false,
            prev_blackout: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StandByDecision {
    pub should_be_active: bool,
    pub display_mode: DisplayMode,
    pub should_poll_camera: bool,
    pub target_brightness: f32, // -1.0 means use system brightness
    pub extra_dim_alpha: f32,   // 0.0..0.92 extra darkening overlay on OLED
    pub is_dark_room: bool,
    pub reason: String,
}

pub fn evaluate_standby_state(rules: &TriggerRules, snap: &TriggerSnapshot) -> StandByDecision {
    let dark_room = match snap.ambient_lux {
        Some(lux) => {
            if snap.prev_dark_room {
                lux < (rules.dark_threshold_lux + rules.lux_hysteresis)
            } else {
                lux < rules.dark_threshold_lux
            }
        }
        None => snap.prev_dark_room,
    };

    if !snap.manual_launch {
        if !rules.enabled {
            return StandByDecision {
                should_be_active: false,
                display_mode: DisplayMode::Inactive,
                should_poll_camera: false,
                target_brightness: -1.0,
                extra_dim_alpha: 0.0,
                is_dark_room: dark_room,
                reason: "Reglas desactivadas".into(),
            };
        }

        // 1. Power check
        let power_ok = match rules.charging_req {
            ChargingRequirement::AlwaysIgnoreCharging => true,
            ChargingRequirement::AnyCharging => snap.is_charging,
            ChargingRequirement::AcOnly => {
                snap.is_charging && snap.charge_source == ChargeSource::Ac
            }
            ChargingRequirement::UsbOnly => {
                snap.is_charging && snap.charge_source == ChargeSource::Usb
            }
            ChargingRequirement::WirelessOnly => {
                snap.is_charging && snap.charge_source == ChargeSource::Wireless
            }
        };
        if !power_ok {
            return StandByDecision {
                should_be_active: false,
                display_mode: DisplayMode::Inactive,
                should_poll_camera: false,
                target_brightness: -1.0,
                extra_dim_alpha: 0.0,
                is_dark_room: dark_room,
                reason: "Sin alimentación requerida".into(),
            };
        }

        // 2. Orientation check
        if rules.orientation_req == OrientationRequirement::LandscapeOnly && !snap.is_landscape {
            return StandByDecision {
                should_be_active: false,
                display_mode: DisplayMode::Inactive,
                should_poll_camera: false,
                target_brightness: -1.0,
                extra_dim_alpha: 0.0,
                is_dark_room: dark_room,
                reason: "Teléfono en vertical".into(),
            };
        }

        // 3. Weekday check
        let day_bit = 1u8 << (snap.local_weekday_mon0.min(6));
        if (rules.weekdays_mask & day_bit) == 0 {
            return StandByDecision {
                should_be_active: false,
                display_mode: DisplayMode::Inactive,
                should_poll_camera: false,
                target_brightness: -1.0,
                extra_dim_alpha: 0.0,
                is_dark_room: dark_room,
                reason: "Día de semana excluido".into(),
            };
        }

        // 4. Date ranges (exclusions & inclusions)
        for dr in &rules.date_ranges {
            if dr.is_exclusion
                && snap.local_yyyymmdd >= dr.start_yyyymmdd
                && snap.local_yyyymmdd <= dr.end_yyyymmdd
            {
                return StandByDecision {
                    should_be_active: false,
                    display_mode: DisplayMode::Inactive,
                    should_poll_camera: false,
                    target_brightness: -1.0,
                    extra_dim_alpha: 0.0,
                    is_dark_room: dark_room,
                    reason: "Fecha en rango de exclusión".into(),
                };
            }
        }
        let inclusions: Vec<&DateRange> = rules
            .date_ranges
            .iter()
            .filter(|d| !d.is_exclusion)
            .collect();
        if !inclusions.is_empty()
            && !inclusions.iter().any(|d| {
                snap.local_yyyymmdd >= d.start_yyyymmdd && snap.local_yyyymmdd <= d.end_yyyymmdd
            })
        {
            return StandByDecision {
                should_be_active: false,
                display_mode: DisplayMode::Inactive,
                should_poll_camera: false,
                target_brightness: -1.0,
                extra_dim_alpha: 0.0,
                is_dark_room: dark_room,
                reason: "Fuera de rango de fechas activo".into(),
            };
        }

        // 5. Time windows
        if !rules.time_windows.is_empty()
            && !rules
                .time_windows
                .iter()
                .any(|w| w.contains(snap.local_minute_of_day))
        {
            return StandByDecision {
                should_be_active: false,
                display_mode: DisplayMode::Inactive,
                should_poll_camera: false,
                target_brightness: -1.0,
                extra_dim_alpha: 0.0,
                is_dark_room: dark_room,
                reason: "Fuera de ventana horaria".into(),
            };
        }
    }

    // Night window evaluation
    let is_night_window = rules
        .night_window
        .as_ref()
        .map(|w| w.contains(snap.local_minute_of_day))
        .unwrap_or(false);

    // Presence & camera polling logic
    let should_poll_camera = match rules.presence_mode {
        PresenceMode::Disabled | PresenceMode::LightOnly => false,
        PresenceMode::FaceOnly | PresenceMode::FaceAndLight => true,
        PresenceMode::FaceOrLight => dark_room, // Smart battery/camera optimization: only use camera when dark!
    };

    let elapsed_no_presence_ms = snap
        .now_epoch_ms
        .saturating_sub(snap.last_presence_epoch_ms);
    let sleep_grace_ms = (rules.sleep_delay_sec as u64) * 1000;
    let face_present_now = snap.face_detected.unwrap_or(false);
    // While in blackout, the presence grace window is ignored: only a fresh positive signal wakes the screen.
    let grace_active = !snap.prev_blackout && elapsed_no_presence_ms <= sleep_grace_ms;

    let presence_ok = if snap.hub_open {
        true
    } else {
        match rules.presence_mode {
            PresenceMode::Disabled => true,
            PresenceMode::LightOnly => !dark_room,
            PresenceMode::FaceOnly => face_present_now || grace_active,
            PresenceMode::FaceOrLight => !dark_room || face_present_now || grace_active,
            PresenceMode::FaceAndLight => !dark_room && (face_present_now || grace_active),
        }
    };

    if !presence_ok && rules.night_blackout_enabled {
        return StandByDecision {
            should_be_active: true,
            display_mode: DisplayMode::AmoledBlackout,
            should_poll_camera,
            target_brightness: if rules.use_system_brightness {
                -1.0
            } else {
                0.01
            },
            extra_dim_alpha: 1.0,
            is_dark_room: dark_room,
            reason: "Modo reposo AMOLED (sin presencia / habitación oscura)".into(),
        };
    }

    let raw_brightness = if snap.hub_open {
        rules.hub_brightness
    } else if is_night_window || dark_room {
        rules.night_brightness
    } else {
        rules.standby_brightness
    }
    .clamp(0.01, 1.0);

    let (target_brightness, extra_dim_alpha) = if rules.use_system_brightness {
        (-1.0, 0.0)
    } else if raw_brightness < 0.12 {
        // Below ~12% Android panel backlight bottoms out; blend extra black scrim for true OLED dimness
        let extra = ((0.12 - raw_brightness) / 0.12) * 0.80;
        (0.01_f32.max(raw_brightness), extra.clamp(0.0, 0.85))
    } else {
        (raw_brightness, 0.0)
    };

    let display_mode = if is_night_window || dark_room {
        DisplayMode::NightActive
    } else {
        DisplayMode::Active
    };

    StandByDecision {
        should_be_active: true,
        display_mode,
        should_poll_camera,
        target_brightness,
        extra_dim_alpha,
        is_dark_room: dark_room,
        reason: if snap.hub_open {
            "Hub domótico abierto".into()
        } else if display_mode == DisplayMode::NightActive {
            "StandBy nocturno activo".into()
        } else {
            "StandBy activo".into()
        },
    }
}
