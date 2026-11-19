use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use super::{SmartDeviceDriver, SmartDeviceInfo, SmartDeviceState};

pub const WIZ_PORT: u16 = 38899;
pub const WIZ_PUSH_PORT: u16 = 38900;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WizSceneInfo {
    pub id: u16,
    pub name_es: String,
    pub category: String,
}

pub fn wiz_scene_catalog() -> Vec<WizSceneInfo> {
    let entries: &[(u16, &str, &str)] = &[
        (1, "Océano", "Dinámica"),
        (2, "Romance", "Ambiente"),
        (3, "Atardecer", "Ambiente"),
        (4, "Fiesta", "Dinámica"),
        (5, "Chimenea", "Acogedora"),
        (6, "Acogedor", "Blanco"),
        (7, "Bosque", "Dinámica"),
        (8, "Colores pastel", "Color"),
        (9, "Despertar", "Ritmo"),
        (10, "Hora de dormir", "Ritmo"),
        (11, "Luz cálida", "Blanco"),
        (12, "Luz diurna", "Blanco"),
        (13, "Luz fría", "Blanco"),
        (14, "Luz nocturna", "Blanco"),
        (15, "Concentración", "Funcional"),
        (16, "Relajación", "Ambiente"),
        (17, "Colores reales", "Funcional"),
        (18, "TV", "Ambiente"),
        (19, "Pulsación", "Dinámica"),
        (20, "Navidad", "Festiva"),
        (21, "Halloween", "Festiva"),
        (22, "Luz de vela", "Acogedora"),
        (23, "Blanco dorado", "Blanco"),
        (24, "Pulso", "Dinámica"),
        (25, "Steampunk", "Dinámica"),
        (26, "Diwali", "Festiva"),
        (27, "Blanco", "Blanco"),
        (28, "Alarma", "Dinámica"),
        (29, "Luz de estudio", "Funcional"),
        (30, "Ciberpunk", "Color"),
        (31, "Mojito", "Color"),
        (32, "Club", "Dinámica"),
    ];
    entries
        .iter()
        .map(|&(id, name, cat)| WizSceneInfo {
            id,
            name_es: name.to_string(),
            category: cat.to_string(),
        })
        .collect()
}

pub struct WizDriver;

impl WizDriver {
    fn send_udp_request(ip: &str, port: u16, payload: &Value, timeout_ms: u64) -> Result<Value, String> {
        let addr: SocketAddr = format!("{ip}:{port}")
            .parse()
            .map_err(|e| format!("IP/puerto inválido ({ip}:{port}): {e}"))?;

        let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| format!("Error abriendo UDP: {e}"))?;
        socket
            .set_read_timeout(Some(Duration::from_millis(timeout_ms)))
            .map_err(|e| e.to_string())?;
        socket
            .set_write_timeout(Some(Duration::from_millis(timeout_ms)))
            .map_err(|e| e.to_string())?;

        let bytes = serde_json::to_vec(payload).map_err(|e| e.to_string())?;
        socket
            .send_to(&bytes, addr)
            .map_err(|e| format!("Error enviando paquete a {addr}: {e}"))?;

        let mut buf = [0u8; 4096];
        let (len, _) = socket
            .recv_from(&mut buf)
            .map_err(|e| format!("Sin respuesta de lámpara {ip}:{port}: {e}"))?;

        serde_json::from_slice(&buf[..len])
            .map_err(|e| format!("Respuesta JSON inválida de {ip}: {e}"))
    }

    fn fire_udp_command(ip: &str, port: u16, payload: &Value) -> Result<(), String> {
        let addr: SocketAddr = format!("{ip}:{port}")
            .parse()
            .map_err(|e| format!("IP/puerto inválido ({ip}:{port}): {e}"))?;
        let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| format!("Error abriendo UDP: {e}"))?;
        socket
            .set_write_timeout(Some(Duration::from_millis(400)))
            .map_err(|e| e.to_string())?;
        let bytes = serde_json::to_vec(payload).map_err(|e| e.to_string())?;
        socket
            .send_to(&bytes, addr)
            .map_err(|e| format!("Error enviando comando WiZ a {addr}: {e}"))?;
        Ok(())
    }

    pub fn parse_pilot_state(resp: &Value) -> Result<(Option<String>, SmartDeviceState), String> {
        let res = resp
            .get("result")
            .ok_or_else(|| "Respuesta WiZ sin campo 'result'".to_string())?;
        let mac = res
            .get("mac")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        let is_on = res.get("state").and_then(|v| v.as_bool()).unwrap_or(false);
        let dimming = res
            .get("dimming")
            .and_then(|v| v.as_u64())
            .map(|v| v.clamp(10, 100) as u8)
            .unwrap_or(100);
        let r = res.get("r").and_then(|v| v.as_u64()).map(|v| v.min(255) as u8);
        let g = res.get("g").and_then(|v| v.as_u64()).map(|v| v.min(255) as u8);
        let b = res.get("b").and_then(|v| v.as_u64()).map(|v| v.min(255) as u8);
        let c = res.get("c").and_then(|v| v.as_u64()).map(|v| v.min(255) as u8);
        let w = res.get("w").and_then(|v| v.as_u64()).map(|v| v.min(255) as u8);
        let temp_kelvin = res
            .get("temp")
            .and_then(|v| v.as_u64())
            .map(|v| v as u16);
        let scene_id = res
            .get("sceneId")
            .and_then(|v| v.as_u64())
            .filter(|&v| v > 0)
            .map(|v| v as u16);
        let speed = res
            .get("speed")
            .and_then(|v| v.as_u64())
            .map(|v| v.clamp(10, 200) as u8);
        let rssi = res.get("rssi").and_then(|v| v.as_i64()).map(|v| v as i32);

        Ok((
            mac,
            SmartDeviceState {
                is_on,
                brightness: dimming,
                r,
                g,
                b,
                c,
                w,
                temp_kelvin,
                scene_id,
                speed,
                rssi,
            },
        ))
    }

    pub fn probe_device(&self, ip: &str, port: u16, timeout_ms: u64) -> Result<SmartDeviceInfo, String> {
        let pilot = Self::send_udp_request(ip, port, &json!({"method":"getPilot","params":{}}), timeout_ms)?;
        let (mac_opt, state) = Self::parse_pilot_state(&pilot)?;
        let mac = mac_opt.unwrap_or_else(|| ip.replace('.', ""));

        let sys_cfg = Self::send_udp_request(
            ip,
            port,
            &json!({"method":"getSystemConfig","params":{}}),
            timeout_ms.min(450),
        )
        .ok();
        let module_name = sys_cfg
            .as_ref()
            .and_then(|v| v.pointer("/result/moduleName"))
            .and_then(|v| v.as_str())
            .unwrap_or("ESP_SHRGB")
            .to_string();
        let room_id = sys_cfg
            .as_ref()
            .and_then(|v| v.pointer("/result/roomId"))
            .and_then(|v| v.as_u64())
            .map(|v| v as u32);

        let supports_rgb = module_name.contains("RGB")
            || state.r.is_some()
            || !module_name.contains("TW");
        let supports_temp = true;

        Ok(SmartDeviceInfo {
            id: mac.clone(),
            driver: "wiz".to_string(),
            ip: ip.to_string(),
            port,
            mac: mac.clone(),
            name: format!("WiZ {}", &mac[mac.len().saturating_sub(4)..]),
            module_name,
            room_id,
            supports_rgb,
            supports_temp,
            state,
        })
    }

    pub fn send_raw(&self, ip: &str, port: u16, json_cmd: &str) -> Result<String, String> {
        let val: Value = serde_json::from_str(json_cmd)
            .map_err(|e| format!("JSON de comando WiZ inválido: {e}"))?;
        let resp = Self::send_udp_request(ip, port, &val, 800)?;
        serde_json::to_string(&resp).map_err(|e| e.to_string())
    }
}

fn build_broadcast_socket(timeout_ms: u32) -> Result<UdpSocket, String> {
    let sock = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
        .map_err(|e| format!("Error creando socket UDP: {e}"))?;
    sock.set_broadcast(true).map_err(|e| e.to_string())?;
    sock.set_reuse_address(true).ok();
    sock.set_read_timeout(Some(Duration::from_millis(120)))
        .map_err(|e| e.to_string())?;
    let bind_addr: SocketAddr = "0.0.0.0:0".parse().unwrap();
    sock.bind(&bind_addr.into())
        .map_err(|e| format!("Error bind UDP: {e}"))?;
    let std_sock: UdpSocket = sock.into();
    let _ = timeout_ms;
    Ok(std_sock)
}

fn parse_subnet_prefix(prefix: &str) -> Option<[u8; 3]> {
    let parts: Vec<&str> = prefix.trim().split('.').collect();
    if parts.len() < 3 {
        return None;
    }
    let a = parts[0].parse::<u8>().ok()?;
    let b = parts[1].parse::<u8>().ok()?;
    let c = parts[2].parse::<u8>().ok()?;
    Some([a, b, c])
}

impl SmartDeviceDriver for WizDriver {
    fn driver_id(&self) -> &'static str {
        "wiz"
    }

    fn discover(
        &self,
        subnet_prefix_ipv4: Option<&str>,
        timeout_ms: u32,
        sweep_subnet: bool,
    ) -> Result<Vec<SmartDeviceInfo>, String> {
        let sock = build_broadcast_socket(timeout_ms)?;
        let get_pilot = br#"{"method":"getPilot","params":{}}"#;
        let reg_pkt = br#"{"method":"registration","params":{"phoneMac":"AAAAAAAAAAAA","register":false,"phoneIp":"1.2.3.4","id":"1"}}"#;

        let mut targets: Vec<SocketAddr> = vec![
            SocketAddr::new(IpAddr::V4(Ipv4Addr::BROADCAST), WIZ_PORT),
        ];
        if let Some(prefix) = subnet_prefix_ipv4.and_then(parse_subnet_prefix) {
            let bcast = Ipv4Addr::new(prefix[0], prefix[1], prefix[2], 255);
            targets.push(SocketAddr::new(IpAddr::V4(bcast), WIZ_PORT));
        }

        for addr in &targets {
            let _ = sock.send_to(reg_pkt, addr);
            let _ = sock.send_to(get_pilot, addr);
        }

        let mut found_ips: HashMap<String, (String, SmartDeviceState)> = HashMap::new();
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.clamp(200, 3000) as u64);
        let mut buf = [0u8; 4096];

        while Instant::now() < deadline {
            match sock.recv_from(&mut buf) {
                Ok((len, src)) => {
                    if let Ok(val) = serde_json::from_slice::<Value>(&buf[..len]) {
                        if let Ok((mac_opt, state)) = Self::parse_pilot_state(&val) {
                            let ip = src.ip().to_string();
                            let mac = mac_opt.unwrap_or_else(|| ip.replace('.', ""));
                            found_ips.entry(ip).or_insert((mac, state));
                        }
                    }
                }
                Err(_) => {
                    if !found_ips.is_empty() && Instant::now() + Duration::from_millis(120) >= deadline {
                        break;
                    }
                }
            }
        }

        // Optional fast parallel /24 subnet sweep if broadcast was dropped by router AP isolation
        if sweep_subnet || found_ips.is_empty() {
            if let Some(prefix) = subnet_prefix_ipv4.and_then(parse_subnet_prefix) {
                let mut handles = Vec::new();
                for chunk_start in (1u8..=254).collect::<Vec<_>>().chunks(32) {
                    let hosts: Vec<u8> = chunk_start.to_vec();
                    handles.push(thread::spawn(move || {
                        let mut hits = Vec::new();
                        let Ok(s) = UdpSocket::bind("0.0.0.0:0") else {
                            return hits;
                        };
                        let _ = s.set_read_timeout(Some(Duration::from_millis(180)));
                        for last in hosts {
                            let addr = SocketAddr::new(
                                IpAddr::V4(Ipv4Addr::new(prefix[0], prefix[1], prefix[2], last)),
                                WIZ_PORT,
                            );
                            let _ = s.send_to(get_pilot, addr);
                        }
                        let end = Instant::now() + Duration::from_millis(320);
                        let mut b = [0u8; 2048];
                        while Instant::now() < end {
                            match s.recv_from(&mut b) {
                                Ok((len, src)) => {
                                    if let Ok(val) = serde_json::from_slice::<Value>(&b[..len]) {
                                        if let Ok((mac_opt, state)) = Self::parse_pilot_state(&val) {
                                            let ip = src.ip().to_string();
                                            let mac = mac_opt.unwrap_or_else(|| ip.replace('.', ""));
                                            hits.push((ip, mac, state));
                                        }
                                    }
                                }
                                Err(_) => break,
                            }
                        }
                        hits
                    }));
                }
                for h in handles {
                    if let Ok(hits) = h.join() {
                        for (ip, mac, state) in hits {
                            found_ips.entry(ip).or_insert((mac, state));
                        }
                    }
                }
            }
        }

        // Enrich with getSystemConfig
        let mut devices = Vec::with_capacity(found_ips.len());
        for (ip, (mac, state)) in found_ips {
            let sys_cfg = Self::send_udp_request(
                &ip,
                WIZ_PORT,
                &json!({"method":"getSystemConfig","params":{}}),
                250,
            )
            .ok();
            let module_name = sys_cfg
                .as_ref()
                .and_then(|v| v.pointer("/result/moduleName"))
                .and_then(|v| v.as_str())
                .unwrap_or("ESP_SHRGB")
                .to_string();
            let room_id = sys_cfg
                .as_ref()
                .and_then(|v| v.pointer("/result/roomId"))
                .and_then(|v| v.as_u64())
                .map(|v| v as u32);
            let supports_rgb = module_name.contains("RGB")
                || state.r.is_some()
                || !module_name.contains("TW");
            devices.push(SmartDeviceInfo {
                id: mac.clone(),
                driver: "wiz".to_string(),
                ip,
                port: WIZ_PORT,
                mac: mac.clone(),
                name: format!("WiZ {}", &mac[mac.len().saturating_sub(4)..]),
                module_name,
                room_id,
                supports_rgb,
                supports_temp: true,
                state,
            });
        }
        devices.sort_by(|a, b| a.ip.cmp(&b.ip));
        Ok(devices)
    }

    fn get_state(&self, ip: &str, port: u16) -> Result<SmartDeviceState, String> {
        let resp = Self::send_udp_request(
            ip,
            port,
            &json!({"method":"getPilot","params":{}}),
            700,
        )?;
        let (_, state) = Self::parse_pilot_state(&resp)?;
        Ok(state)
    }

    fn set_power(&self, ip: &str, port: u16, on: bool) -> Result<(), String> {
        let payload = json!({"method":"setPilot","params":{"state": on}});
        Self::fire_udp_command(ip, port, &payload)
    }

    fn set_brightness(&self, ip: &str, port: u16, dimming: u8) -> Result<(), String> {
        let d = dimming.clamp(10, 100);
        let payload = json!({"method":"setPilot","params":{"state": true, "dimming": d}});
        Self::fire_udp_command(ip, port, &payload)
    }

    fn set_rgb(
        &self,
        ip: &str,
        port: u16,
        r: u8,
        g: u8,
        b: u8,
        dimming: Option<u8>,
    ) -> Result<(), String> {
        let mut params = serde_json::Map::new();
        params.insert("state".into(), json!(true));
        params.insert("r".into(), json!(r));
        params.insert("g".into(), json!(g));
        params.insert("b".into(), json!(b));
        if let Some(d) = dimming {
            params.insert("dimming".into(), json!(d.clamp(10, 100)));
        }
        Self::fire_udp_command(ip, port, &json!({"method":"setPilot","params":params}))
    }

    fn set_temp(
        &self,
        ip: &str,
        port: u16,
        kelvin: u16,
        dimming: Option<u8>,
    ) -> Result<(), String> {
        let k = kelvin.clamp(2200, 6500);
        let mut params = serde_json::Map::new();
        params.insert("state".into(), json!(true));
        params.insert("temp".into(), json!(k));
        if let Some(d) = dimming {
            params.insert("dimming".into(), json!(d.clamp(10, 100)));
        }
        Self::fire_udp_command(ip, port, &json!({"method":"setPilot","params":params}))
    }

    fn set_scene(
        &self,
        ip: &str,
        port: u16,
        scene_id: u16,
        speed: Option<u8>,
        dimming: Option<u8>,
    ) -> Result<(), String> {
        let mut params = serde_json::Map::new();
        params.insert("state".into(), json!(true));
        params.insert("sceneId".into(), json!(scene_id.max(1)));
        if let Some(sp) = speed {
            params.insert("speed".into(), json!(sp.clamp(10, 200)));
        }
        if let Some(d) = dimming {
            params.insert("dimming".into(), json!(d.clamp(10, 100)));
        }
        Self::fire_udp_command(ip, port, &json!({"method":"setPilot","params":params}))
    }
}
