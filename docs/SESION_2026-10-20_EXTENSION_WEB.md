# Sesión DragonShot — 2026-10-20

> Resumen de continuidad. No es una transcripción literal: es el estado técnico
> necesario para retomar si la sesión de OpenCode se cierra.
>
> - Repo: `/home/alexander/Proyectos/Bot`
> - HEAD base: `c9c10ee`
> - Modificado: `docs/DISENO_EXTENSION_WEB.md`, `docs/mockup-extension.html`
> - Creado: `extension/` con la **extensión MV3 completa**: manifest, hook
>   MAIN (`page-hook.js`), overlay ISOLATED (`content.js`), auto-calibración,
>   service worker (`background.js`), iconos y pruebas.
> - La app Java se mantiene como respaldo.
> - **Corrección 2026-10-20 (tarde):** la selección ya no bloquea el ratón; el
>   juego recibe los `mousemove` y el cañón apunta al destino. La trayectoria y
>   la marca de potencia funcionan a izquierda/derecha y con `look=0/1`.
> - Pruebas: auto-calibración **14/14**, integración **38/38**.
- **Corrección live 2026-10-20 (noche):** ángulo desde
  `player.angle.worldTransform` y espejo del origen en local antes de rotar por
  `body`. Ver §11.

---

## 1. Objetivo

Terminar la extensión web de DragonShot (red/runtime, overlay, física y pruebas)
y dejar la app Java como respaldo. La extensión MV3 ya está escrita y cargable
como unpacked; queda la validación live de los escenarios G–O, resize/fullscreen,
ciclo de sala/reconexión y las restricciones CSP/SRI/ToS.

---

## 2. Decisiones vigentes

- Responder en español.
- v1 usa **viento 0** (`AIRE = 0`). `wind_power`/`wind_angle` solo se leen para
  diagnóstico.
- No reutilizar opcodes del remake. El opcode del disparo varía por servidor.
- El hook debe detectar el disparo por la forma del payload o por perfil, no por
  un opcode fijo.
- Pixi se captura en `document_start`; el breakpoint fue solo diagnóstico.
- El jugador propio se identifica por `player_info.is_me`, no por índices del
  árbol.
- Producción usa constantes live; el remake usa `398 / 2.34 / 485` como perfil
  separado. No mezclar perfiles.
- `extension/` ya contiene la extensión MV3 completa: `manifest.json`,
  `src/page-hook.js` (MAIN), `src/content.js` (ISOLATED), auto-calibración,
  service worker e iconos. La página del juego solo se lee; no se automatiza el
  disparo.
- La física live **no** se hardcodea: `2.269/390` es fallback y la extensión
  recalibra con `game_shoot.power` + `play.s[3]/s[5]`.
- **Interacción origen/destino:** botón **Seleccionar destino** + clic normal.
  La capa es visual (`pointer-events:none`): los `mousemove` llegan al juego y
  el cañón apunta; el clic final se intercepta en `window` (captura) y fija el
  **destino**. El page-hook recibe `selection-mode` para no crear una muestra de
  disparo con ese clic. Alternativa: `Ctrl + clic izquierdo o derecho` (primer
  clic fija el origen y lo redirige al cañón; el segundo fija el destino). Los
  siguientes clics mueven el destino; `Esc` cancela el modo o limpia; el clic
  derecho sin Ctrl no se intercepta.
- No hay `default_popup`: el icono de la extensión solo muestra/oculta el panel
  persistente. Esto evita que el primer clic se pierda al cerrarse el popup del
  navegador y devolver el foco a la página.
- La marca de potencia es **visual** en el overlay; no se escribe en la barra del
  juego ni se automatiza el disparo en v1.

---

## 3. Estado cerrado

### Java

- 39/39 tests Java ejecutados con Temurin 21 en Linux headless.
- Bugs confirmados: A1 velocidad detrás del objetivo, A2 ángulo obsoleto,
  M1 zoom, M4 rendimiento.
- `Diagnostics` / `OverlaySmoke` requieren Windows/JNA y no se ejecutaron.

### Protocolo y red

- Endpoint live observado: `wss://s3qu3nc3dis0rdred.dragon.ws/6`
  (server 6, `Bunge`).
- Calibración live: server 5, sala 48 (y después sala 19).
- XOR: cliente → servidor `0xA5`; servidor → cliente `0xDB`. WSS conserva TLS.
- Opcodes C→S: login `0x09`, move `0x06` (server 6), aim `0x05` (server 5),
  shoot `0x07` (server 6) / `0x3A` (server 5), ready `0x0A`.
- Opcodes S→C: profile `0x01`, `room_state` `0x05`, `play` `0x06`,
  `update` `0x0B`, `result` `0x0D`.
- Campos del disparo: `x, y, body, look, ang, power, time, selectedShot, extra`.
  `extra` es apuntado/touch, no trayectoria.
- `play[15]` trae `s=[x0,y0,ang_abs,v0,windX,vertAccel]`, `time` (ms de vuelo),
  `h` (impacto) y `d` (daño).
- `wind_power` en índice 13 corresponde al **siguiente** turno; el viento real
  del disparo está en `s[4]`/`s[5]`.

### Física live

- **Fallback:** `v0 = power × 2.269` (rango 2.266–2.275) y gravedad `390` con
  viento 0.
- La captura posterior de la **sala 44** (cuatro disparos `look=0`, `body=0`)
  midió `v0 = power × 2.364` y `s[5] = 402`. Por eso `2.269/390` no es universal.
- Origen Armor local `(a=20.45, c=25.83)`; offset en `body=0`:
  `(20.45, −25.83)`; se espeja en x para `look=0`.
- `look=0` confirmado con los cuatro disparos: `s=[911,−13,...]` para jugador en
  `(931,13)`. `look=1` ya estaba cubierto por la calibración previa.
- Ángulo: con `look=0` y `body=0`, `game_shoot.ang = aim.deg − 180`; el servidor
  envía `ang_abs = 180 − ang`.
- Potencia DOM ↔ `game_shoot.power` confirmada: `195/250/240/223`.
- Auto-calibración implementada en `extension/src/auto-calibration.js`:
  `powerScale = s[3]/power`; `gravity = s[5]` si `s[4] === 0`; mediana de 8.
- Predicción vs `h`: ~1–4 px en los 8 disparos de la calibración previa.
- No mezclar: Java desktop `gravity=500`, `powerScale=10`; remake
  `398 / 2.34 / 485`; producción live con perfil auto-calibrado (fallback
  `390 / 2.269 / segundos`).

### Pixi y jugador propio

- `window.PIXI` es `undefined` después de cargar; se recuperó el
  `InteractionManager` con un breakpoint en `onMouseOut`.
- `this.stage` es el `Stage`; el árbol del juego está accesible.
- `stage/0` aplica `scale = 1.5716667`.
- El contenedor de mundo (padre de los `PlayerContainer-*`) es la cámara:
  `screen = zoom * (world + camera)`.
- Canvas observado: **1257×943**.
- Cámara observada en dos muestras: `(64.57, 194.09)` y `(97, 270)`; la fórmula
  se mantuvo.
- Jugador propio: `PlayerContainer-ZinComPaSioN`, `player_info.is_me === true`.
- Expone `x`, `y`, `look`, `body_angle`, `angle.rotation`, `mobile`, `is_turn`.
- `PlayerInfo-<nombre>` expone `is_me`, `is_turn`, `hp`, `max_hp`, `team`,
  `guild` y `country`.
- No usar índices (`stage/0/2/3`, etc.): el árbol cambia al añadir/quitar joyas,
  almas y efectos. `PlayerContainer-soul` es un jugador muerto, no activo.

### Potencia en vivo (cerrado 2026-10-20)

La potencia **no** está en Pixi: es DOM dentro de `#gameui`.

| Elemento | Valor observado | Uso |
|---|---|---|
| `#powerBar` | `offsetLeft=244`, `offsetTop=569`, alto 22 px; `style.width` = potencia | Barra de carga; crece de 0 a 400 px. |
| `#powerMarks` | `offsetWidth=400`, mismo origen | Contenedor de marcas. |
| `#powerMarkArea` | `offsetWidth=400` | Área de la barra. |
| `#powerMark` | `offsetLeft=195`, ancho 11 px, `left:200px` | Marca fija de referencia. |
| `#last_power_mark` | ancho 1 px; `style.left = 247 + potencia` | Marca de la **última** potencia disparada. |

Lectura:

```js
// durante la carga
const power = parseFloat(document.querySelector('#powerBar').style.width) || 0;

// después del disparo (última potencia)
const lastPower =
  (parseFloat(document.querySelector('#last_power_mark').style.left) || 0) - 247;
```

Muestras reales (server 5, sala 19):

| Disparo | `#powerBar` | `#last_power_mark.left` | `left − 247` |
|---|---:|---:|---:|
| 1 | 182 px | 429 px | 182 |
| 2 | 117 px | 364 px | 117 |

La relación es lineal y exacta dentro de 1 px. La barra conserva el ancho de la
última potencia después del disparo; para la potencia *actual* hay que leerla
mientras el jugador mantiene el clic. `#powerMark` no es la potencia actual.

**Resuelto:** los cuatro disparos de la sala 44 coincidieron exactamente con
`game_shoot.power` (`195/250/240/223`). La auto-calibración usa esa misma unidad
(§16.12 del diseño).

### Interacción origen/destino (diseño 2026-10-20)

Estado guardado en **coordenadas de mundo**; la pantalla solo captura el clic.

- `Ctrl + clic derecho` 1.º: fija `origin = own-cannon` y **redirige el marcador
  al cañón** del móvil propio, aunque el clic sea lejos. Pasa a modo destino.
- `Ctrl + clic derecho` 2.º: fija `destination = world(click)`, dibuja la
  trayectoria solución y la marca de potencia.
- Siguientes `Ctrl + clic derecho`: mueven el destino.
- `Ctrl + clic derecho` sobre el móvil propio: vuelve a fijar el origen.
- `Esc`: limpia el destino y vuelve al modo origen.
- `Shift + Ctrl + clic derecho`: fuerza destino sin tocar el origen (opcional).
- El clic derecho **sin Ctrl** no se intercepta.
- Si el destino cae sobre el rival, se puede anclar a su entidad y seguirlo; si
  no hay hitbox fiable, queda como punto fijo.
- El origen no se congela: se recalcula cada frame con `originFor` (móvil,
  `body`, `look`).
- La marca de potencia se dibuja en el overlay a `P/400` del ancho de
  `#powerMarkArea`/`#powerMarks` (666 px con el zoom live, no el ancho parcial de
  `#powerBar`); no se modifica la barra ni se automatiza el disparo en v1.
- **Corrección del 2026-10-20 tarde:** la capa de selección pasó a
  `pointer-events:none`. Antes bloqueaba los `mousemove`, el cañón se quedaba
  apuntando al lado anterior y un destino a la derecha daba `OBJETIVO DETRÁS`.
  Ahora el juego recibe el movimiento, apunta al cursor y el clic se intercepta
  en captura. El page-hook ignora el gesto con `selection-mode`.
- La trayectoria y la marca se validan en integración con destino a la derecha
  (`look=1`, ángulo 20°) y a la izquierda (`look=0`, ángulo 160°): ambas dan
  potencia válida y origen espejado. No se fuerza un ángulo distinto al real.

Detalle completo y pruebas G–O: `docs/DISENO_EXTENSION_WEB.md` §8.2.

---

## 4. Pendientes

### Implementación MV3 (hecho)

1. **Hook real de Pixi en `document_start`** implementado con accessor de
   `window.PIXI`, proxy de `Application` y fallback por `Renderer.render`.
2. **Hook real de WebSocket** implementado: envuelve el constructor, conserva
   los frames originales y decodifica XOR `0xA5`/`0xDB` solo para observar.
3. **Física JS integrada** con la auto-calibración y el overlay; probada con
   WebSocket/Pixi simulados (`2.3641/402`) y reset por `room_state`.
4. **Overlay responsive** full-viewport en Shadow DOM, con resize y DPR; falta
   probar fullscreen/zoom live.
5. **Selección interactiva** implementada: botón **Seleccionar destino** + capa
   visual que no bloquea el ratón, y `Ctrl + clic izquierdo/derecho` como atajo;
   el primer clic fija el origen en el cañón, el segundo fija el destino, los
   siguientes lo mueven, `Esc` cancela/limpia. El destino es fijo; `entity`
   (seguir al rival) queda pendiente.
6. **Manifest MV3, service worker e iconos** creados en `extension/`; carga
   verificada como unpacked en Brave (MAIN + ISOLATED montan hook y overlay).
7. ~~**Ángulo real ↔ `game_shoot`**~~ **cerrado:** con `look=0` y `body=0`,
   `ang = aim.deg − 180`; el último disparo dio `aim.deg=200` → `ang=20`.
8. ~~**Validar `look=0`**~~ **cerrado:** cuatro disparos propios en la sala 44;
   origen `s=[911,−13,...]` para `(931,13)`.

### Pendiente de validación live

1. **CSP / SRI / ToS**: auditar si el uso y la inyección en `MAIN` están
   permitidos. La extensión ya carga en el dominio, pero no se ha revisado ToS.
2. **Escenarios G–O**: primer clic lejos del cañón, segundo clic, mover destino,
   clic sobre el móvil propio, objetivo detrás/inalcanzable y resize/fullscreen.
3. **Turno / observador / cambio de sala / reconexión**: sin probar en partida;
   el árbol de Pixi se reconstruye y puede romper referencias.
4. **`game_start` live**: no se recapturó; su contrato conocido no lleva
   `v0`/gravedad.
5. **Colisión con terreno**: no confirmada; la trayectoria actual se corta por
   viewport, no por mapa.

### Calibración y cobertura

- ~~**Potencia ↔ `game_shoot.power`**~~ **cerrado:** 195/250/240/223 exactos.
- **Auto-calibración:** `extension/src/auto-calibration.js` implementada y con
  **14/14** pruebas en navegador. `powerScale=s[3]/power`; `gravity=s[5]` solo
  con `s[4]===0`; mediana de 8; fallback `2.269/390`.
- **`room_state`/`game_start`:** `room_state` de salas 48 y 44 capturado y
  comparado: mismo modo/mapa/`max_wind`; no declara física. `game_start` live no
  se recapturó (la partida había terminado); su contrato conocido tampoco lleva
  `v0`/gravedad.
- **Origen por móvil**: solo está calibrado **Armor**. Falta decidir si la v1
  será Armor-only o calibrar otros móviles.
- **Colisión con terreno**: no confirmada en producción.
- **Opcodes por servidor**: el hook **no** hardcodea `0x07`; detecta
  `game_shoot` por forma del payload y `play`/`room_state` por estructura.
  Server 6 usa `0x07`, server 5 usa `0x3A`.

### Producto y seguridad

- **Token de sesión**: no loguearlo ni publicarlo. La captura cruda
  `ws-capture.jsonl` lo contiene.
- **Diagnostics / OverlaySmoke de Java**: requieren Windows/JNA y no se
  ejecutaron.
- **Límites de v1**: dejar claro que usa viento 0 y que no se mezclan constantes
  de desktop, remake y producción.

### Implementación

- **`extension/` está completo para el MVP:** `manifest.json`, `icons/`,
  `src/page-hook.js`, `src/content.js`, `src/auto-calibration.js`,
  `src/background.js`, `test/` y `README.md`.
- El harness de integración con Pixi/WebSocket simulados es temporal
  (`/tmp/opencode/test_extension_integration.py`); no se versiona porque usa
  CDP y una instancia Brave concreta.

---

## 5. Próximo paso recomendado

1. Cargar `extension/` como unpacked en Brave/Chrome y recargar
   `https://dragonbound.net/`.
2. En una partida real, ejecutar los escenarios G–O de §8.2.3: primer clic lejos
   del cañón, segundo clic, mover destino, clic sobre el móvil propio, objetivo
   detrás/inalcanzable y resize/fullscreen.
3. Verificar que la calibración pasa de `FALLBACK` a `CALIBRADO (n)` tras un
   disparo propio y que `room_state`/reconexión vuelven a fallback.
4. Capturar `game_start` si se quiere evidencia live adicional y probar
   `look=1`/`body≠0`.
5. Revisar CSP/SRI/ToS antes de publicar o distribuir la extensión.
6. Posponer los fixes Java A1/A2/M1/M4 hasta validar la extensión.

---

## 6. Artefactos y archivos

### Documentación y referencia

- `docs/DISENO_EXTENSION_WEB.md`: diseño actualizado (Pixi, jugador propio,
  cámara, potencia y pendientes). Modificado sin commit.
- `docs/mockup-extension.html`: referencia visual de la UI.
- `README.md`: objetivo y estado de la app Java.
- `HANDOFF.md`: handoff antiguo; no confiar en su estado live.

### Extensión (nuevo)

- `extension/manifest.json`: MV3, `content_scripts` MAIN/ISOLATED, iconos.
- `extension/src/page-hook.js`: WebSocket + Pixi + snapshots + calibración.
- `extension/src/content.js`: overlay, panel acoplado, selección por capa y
  `Ctrl + clic`, solución de potencia y trayectoria.
- `extension/src/auto-calibration.js`: módulo puro de auto-calibración.
- `extension/src/background.js`: service worker que muestra/oculta el panel
  desde el icono; no hay popup del navegador.
- `extension/icons/`: iconos.
- `extension/README.md`: instalación, uso y limitaciones.
- `extension/test/auto-calibration.test.html`: página de pruebas (14/14).
- `extension/test/integration.test.html`: hook → overlay (37/37), incluidos
  destino izquierdo/derecho, `look=0/1` y marcador de potencia.
- `extension/test/auto-calibration.test.js`: casos y aserciones.
- `extension/test/fixtures/captured-shots.js`: payloads reales sin token.
- `/tmp/opencode/test_extension_integration.py`: harness temporal de integración
  con Pixi/WebSocket simulados (no versionado).

### Capturas y scripts (temporales)

- `/tmp/opencode/dragonbound-live/calibration-ws.jsonl`: 154 frames de la
  calibración server 5.
- `/tmp/opencode/dragonbound-live/shot-ws.jsonl`: captura de los cuatro disparos
  `look=0` de la sala 44.
- `/tmp/opencode/dragonbound-live/room-state-ws.jsonl`: captura de `room_state`
  de la sala 44; contiene frames de login, **no publicar**.
- `/tmp/opencode/dragonbound-live/ANALISIS.md`: análisis de la captura live del
  server 6.
- `/tmp/opencode/dragonbound-live/power-poll.log`: lecturas de `#powerBar` y
  `#last_power_mark` de los disparos 182/117.
- `/tmp/opencode/cdp_capture_im.py`: captura el `InteractionManager` vía
  breakpoint `mouseout`.
- `/tmp/opencode/cdp_eval.py`: helper para evaluar JS por CDP.
- `/tmp/opencode/cdp_ws_capture.py`: captura pasiva de WebSocket por CDP.
- `/tmp/opencode/pixi_probe.js`: probe pre-load para capturar `window.PIXI`.
- `/tmp/opencode/power_poll.py`: poll de la barra de potencia.
- `/tmp/opencode/dragonbound-live/ws-decrypted-redacted.txt`: muestra segura sin
  token.

> **No publicar** `ws-capture.jsonl` ni capturas crudas: contienen el token de
> sesión.

### Código Java relevante

- `src/main/java/com/dragonshot/sim/physics/Ballistics.java`: física Java, bug A1
  y constantes desktop.
- `src/main/java/com/dragonshot/sim/state/SessionState.java`: bug A2 de ángulo
  obsoleto.
- `src/main/java/com/dragonshot/sim/pipeline/GamePipeline.java`: transformación,
  recaptura y constantes.
- `dragonshot.properties`: calibración actual de la app desktop.

---

## 7. Cómo retomar

1. Revisar `git status` y `git diff docs/DISENO_EXTENSION_WEB.md`.
2. Leer `docs/DISENO_EXTENSION_WEB.md`, sobre todo §5.2, §7.4, §8, §8.2,
   §16.10, §16.11 y §16.12.
3. Comprobar que la app Java sigue en 39/39 tests antes de tocar código.
4. Para la próxima prueba live, abrir el juego en Brave/Chrome y usar los scripts
   de `/tmp/opencode` con CDP en el puerto 9222.
5. No pegar tokens ni capturas crudas en el chat ni en el repo.

---

## 8. Estado de la sesión al guardar

- El usuario quedó en `https://dragonbound.net/#server-5/room-44`, en la pantalla
  de sala/lobby tras terminar la partida.
- `look=0`, potencia ↔ `game_shoot` y ángulo con `body=0` quedaron cerrados.
- `room_state` de salas 48 y 44 capturado y comparado; no declara física.
- Auto-calibración implementada en `extension/src/auto-calibration.js` con
  **14/14** pruebas en navegador.
- Interacción implementada en `extension/src/content.js`: botón **Seleccionar
  destino** + capa visual sin `pointer-events`, y `Ctrl + clic` como atajo;
  primer clic fija origen en el cañón, segundo fija destino, siguientes mueven
  el destino, `Esc` cancela o limpia; trayectoria y marca de potencia incluidas.
- Extensión MV3 completa en `extension/`; se cargó como unpacked en Brave y se
  verificó que MAIN/ISOLATED montan hook y overlay en `dragonbound.net`.
- Integración hook → auto-calibración → overlay → reset probada con Pixi y
  WebSocket simulados: `powerScale=2.3641`, `gravity=402`, panel `166/400`,
  `room_state` vuelve a fallback. Integración **37/37**; incluye destino
  izquierdo/derecho, `look=0/1` y marca de potencia visible.
- Pendiente: validación live tras la corrección, escenarios G–O, resize/
  fullscreen, reconexión, CSP/SRI/ToS.
- `docs/DISENO_EXTENSION_WEB.md`, `docs/mockup-extension.html` y
  `docs/SESION_2026-10-20_EXTENSION_WEB.md` están modificados; `extension/` es
  nuevo. Todo queda listo para commit/push.

---

## 9. Actualización live (continuación de la sesión)

### Captura Pixi sin `window.PIXI`

- El cliente real sigue sin exponer `window.PIXI`. La captura definitiva observa
  `Function.prototype.bind`: los objetos Pixi enlazan sus handlers y exponen
  `stage`. No se reemplazan constructores ni se parchea `send`.
- La primera captura era el **stage del avatar del lobby** y también contenía un
  jugador con `player_info.is_me`; el snapshot salía `sin-jugador` o con la
  cámara equivocada.
- Solución implementada en `extension/src/page-hook.js`:
  - se conservan hasta 12 `stage` candidatos (`pixiApps`);
  - se elige el que contiene un jugador con `is_me`;
  - `visibleGameCanvas()` exige `#Dragon2D` visible y un canvas con ancho/alto;
  - sin partida activa el snapshot devuelve `sin-juego`, no datos del lobby;
  - `bind` se restaura solo cuando hay canvas de juego visible y jugador propio.
- El móvil se obtiene de `me.mobile.filename` (p. ej.
  `/static/images/mobiles/Armor2D.png`), no de `player_info.mobile`, que no
  existe en el cliente live.

### Canvas, cámara y turno (live)

- Canvas y cámara validados en salas 67/68: `screen =
  zoom * (world + camera)`, sumando `canvas.rect.left/top` para el cliente.
  Zoom live observado: `0.715`, `0.7517`, `1.5717`.
- Canvas live observado: `572×429`, `601×451`, `630×473`, `1257×943`.
- `#turn_line0` contiene al jugador activo; cuando es el turno propio incluye
  `turn_line_me`. Es la fuente de `snapshot.me.isTurn`. Los índices de `play` no
  se usan para decidir el turno.
- `#powerBar.style.width` es la potencia actual (0–400); `#last_power_mark.left`
  es `247 + potencia` (se conserva el offset 247).
- El overlay live dibujó origen, destino, trayectoria y `POTENCIA NECESARIA
  68/400`, además de las marcas “actual” y “necesaria” sobre la barra. Captura
  local: `/tmp/opencode/live-overlay4.png` (no versionada).

### Pendiente live

- La prueba de arrastre manual no cambió la barra en el estado de sala 67; no se
  pudo capturar un `pointerup` con potencia > 0 ni verificar la calibración con
  el `play` entrante en esa sesión.
- La sala 68 terminó y el botón de inicio quedó en estado de resultados; crear
  una sala nueva vuelve a funcionar.
- Siguen pendientes: escenarios G–O, resize/fullscreen, reconexión, colisión con
  terreno, objetivo entidad/rival y auditoría CSP/SRI/ToS.
- Integración hook → overlay: **37/37** tras añadir la guarda `#Dragon2D`, la
  prueba de clic sin carga, la capa de selección que no bloquea el ratón, los
  destinos izquierdo/derecho con `look=0/1`, la marca de potencia, el
  acoplamiento del panel y el toggle del icono. Auto-calibración: **14/14**.
  Java: **39/39** (sin cambios en esta sesión; no re-ejecutado por falta de JDK
  en este entorno).

---

## 10. Corrección del foco y ventanita persistente

### Diagnóstico

- El **popup del navegador** (`action.default_popup`) se cierra al hacer clic en
  la página; el primer clic se consume devolviendo el foco y puede parecer que
  la extensión no detecta el origen/destino.
- El panel medía `274 px` y en un viewport live de `1156×485` el canvas ocupaba
  `x=300..946`; el margen derecho era de solo `210 px`. El panel se dibujaba
  encima del canvas (`870..1144`) y tapaba parte del campo de juego.
- La selección dependía de `Ctrl + clic derecho`; el menú contextual y el foco
  añadían una vía más de fallo.

### Cambios

- **Sin `default_popup`:** se eliminó `popup.html`; `src/background.js` atiende
  `chrome.action.onClicked` y envía `dragonshot-toggle-panel` al content script
  para ocultar/mostrar la ventanita.
- **Panel compacto y acoplado:** ancho `196 px`, cabecera contraíble y
  `positionPanel()` lo coloca a la derecha/izquierda/abajo/arriba del canvas
  según el espacio. El borde ámbar (`data-dock="overlay"`) indica que no hay
  margen exterior (p. ej. fullscreen).
- **Modo de selección:** el botón **Seleccionar destino** activa una capa
  visual con marco cian. La capa usa `pointer-events:none`, así el ratón llega
  al juego y el cañón apunta; un clic normal se intercepta en `window` (captura)
  y fija el destino en coordenadas de mundo. El page-hook ignora ese gesto para
  no crear una muestra falsa. `Esc`, clic derecho o clic fuera cancela.
- **Atajo conservado:** `Ctrl + clic izquierdo o derecho`; se suprimen
  `mousedown`/`mouseup`/`click` para que el juego no interprete el clic de
  selección. El clic derecho sin Ctrl no se intercepta.
- **Panel por encima de la capa:** permite cancelar y seguir viendo el estado
  mientras se elige el destino.

### Verificación

- Integración hook → overlay: **38/38** (incluye capa de selección sin bloquear
  el ratón, destinos a izquierda/derecha, panel fuera del canvas y toggle del
  icono).
- Live: extensión recargada en Brave; el content script nuevo monta el panel de
  `196 px` en `x=952..1148` (viewport `1156`). La página quedó en el lobby
  `#server-5`, sin `#Dragon2D` visible, así que el snapshot sigue siendo
  `sin-juego`; la prueba en partida continúa pendiente.

---

## 11. Corrección del apuntado y del origen (live, 2026-10-20 noche)

La primera prueba en partida real reportó dos fallos:

1. La trayectoria solo salía bien con el destino a la **izquierda**; a la
   derecha la curva aparecía invertida.
2. Con el móvil mirando a la **izquierda**, el inicio de la trayectoria seguía
   dibujándose a la derecha.

### Diagnóstico

- `angle.rotation` es la rotación de **pantalla** de Pixi (Y hacia abajo,
  positiva horaria) e **incluye** `look` y `body`: `rotation = −ang_abs`. El
  código anterior la trataba como el `ang` relativo del cliente y le restaba
  `body`, así que con `look=1` devolvía `360 − ang_abs` (reflejo horizontal).
- La muestra live `aim.deg = 200` ↔ `ang = 20` (`look=0`, `body=0`) confirma
  `ang_abs = 360 − aim.deg = 160`, es decir `ang_abs = −aim.deg`.
- `originFor` espejaba el `dx` **después** de rotar por `body`. `game.js` rota
  `{x: x + dis, y: y − dy}` con `dis = −20,45` cuando `look=0`, o sea espeja el
  offset local antes de rotar.
- El lado del móvil se tomaba solo de `player.look`, que en vivo puede no
  coincidir con el sprite en el momento de apuntar.

### Cambios

- `page-hook.js`: `readAim(me)` lee `me.angle.worldTransform` y calcula
  `angAbs = normalize(−atan2(b, a)·180/π)`. Sin `worldTransform` usa
  `−rotation`. Ya no se resta `body` ni se aplica el espejo de `look`.
- `detectLook(me, angDeg)`: si el apuntado es claramente horizontal
  (`|cos| ≥ 0,5`) manda su signo; si no, `look`; si falta, `scale.x < 0`.
  El snapshot publica `lookSource` y `aimSource` para diagnóstico.
- `originFor` (y el respaldo `computeOrigin`) espejan `sx·a` antes de rotar:
  `dx = sx·a·cos + c·sin`, `dy = sx·a·sin − c·cos`.
- El `ang` del disparo simulado en `pointerup` se reconstruye relativo al cuerpo
  (`look=0 → 180 − abs − body`; `look=1 → abs + body`).

### Verificación

- Auto-calibración **14/14** (nuevas expectativas del espejo local con
  `body=26`).
- Integración **38/38**: se añadió `worldTransform` al Pixi falso, destino
  derecho con `look=1`, `look` atrasado corregido por el apuntado y apuntado
  casi vertical que respeta `look`.
- Las pruebas de navegador se ejecutaron en Brave headless vía CDP
  (`127.0.0.1:9223`) sirviendo el repo en `127.0.0.1:8123`.
- **Pendiente:** recargar la extensión en Brave y validar en partida real
  destinos izquierda/derecha, `look=0/1`, `body ≠ 0` y la marca de potencia.
  El panel muestra `ÁNGULO` en grados matemáticos: apuntando 45° arriba-derecha
  debe leer `45°`, y arriba-izquierda `135°`.

## 12. Corrección del espejo del apuntado (live, 2026-10-21)

La validación tras §11 reportó que el disparo al oponente de la **izquierda**
seguía descalibrado: potencia sugerida y trayectoria no cuadraban. Dato clave
del panel: apuntando al rival de la izquierda, `ÁNGULO` marcaba **50–70°**.

### Diagnóstico

- Un cañón apuntando a la izquierda debe dar `> 90°`. El valor mostrado era el
  **reflejo vertical** del real (`180 − 120 = 60`): el `worldTransform` de
  `angle` no siempre incluye el espejo del sprite, así que la lectura llega con
  el coseno cambiado y el mismo seno.
- Esa lectura espejada (coseno positivo) hacía que `detectLook` dedujera
  `look=1` aunque `player.look` fuera `0`. Con `look=1` el origen no se espejaba
  (cañón a la derecha) y la potencia se resolvía para el lado contrario.
- Un solo error explicaba los dos síntomas: potencia y trayectoria.

### Cambios (v0.1.2)

- `detectLook` vuelve a priorizar `player.look`; `scale.x` y el signo del
  apuntado quedan como último recurso (se elimina el círculo vicioso).
- `alignAimToLook(deg, look)`: si el signo horizontal del ángulo no coincide con
  el lado del móvil, se refleja con `180 − deg`. Cerca de la vertical
  (`|cos| < 0,1`) no se toca.
- El snapshot publica `aimRaw`, `aimMirrored` y `aimCheck`; el panel añade una
  línea `L0 · B-17° · R72° · θ72° · e0°` (lado, cuerpo, rotación Pixi, ángulo
  leído y error del último disparo real).
- `aimCheck` compara cada `game_shoot` real con el ángulo mostrado y deja el
  residuo a la vista; no se automatiza ningún disparo.

### Verificación

- Auto-calibración **14/14**.
- Integración **40/40**: lectura espejada con `look=0` → 120° y origen a la
  izquierda con solución válida; lectura espejada con `look=1` → 20° y origen a
  la derecha.
- Ejecutado en Brave headless vía CDP contra `127.0.0.1:8123`.
- **Pendiente:** recargar en Brave y confirmar en partida real que, apuntando a
  la izquierda, `ÁNGULO` pasa de 90° y que `e0°` del último disparo queda cerca
  de 0.

## 13. Cierre del apuntado: inversión vertical del cañón (2026-10-21, v0.1.3)

§12 no bastó: en la partida real el tiro a la izquierda seguía mal. Esta vez se
validó **contra el cliente**, no solo contra el Pixi falso.

### Cómo se midió

- Brave del usuario con `--remote-debugging-port=9222`; se movió el ratón con
  `Input.dispatchMouseEvent` (eventos trusted) y se leyó el snapshot en vivo.
- Se capturó el canvas con `Page.captureScreenshot` para **ver** el cañón y el
  dial que dibuja el juego alrededor del móvil.
- Se añadió `tree()` al hook para volcar el subárbol Pixi del jugador
  (`me → body → angle`), sin publicar frames ni tokens.

### Hallazgo

Con `look=0` y `aimRaw=235°`, el dial del juego marcaba **~135°**. La relación
no es un reflejo horizontal (`180 − deg`) sino una **inversión vertical**
(`−deg`):

```
raw  = 180 + ang + body        (look = 0)
real = 180 − ang − body = −raw (mod 360)
```

Con `look=1` no hay espejo y `real = raw`. Confirmado con:

- `look=0`, `raw=200°` ↔ `ang` del cliente 20° → real 160°.
- `look=1`, `raw=24°` y rival a 28° → el cañón apuntaba al rival.
- Fixtures del servidor: `look=0` → `180 − ang`; `look=1` → `ang − body`.

### Cambios (v0.1.3)

- `alignAimToLook(deg, look, source)`: con `source='world'` devuelve `−deg` si
  `look=0` y `deg` si `look=1`. Se elimina el umbral `|cos| < 0,1` (la inversión
  es sistemática). La lectura `rotation` mantiene la heurística anterior.
- El mensaje `play` publica `truth` (`s[2]`), `raw`, `aimSource` y `look` del
  mismo instante, para verificar la conversión sin DevTools.
- `tree()` en el hook, solo para diagnóstico manual.

### Verificación

- Auto-calibración **14/14**; integración **41/41** con lecturas reales
  (`look=0, raw=240 → 120`; `look=0, raw=200 → 160`; `look=1, raw=20 → 20`;
  `look=0, raw=265 → 95`).
- **Confirmado en vivo (8 disparos):** `play` publica `truth`/`raw` del mismo
  instante y el residuo fue 0° en todos los casos:
  `look=0` → 231→129, 235→125, 230→130, 206→154;
  `look=1` → 50→50, 63→63. La corrección `look=0 → −raw`, `look=1 → raw`
  coincide con el servidor en ambos lados.

### Pendiente detectado al cerrar

La calibración de potencia mezcla tipos de bala: en esta sesión se disparó con
`#btnShot2.Pressed` y el perfil quedó en `2.2919/386` (8 muestras), frente a
`2.3641/402` de los fixtures (`selectedShot=0`). Detalle funcional y técnico en
**§16.16** del diseño; resumen en `HANDOFF.md` §5.3. No implementado.

---

## 14. Soporte de Boomer: calibración por móvil (2026-10-29, v0.1.4)

### Pedido

La v0.1.3 era estable con Armor y `AIRE = 0`. Al pasar al móvil **Boomer** los
disparos se quedaban cortos: hacía falta perfeccionar el tiro, la trayectoria y
el cálculo para ese móvil.

### Causa

La gravedad del juego es **por móvil** (tabla del cliente: `a = 75` en Armor,
`a = 62,5` en Boomer). `AutoCalibration` guardaba `powerSamples` y
`gravitySamples` globales, así que Boomer heredaba la gravedad de Armor
(`402`) en vez de la suya (`~335`): un 17 % de más, que a larga distancia son
cientos de unidades de error. El primer disparo de un móvil nuevo, además,
usaba el respaldo `390` sin intentar predecir su gravedad.

### Cambios

- `MOBILE_A` (21 móviles) y `mobileKey()` en `auto-calibration.js`;
  `/static/images/mobiles/Boomer2D.png` → `boomer`.
- Cubos por móvil `Map<móvil, {power, gravity}>` con ventana de 8; se eliminaron
  los arrays globales.
- `gravityUnits = s[5] / a` permite **predecir** la gravedad de un móvil sin
  muestras: Armor `402` → unidad `5,36` → Boomer `335`; sin observaciones,
  `390/75·62,5 = 325`.
- La potencia usa el cubo propio; si está vacío, la mediana global (la escala es
  del disparo, no del móvil); si tampoco, `DEFAULT_PROFILE`.
- El perfil publica `mobile`, `gravitySource`, `samples` del cubo activo y
  `originMeasured` (el origen se mide siempre, pero solo se aplica con
  `calibrateOrigin`). El panel muestra `CALIBRADO (n)`, `PREDICHO` o `FALLBACK`.
- `__DS_EXT_HOOK__.profile()` usa el móvil del jugador actual (antes caía al
  último disparo o a Armor).
- `resetPhysics()` limpia cubos/unidades (la sala puede cambiar constantes) y
  conserva orígenes.
- `manifest.json` → **0.1.4**. `page-hook.js` y `content.js` no necesitaron
  cambios: ya pasan el móvil y consumen `snap.profile`.

### Pruebas

- Auto-calibración **20/20** (nuevas: `mobileKey`, predicción de Boomer con y
  sin muestras, herencia de potencia global, cubos separados, móvil sin tabla y
  origen medido sin aplicar).
- Integración **41/41** sin regresiones.
- Ejecutadas en Brave headless por CDP (puerto 9223) contra las páginas
  `test/auto-calibration.test.html` y `test/integration.test.html`.

### Pendiente

- Validar Boomer contra el cliente real (5 disparos al mismo destino) y ajustar
  su **origen**, que hoy usa el de Armor.
- La calibración por tipo de bala sigue pendiente (§16.16); los cubos son por
  móvil, no por `S1/S2/SS`.
- Si se habilita el viento, `b` también es por móvil (Boomer `1,39`).

---

## 15. Cobertura del resto de móviles (2026-10-29, v0.1.5)

### Pedido

Dejar el offset de Boomer como pendiente de prioridad y continuar con la
calibración, la trayectoria y el cálculo de la barra de poder de los **demás
móviles**.

### Qué se hizo

- Se verificó la tabla completa de 21 móviles (`a` de gravedad) y se añadió
  normalización de acentos en `mobileKey()` (`Eléctrico2D.png` → `electrico`);
  también se resuelven `Dragon22D.png` y `Dragon2_2D.png` → `dragon2`.
- El motor ya era genérico: la trayectoria y `solvePower` usan la gravedad del
  perfil activo. Se añadieron pruebas que lo demuestran:
  - **23/23** auto-calibración: los 21 móviles predicen `unidad · a` tras una
    muestra de Armor; Dragon2 aprende `643,2` sin tocar el cubo de Armor.
  - **50/50** integración: con el mismo destino y ángulo, Boomer pide menos
    potencia que Armor y Dragon2 más; el panel muestra `PREDICHO` y publica
    `data-mobile`/`data-gravity`/`data-gravity-source`.
- `manifest.json` → **0.1.5**.

### Comportamiento por móvil

- Gravedad: predicha por tabla hasta el primer disparo; después, `s[5]` real.
- Potencia: escala global (del disparo) con cubo por móvil.
- Trayectoria: parábola con la gravedad del móvil activo.
- Barra: `0..400`; si la potencia necesaria supera 400, `INALCANZABLE`.
- Origen: **sigue pendiente** para todos (se mide, no se aplica).

### Pendiente

- Validación live móvil por móvil y origen por móvil.
- Tipo de bala (§16.16) y viento por móvil (Boomer `b=1,39`) si se habilitan.

---

## 16. Origen del cañón por móvil (2026-10-29, v0.1.6)

### Pedido

Realizar el primer pendiente: el **origen del cañón** (offset) para cada móvil,
en lugar de reutilizar el de Armor.

### Qué se hizo

- `AutoCalibration.getOrigin(mobile)` calcula la mediana de `(a, c)` del cubo
  del móvil y la **MAD** (mediana de las distancias a la mediana), con
  `stable = MAD ≤ maxOriginMad` (2 unidades). El solver ya deshacía el espejo
  (`look`) y la rotación (`body`) en cada `play`.
- `getProfile(mobile)` aplica el origen medido solo si el móvil **no** tiene un
  origen ajustado, hay al menos `minOriginSamples` (3) muestras y son estables.
  `TUNED_ORIGINS` conserva Armor (`20,45 / 25,83`), validado en vivo, para no
  degradarlo con una medición ruidosa.
- Nuevas opciones: `calibrateOrigin` (`'auto'` por defecto, `true` fuerza desde
  la primera muestra, `false` solo mide), `minOriginSamples` y `maxOriginMad`.
- El perfil publica `originSource` (`measured` / `tuned` / `default`),
  `originCalibrated` y `originMeasured` (`a`, `c`, `samples`, `mad`, `stable`).
- El panel añade el origen al tooltip (`origen medido (n)`, `origen fijo`,
  `origen por defecto`) y `data-origin-source`.
- `resetPhysics()` conserva los orígenes (propiedad del sprite, no de la sala);
  `reset()` los borra.

### Pruebas

- Auto-calibración **27/27**. Nuevas:
  - Boomer aplica su origen tras 3 muestras estables.
  - Con 2 muestras sigue el origen por defecto.
  - Armor conserva el valor ajustado (`tuned`).
  - Una secuencia inestable (MAD 20) no se aplica.
- Integración **54/54**. Nuevo: 3 disparos de Boomer con la boca en
  `(816, 92)` dejan `originSource = 'measured'`, el snapshot usa ese origen y el
  panel publica `data-origin-source`.
- `manifest.json` → **0.1.6**.

### Pendiente

- Validación live móvil por móvil: confirmar que el offset medido coincide con
  el cañón real (los 3 primeros disparos de un móvil sin ajuste usan Armor).
- Tipo de bala (§16.16) y viento por móvil si se habilitan.
