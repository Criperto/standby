use standby_core::expr::eval::{eval_value, EvalContext};
use standby_core::presets::{import_cripertoshell_preset, sanitize_and_validate_preset};
use standby_core::smarthome::wiz::{wiz_scene_catalog, WizDriver, WIZ_PORT};
use standby_core::smarthome::SmartDeviceDriver;
use std::env;
use std::fs;
use std::process;

fn print_usage() {
    eprintln!(
        "Uso: standby-cli <comando> ...
Comandos:
  version
  validate <preset.json> [<preset2.json> ...]
  import-criperto <entrada.json> <salida.json>
  eval <expresión>
  wiz discover [prefijo_subred ej. 192.168.1]
  wiz state <ip>
  wiz on|off <ip>
  wiz dim <ip> <10..100>
  wiz rgb <ip> <r> <g> <b> [dim]
  wiz temp <ip> <kelvin> [dim]
  wiz scene <ip> <sceneId> [dim]
  wiz scenes"
    );
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        print_usage();
        process::exit(1);
    }

    match args[1].as_str() {
        "version" => println!("{}", standby_core::core_version()),
        "validate" => {
            if args.len() < 3 {
                print_usage();
                process::exit(1);
            }
            let mut had_error = false;
            for path in &args[2..] {
                let raw = match fs::read_to_string(path) {
                    Ok(c) => c,
                    Err(e) => {
                        eprintln!("[ERROR] {path}: no se pudo leer: {e}");
                        had_error = true;
                        continue;
                    }
                };
                let report = sanitize_and_validate_preset(&raw);
                if report.valid {
                    println!(
                        "[OK] {path} (frame_tick={}, sec_tick={}, fontsource={:?}, svgs={:?})",
                        report.needs_frame_tick,
                        report.needs_second_tick,
                        report.missing_fontsource_ids,
                        report.referenced_svgs
                    );
                    for w in report.warnings {
                        println!("  [WARN] {w}");
                    }
                } else {
                    had_error = true;
                    eprintln!("[FAIL] {path}");
                    for e in report.errors {
                        eprintln!("  [ERR] {e}");
                    }
                }
            }
            if had_error {
                process::exit(2);
            }
        }
        "import-criperto" => {
            if args.len() < 4 {
                print_usage();
                process::exit(1);
            }
            let raw = fs::read_to_string(&args[2]).unwrap_or_else(|e| {
                eprintln!("Error leyendo {}: {e}", args[2]);
                process::exit(1);
            });
            match import_cripertoshell_preset(&raw) {
                Ok(preset) => {
                    let out_json = serde_json::to_string_pretty(&preset).unwrap();
                    fs::write(&args[3], out_json).unwrap_or_else(|e| {
                        eprintln!("Error escribiendo {}: {e}", args[3]);
                        process::exit(1);
                    });
                    println!(
                        "Importado '{}' con {} objetos -> {}",
                        preset.name,
                        preset.objects.len(),
                        args[3]
                    );
                }
                Err(e) => {
                    eprintln!("Error importando preset: {e}");
                    process::exit(1);
                }
            }
        }
        "eval" => {
            if args.len() < 3 {
                print_usage();
                process::exit(1);
            }
            let expr = args[2..].join(" ");
            let ctx = EvalContext::default();
            println!("{}", eval_value(&expr, &ctx).as_str());
        }
        "wiz" => {
            if args.len() < 3 {
                print_usage();
                process::exit(1);
            }
            let drv = WizDriver;
            match args[2].as_str() {
                "discover" => {
                    let prefix = args.get(3).map(|s| s.as_str());
                    match drv.discover(prefix, 900, prefix.is_some()) {
                        Ok(devs) => println!("{}", serde_json::to_string_pretty(&devs).unwrap()),
                        Err(e) => {
                            eprintln!("Error descubriendo WiZ: {e}");
                            process::exit(1);
                        }
                    }
                }
                "scenes" => {
                    println!("{}", serde_json::to_string_pretty(&wiz_scene_catalog()).unwrap());
                }
                "state" if args.len() >= 4 => match drv.get_state(&args[3], WIZ_PORT) {
                    Ok(st) => println!("{}", serde_json::to_string_pretty(&st).unwrap()),
                    Err(e) => {
                        eprintln!("Error consultando {}: {e}", args[3]);
                        process::exit(1);
                    }
                },
                "on" if args.len() >= 4 => {
                    drv.set_power(&args[3], WIZ_PORT, true).unwrap();
                    println!("OK ON -> {}", args[3]);
                }
                "off" if args.len() >= 4 => {
                    drv.set_power(&args[3], WIZ_PORT, false).unwrap();
                    println!("OK OFF -> {}", args[3]);
                }
                "dim" if args.len() >= 5 => {
                    let dim: u8 = args[4].parse().unwrap_or(100);
                    drv.set_brightness(&args[3], WIZ_PORT, dim).unwrap();
                    println!("OK DIM {dim}% -> {}", args[3]);
                }
                "rgb" if args.len() >= 7 => {
                    let r: u8 = args[4].parse().unwrap_or(255);
                    let g: u8 = args[5].parse().unwrap_or(255);
                    let b: u8 = args[6].parse().unwrap_or(255);
                    let dim = args.get(7).and_then(|s| s.parse::<u8>().ok());
                    drv.set_rgb(&args[3], WIZ_PORT, r, g, b, dim).unwrap();
                    println!("OK RGB({r},{g},{b}) -> {}", args[3]);
                }
                "temp" if args.len() >= 5 => {
                    let k: u16 = args[4].parse().unwrap_or(3000);
                    let dim = args.get(5).and_then(|s| s.parse::<u8>().ok());
                    drv.set_temp(&args[3], WIZ_PORT, k, dim).unwrap();
                    println!("OK TEMP {k}K -> {}", args[3]);
                }
                "scene" if args.len() >= 5 => {
                    let sid: u16 = args[4].parse().unwrap_or(1);
                    let dim = args.get(5).and_then(|s| s.parse::<u8>().ok());
                    drv.set_scene(&args[3], WIZ_PORT, sid, None, dim).unwrap();
                    println!("OK SCENE {sid} -> {}", args[3]);
                }
                _ => {
                    print_usage();
                    process::exit(1);
                }
            }
        }
        _ => {
            print_usage();
            process::exit(1);
        }
    }
}
