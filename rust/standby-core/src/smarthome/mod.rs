pub mod wiz;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SmartDeviceState {
    pub is_on: bool,
    pub brightness: u8,         // 10..100
    pub r: Option<u8>,
    pub g: Option<u8>,
    pub b: Option<u8>,
    pub c: Option<u8>,
    pub w: Option<u8>,
    pub temp_kelvin: Option<u16>,
    pub scene_id: Option<u16>,
    pub speed: Option<u8>,
    pub rssi: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SmartDeviceInfo {
    pub id: String,
    pub driver: String,
    pub ip: String,
    pub port: u16,
    pub mac: String,
    pub name: String,
    pub module_name: String,
    pub room_id: Option<u32>,
    pub supports_rgb: bool,
    pub supports_temp: bool,
    pub state: SmartDeviceState,
}

/// Extensible smart-home protocol abstraction (`WizDriver`, future Matter/Hue/MQTT drivers).
pub trait SmartDeviceDriver {
    fn driver_id(&self) -> &'static str;
    fn discover(
        &self,
        subnet_prefix_ipv4: Option<&str>,
        timeout_ms: u32,
        sweep_subnet: bool,
    ) -> Result<Vec<SmartDeviceInfo>, String>;
    fn get_state(&self, ip: &str, port: u16) -> Result<SmartDeviceState, String>;
    fn set_power(&self, ip: &str, port: u16, on: bool) -> Result<(), String>;
    fn set_brightness(&self, ip: &str, port: u16, dimming: u8) -> Result<(), String>;
    fn set_rgb(&self, ip: &str, port: u16, r: u8, g: u8, b: u8, dimming: Option<u8>) -> Result<(), String>;
    fn set_temp(&self, ip: &str, port: u16, kelvin: u16, dimming: Option<u8>) -> Result<(), String>;
    fn set_scene(&self, ip: &str, port: u16, scene_id: u16, speed: Option<u8>, dimming: Option<u8>) -> Result<(), String>;
}
