# Guía de Expresiones, Regex y Acciones (`EXPRESIONES_GUIA.md`)

El motor `standby-core` (escrito en Rust) evalúa expresiones reactivas, funciones regulares (`Regex`), curvas de animación Penner, colores dinámicos Material You y acciones para widgets y lámparas WiZ RGB.

---

## 1. Variables del Contexto en Vivo

| Variable | Tipo | Descripción |
|---|---|---|
| `t` | Número | Tiempo continuo en segundos (activa tick a 60fps solo si una expresión visible lo usa) |
| `hour`, `minute`, `second`, `millisecond` | Número | Hora local actual |
| `battery` | Número | Nivel de batería (`0..100`) |
| `hasBattery`, `isCharging` | Bool | Estado de energía |
| `hasMedia`, `isPlaying`, `hasCover` | Bool | Estado del reproductor multimedia activo |
| `mediaProgress` | Número | Progreso de reproducción (`0.0..1.0`) |
| `mediaPosition`, `mediaLength` | Número | Posición y duración en segundos |
| `mediaTitle`, `mediaArtist` | String | Título y artista actuales |
| `weatherTemp`, `weatherCode` | String/Num | Temperatura actual (ej. `"22°"`) y código WMO |
| `ambientLux` | Número | Luz ambiental del sensor en lux |
| `wizOn`, `wizBrightness` | Bool/Num | Estado de la lámpara WiZ principal (`0..100`) |
| `base` / `value` | Número | Valor base configurado en el objeto |

---

## 2. Macros en `custom_text` (`$MACRO` y `${expr}`)

En cualquier objeto `custom_text` podés combinar macros directos y bloques `${...}`:

- Macros rápidos: `$HOUR`, `$MINUTE`, `$SECOND`, `$BATTERY`, `$DATE`, `$DATETIME`, `$TITLE`, `$ARTIST`, `$WEATHER_TEMP`, `$WIZ_NAME`, `$WIZ_DIM`.
- Expresiones embebidas:
  ```text
  Ahora suena: ${regexReplace($TITLE, "(?i)\s*\((official|lyric|audio|video|hd|4k)[^)]*\)", "")}
  ```

---

## 3. Funciones de Expresiones Regulares (`Regex` en Tiempo Lineal)

Todas las funciones regex usan el motor `regex` de Rust con caché LRU precompilado (tiempo lineal `O(n)`, inmune a bloqueos):

1. **`regexTest(texto, patron)`** → `bool` (`1.0` o `0.0`)
   - Ejemplo en `visibleCondition`: `hasMedia && regexTest(mediaTitle, "(?i)feat\\.|ft\\.|live")`
2. **`regexMatch(texto, patron)`** → `string` (primera coincidencia completa o `""`)
   - Ejemplo: `regexMatch(mediaTitle, "#[A-Za-z0-9_]+")`
3. **`regexExtract(texto, patron, grupo)`** → `string` (extrae el grupo de captura `0, 1, 2...`)
   - Ejemplo: `regexExtract(mediaTitle, "^([^-]+)\\s*-\\s*(.+)$", 1)`
4. **`regexReplace(texto, patron, reemplazo)`** → `string` (soporta `$1`, `$2` en el reemplazo)
   - Ejemplo: `regexReplace(mediaTitle, "(?i)\\s*\\[[^\\]]*\\]", "")`

Funciones adicionales de texto: `upper(s)`, `lower(s)`, `trim(s)`, `len(s)`, `substr(s, inicio, largo)`, `contains(s, sub)`, `startsWith(s, pre)`, `endsWith(s, suf)`, `padStart(s, largo, char)`.

---

## 4. Matemáticas, Ondas y 28 Curvas de Easing

- Matemáticas: `sin`, `cos`, `tan`, `abs`, `sqrt`, `pow`, `min`, `max`, `clamp(x, a, b)`, `lerp(a, b, t)`, `smoothstep(a, b, x)`, `map(x, inMin, inMax, outMin, outMax)`, ` fract(x)`, `mod(a, b)`.
- Ondas periódicas: `wave(t, freq)`, `triangle(t, period)`, `saw(t, period)`, `pulse(t, freq, duty)`.
- Easings Penner (`0..1`):
  `easeInSine`, `easeOutSine`, `easeInOutSine`, `easeInQuad`, `easeOutQuad`, `easeInOutQuad`, `easeInCubic`, `easeOutCubic`, `easeInOutCubic`, `easeInQuart`, `easeOutQuart`, `easeInOutQuart`, `easeInQuint`, `easeOutQuint`, `easeInOutQuint`, `easeInExpo`, `easeOutExpo`, `easeInOutExpo`, `easeInCirc`, `easeOutCirc`, `easeInOutCirc`, `easeInBack`, `easeOutBack`, `easeInOutBack`, `easeInElastic`, `easeOutElastic`, `easeInOutElastic`, `easeOutBounce`.

---

## 5. Colores Dinámicos Material You

En la propiedad `color` podés usar tokens semánticos del teléfono (`@primary`, `@secondary`, `@tertiary`, `@surface`, `@surfaceContainer`, `@onSurface`, `@error`) y funciones de color:

- `mix(@primary, @tertiary, 0.5 + 0.5 * sin(t))`
- `alpha(@primary, isCharging ? 1.0 : 0.5)`
- `lighten(@secondary, 0.2)` / `darken(@primary, 0.3)`
- `rgb(255, 120, 40)` / `rgba(80, 200, 255, 0.8)` / `hsv(t * 25, 0.8, 1.0)`

---

## 6. Acciones Interactivas (`onTap`)

Podés encadenar acciones separadas por `;`:

- **Lámpara WiZ RGB local (UDP 38899)**:
  - `wiz(toggle, primary)`
  - `wiz(on, primary)` / `wiz(off, primary)`
  - `wiz(brightness, primary, 80)`
  - `wiz(rgb, primary, #ff5500)`
  - `wiz(temp, primary, 2700)`
  - `wiz(scene, primary, 30)` (ej. `3` = Atardecer, `14` = Luz nocturna, `30` = Ciberpunk)
- **Multimedia**:
  - `media(play_pause)`, `media(next)`, `media(prev)`
- **Variables de Estado del Preset**:
  - `flip(@|expanded)`
  - `set(@|tab, 1)`
  - `inc(@|counter, 1)`
  - `cyclestate(@|mode, 0, 3)`
