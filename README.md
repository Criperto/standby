# StandBy (`com.criperto.standby`)

> Reloj de escritorio Always-On Display (AOD) con Material You, editor profesional de escenas por capas, shaders AGSL (`liquid_glass`), motor nativo en Rust con expresiones y Regex, recorte de profundidad IA con ONNX y centro de control de Hogar Inteligente multi-marca local + Home Assistant para Android 12–15+.

![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)
![Version: 0.3.0](https://img.shields.io/badge/version-0.3.0-orange.svg)
![Platform: Android 12–15+](https://img.shields.io/badge/platform-Android%2012--15%2B%20(arm64)-3DDC84.svg)
![Core: Rust + Compose M3](https://img.shields.io/badge/core-Rust%20%2B%20Jetpack%20Compose-purple.svg)

Desarrollado por **Criperto** ([github.com/Criperto](https://github.com/Criperto)).

---

## 🌟 Características Principales

- **Modo StandBy AMOLED & Salvapantallas (`DreamService` + Auto-Monitor):**
  - Activación automática al enchufar y colocar el teléfono en horizontal (con salida automática al ponerlo en vertical y reingreso al volver a horizontal).
  - Reglas configurables por fuente de carga (CA, USB, inalámbrica), ventanas horarias, días de la semana y exclusiones de calendario.
  - Brillo de ventana independiente del brillo del sistema, modo nocturno automático con tinte cálido opcional y desplazamiento periódico anti-quemado OLED (*Pixel Shift*).
  - **Presencia IA 24/7 Local:** Detección facial local con cámara + sensor de luz ambiental que apaga la pantalla a negro AMOLED `#000000` cuando la habitación está a oscuras sin nadie enfrente, y la despierta al detectar luz o rostro.
- **Tap-to-Wake Smart Home Hub (Multi-Marca Local + Home Assistant):**
  - Tocar la pantalla despierta un panel **Material You** (100% adaptado a los colores dinámicos del wallpaper del teléfono) con retorno automático al reloj tras inactividad.
  - **Control Estilo Google Home:** Tarjetas anchas donde **un toque alterna `ON ↔ OFF`** y **deslizar horizontalmente regula el `%`** (brillo en luces, temperatura `16°C–30°C` en aires acondicionados, volumen `%` en Smart TVs o velocidad en ventiladores) sin apuntar a botones pequeños.
  - **Drivers Locales Directos (Cero Servidor, Cero Nube):**
    - **WiZ RGB / Tunable White** por UDP local (`:38899` / `:38900`) con rueda de color HSV, temperatura Kelvin (`2200K–6500K`), modo *Luz Diurna* y catálogo de 32 escenas dinámicas.
    - **WLED** por HTTP JSON (`/json/state`).
    - **Shelly (Gen1/Gen2) y Tasmota** por HTTP local.
    - **Yeelight / Xiaomi** por TCP local (`:55443`) y **Philips Hue Bridge**.
  - **Integración con Home Assistant Server:** Auto-descubrimiento en LAN (`homeassistant.local:8123` / escaneo `:8123`) o conexión por URL + Token para controlar **Luces de cualquier marca (`light.*`)**, **Aires Acondicionados (`climate.*`)**, **Smart TVs (`media_player.*`)**, **Enchufes (`switch.*`)** y **Ventiladores (`fan.*`)**.
- **Editor Profesional de Escenas en el Teléfono:**
  - Lienzo virtual `1920×1080` con zoom/paneo multitáctil, panel de herramientas colapsable (`< Lienzo` / `> Panel`) y vista previa 1:1 en vivo.
  - **Sistema de Capas, Grupos y Auto-Layout:** Árbol jerárquico con orden Z, bloqueo, visibilidad, agrupación y auto-layout (`Fila`, `Columna`, `Grilla 2×2`, `Radial`).
  - **Gizmo Táctil y Pivote Inteligente:** Manipuladores de posición, escala por esquinas/lados, rotación con snap a `15°`, vectores de gradiente y **pivote interactivo con conservación de posición visual**.
  - **Guías Magnéticas (`Smart Guides`):** Autoalineación a centros, bordes, tercios y espaciado equidistante entre objetos con respuesta háptica.
  - **Todos los Elementos Personalizables:** Relojes enteros o por componente/dígito individual (`clock_hours`, `clock_minutes`, `clock_h1..s2`), fechas, textos con macros, figuras paramétricas, iconos `.svg` importables, batería, clima en vivo automático (Open-Meteo sin API key), reproductor multimedia y botones Smart Home 100% personalizables.
- **Shaders GPU Modulares (AGSL):**
  - Port nativo a Android AGSL del shader **`liquid_glass`** (refracción con índice IOR, dispersión cromática, escarchado *frost*, iluminación especular de borde y biselado sobre texto y figuras) + importador de shaders `.agsl` personalizados.
- **Recorte de Fondo con IA Local (ONNX Runtime) y Smart Depth:**
  - Descarga bajo demanda de modelos de segmentación (`BiRefNet General Lite`, `RMBG-1.4`, `IS-Net General`, `IS-Net Anime`, `U²-Netp`) que se ejecutan **una sola vez por wallpaper**, generando máscara WebP y grilla de oclusión `128×72` para efecto de profundidad por carácter (*Smart Depth*).
- **Motor Nativo de Expresiones y Regex en Rust (`standby-core`):**
  - Evaluador AST con 28 curvas de easing Penner, física de resortes (`SpringSolver`), funciones de color Material You (`@primary`, `@secondary`, `@tertiary`, `mix`, `lighten`, `darken`) y **funciones Regex en tiempo lineal** (`regexMatch`, `regexTest`, `regexExtract`, `regexReplace`).
- **Ecosistema Tipográfico y Presets Portables (`.json` / `.standby`):**
  - Escáner de fuentes del sistema, familia **Apple SF Pro Display** integrada, descarga directa desde la API de **Fontsource** e importación de archivos `.ttf` / `.otf`.
  - Importación/exportación de paquetes `.standby` (ZIP con fuentes, SVGs y wallpaper), pegado directo de JSON desde el portapapeles y herramienta CLI en PC (`standby-cli`) para validar o migrar presets desde `CripertoShell Lockscreen`.

---

## 🛠 Arquitectura

- **`rust/standby-core` (Rust + UniFFI `0.32`):** Motor multiplataforma compilado a `arm64-v8a` con alineación de página de 16 KB (compatible con Android 15):
  - `expr/`: Parser Pratt, caché AST, evaluador reactivo, regex seguro en tiempo lineal, interpolador de macros `$MACRO` / `${expr}` y analizador estático de frecuencia de tick (`MinuteOnly` / `Second` / `Frame60`).
  - `geometry/`: Transformaciones jerárquicas padre→hijo, cambio de pivote sin salto visual y motor de snapping magnético ponderado por proximidad.
  - `mask/`: Post-procesado morfológico de máscaras IA (choke/erosión, smoothstep, desenfoque gaussiano separable y bitmask `128×72`).
  - `triggers/`: Máquina de estados determinista para disparadores de carga, orientación, horario, presencia de cámara/luz e histéresis de lux.
  - `smarthome/`: Driver UDP local para lámparas WiZ (`38899`) y catálogo de escenas.
  - `presets/`: Validador de esquema v1, sanitizador, importador de presets de `CripertoShell` y empaquetador `.standby`.
- **`rust/standby-cli`:** CLI de escritorio para validar presets JSON, evaluar expresiones/regex y probar dispositivos en LAN.
- **`android/` (Kotlin + Jetpack Compose Material 3 + AGSL + ONNX Runtime + CameraX):** Interfaz nativa Material You, renderizador GPU de escenas, `DreamService`, servicio monitor y Hub Smart Home multi-marca.

---

## 🚀 Compilación y Releases

El proyecto incluye un script maestro de compilación y publicación (`./scripts/release.sh` y `./scripts/build-apk.sh`):

```bash
# 1. Compilar APK optimizada (e instalar por ADB si hay un teléfono conectado)
./scripts/build-apk.sh

# 2. Ejecutar suite completa de tests (Rust + Android JVM)
source ./scripts/env.sh
cd rust && cargo test --workspace
cd ../android && STANDBY_SKIP_GRADLE_RUST=1 ./gradlew testDebugUnitTest --no-daemon

# 3. Crear nueva versión, compilar APK y publicar Release en GitHub como 'latest'
./scripts/release.sh --patch --github -m "Descripción de la versión"
```

### Validación de Presets en PC (`standby-cli`)
```bash
source ./scripts/env.sh
cargo run --manifest-path rust/Cargo.toml --bin standby-cli -- validate android/app/src/main/assets/presets/*.json
```

Documentación detallada adicional:
- [Esquema de Presets (`docs/PRESET_SCHEMA.md`)](docs/PRESET_SCHEMA.md)
- [Guía de Expresiones, Regex y Acciones (`docs/EXPRESIONES_GUIA.md`)](docs/EXPRESIONES_GUIA.md)
- [Guía de Reemplazo de Icono SVG (`docs/ICON_REPLACE.md`)](docs/ICON_REPLACE.md)

---

## 👤 Autor

- **Criperto (Tomas)** — [https://github.com/Criperto](https://github.com/Criperto)
- Repositorio oficial: [https://github.com/Criperto/standby](https://github.com/Criperto/standby)

---

## 📄 Licencia

Este proyecto está licenciado bajo los términos de la **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)**.
Consultá el archivo [LICENSE](LICENSE) para más detalles.
