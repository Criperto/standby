# StandBy Preset Schema v1 (`PRESET_SCHEMA.md`)

Esta guía documenta el formato JSON de presets de **StandBy** (`schemaVersion: 1`) para que puedas diseñarlos a mano o pedirle a cualquier IA en tu PC que te genere un reloj completo y validarlo con `standby-cli`.

---

## 1. Flujo de Trabajo en PC (`standby-cli`)

Podés validar cualquier JSON o migrar un preset de tu lockscreen de escritorio (`CripertoShell/modules/lockscreen/presets/*.json`) directamente desde la terminal:

```bash
source ./scripts/env.sh

# Validar y sanitizar un preset JSON
cargo run --manifest-path rust/Cargo.toml --bin standby-cli -- validate mi_preset.json

# Convertir un preset de CripertoShell Lockscreen a StandBy (filtra password/power y adapta tokens a Material You)
cargo run --manifest-path rust/Cargo.toml --bin standby-cli -- import-criperto \
  /home/tomas/.config/quickshell/CripertoShell/modules/lockscreen/presets/default_liquidglass.json \
  mi_liquidglass.json
```

En el teléfono podés importar el `.json` o `.standby` desde **Editor → Presets → Importar .json/.standby** o **Pegar JSON (IA PC)**.

---

## 2. Estructura Raíz del Preset

```json
{
  "schemaVersion": 1,
  "name": "Cyber StandBy",
  "description": "Reloj compuesto con Liquid Glass y control WiZ",
  "author": "Criperto",
  "background": {
    "mode": "black",
    "wallpaperUri": null,
    "brightness": 0.35,
    "blurRadius": 0.0,
    "nightDimFactor": 0.5
  },
  "state": {
    "expanded": 0.0,
    "tab": 0.0
  },
  "meta": {
    "targetAspect": "16:9",
    "createdBy": "standby-ai"
  },
  "objects": []
}
```

- `background.mode`: `"black"` (AMOLED `#000000` puro) o `"wallpaper"` (imagen atenuada con soporte de recorte IA ONNX y Smart Depth).

---

## 3. Tipos de Objetos Soportados (`type`)

| Categoría | Valores de `type` |
|---|---|
| Relojes enteros y partes | `"clock"`, `"clock_hours"`, `"clock_minutes"`, `"clock_seconds"`, `"clock_separator"`, `"clock_ampm"` |
| Dígitos individuales (2×2 / 4 dígitos) | `"clock_h1"`, `"clock_h2"`, `"clock_m1"`, `"clock_m2"`, `"clock_s1"`, `"clock_s2"` |
| Fecha y texto | `"date"`, `"datetime"`, `"custom_text"` |
| Figuras e Iconos | `"shape"`, `"icon_svg"` |
| Batería y Clima | `"battery"`, `"battery_icon"`, `"battery_percent"`, `"battery_bar"`, `"weather"`, `"weather_icon"`, `"weather_temp"` |
| Reproductor Media | `"media"`, `"media_cover"`, `"media_title"`, `"media_artist"`, `"media_play_pause"`, `"media_prev"`, `"media_next"`, `"media_progress"` |
| Hogar Inteligente | `"wiz_device"` |
| Contenedor / Grupo | `"group"` |

---

## 4. Propiedades de cada `SceneObject`

- **Identidad y Capas**: `id`, `type`, `name`, `alias`, `zIndex` (int), `visible` (bool), `locked` (bool).
- **Transformación y Pivote**:
  - `posX_ratio` (`0.0..1.0`), `posY_ratio` (`0.0..1.0`), `baseWidth` (px en lienzo 1920×1080), `baseHeight` (px).
  - `pivotX` (`0.0..1.0`, default `0.5`), `pivotY` (`0.0..1.0`, default `0.5`).
  - `rotation` (`0..360`), `scaleX`, `scaleY`, `uniformScale`, `elementOpacity` (`0..1`).
  - Perspectiva 3D: `perspectiveX`, `perspectiveY`, `perspectiveDistance` (default `800`).
- **Grupos y Auto-Layout**:
  - `groupId`, `groupOffsetX`, `groupOffsetY`, `childrenIds`, `autoLayout` (`"none" | "row" | "column" | "grid" | "radial"`), `autoLayoutGap`.
- **Tipografía**:
  - `customFontFamily` (ej. `"Inter"`, `"JetBrains Mono"`), `fontSourceId` (slug de Fontsource para auto-descarga, ej. `"space-grotesk"`), `fontFileName` (`.ttf`/`.otf` local), `fontWeight` (`100..900`), `fontItalic`, `baseFontSize`, `letterSpacing`, `textAlign` (`"left" | "center" | "right"`).
- **Color Material You y Gradiente 4 Modos**:
  - `textColorChoice`: `"primary"`, `"onPrimary"`, `"primaryContainer"`, `"secondary"`, `"tertiary"`, `"surface"`, `"surfaceContainer"`, `"onSurface"`, `"white"`, `"black"`.
  - `isCustomColor`: `true` + `customHex: "#RRGGBB"`.
  - `gradient`: `{ "enabled": true, "type": "linear|radial|conic|mesh", "p0x": 0.5, "p0y": 0.0, "p1x": 0.5, "p1y": 1.0, "stops": [...] }`.
- **Shaders (Liquid Glass y AGSL)**:
  - `liquidGlassEnabled`: `true`, `shaderId`: `"liquid_glass"`.
  - Parámetros: `liquidBezel`, `liquidThickness`, `liquidIor`, `liquidDispersion`, `liquidFrost`, `liquidSaturation`, `liquidRim`, `liquidLightAngle`, `liquidTintAmount`.
- **Máscara IA y Smart Depth**:
  - `isMask`: `true`, `maskModelName`: `"birefnet-general-lite" | "rmbg-1.4-fp16" | "isnet-general-use" | "isnet-anime" | "u2netp"`.
  - `depthCutoutMode`: `"none" | "limit_50" | "always_behind"`, `depthCutoutThreshold`: `50.0`, `maskChoke`, `maskFeather`, `invertMask`.
- **Expresiones y Acciones**:
  - `visibleCondition`: string booleano.
  - `expressions`: mapa `{ "opacity": "...", "offsetX": "...", "offsetY": "...", "rotation": "...", "scale": "...", "color": "..." }`.
  - `actions`: `{ "onTap": "wiz(toggle,primary)" }`.
