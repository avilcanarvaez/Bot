# Diseño: Extensión de navegador para DragonBound (juego web)

> Documento de diseño y plan de implementación. La extensión MV3 está
> implementada en `extension/` (hook, overlay, auto-calibración y pruebas); queda
> la validación live G–O y la auditoría CSP/SRI/ToS.
> Objetivo: reemplazar el enfoque de captura de escritorio (app Java) por una
> extensión MV3 que se integra con el juego web y lee su estado real.

---

## 0. Resumen ejecutivo

El repositorio `DragonBoundR/DragonBound` es un **juego web HTML5** servido por
Express, con cliente en un único bundle JS sobre **Pixi.js** y comunicación con el
servidor de juego por **WebSocket en JSON**. Por eso una **extensión de navegador**
es el vehículo correcto:

- El overlay vive **dentro de la página**, en las coordenadas exactas del juego
  (sin `Robot`, sin DPI, sin exclusión de captura, sin JNA, multiplataforma).
- El **estado real** (ángulo, potencia, posición, turno, viento) se obtiene de la
  **red (WebSocket)** y del **runtime del cliente** (Pixi), no por visión artificial.
- La física se puede **replicar** con el modelo del remake (`weight = 398`, escala
  de potencia `×2.34`, divisor `485`) y, para producción, con un **perfil
  auto-calibrado** desde `play`: la escala inicial es `v0 = power × 2.269`,
  gravedad `390`, tiempo en **segundos** y origen de **Armor** `(20.45, -25.83)`
  rotado por `body` (§7.4 y §16.9). La captura del **2026-10-20** en la sala 44
  midió `v0/power = 2.364` y `s[5] = 402`, por lo que la extensión recalibra en
  runtime con `game_shoot.power` + `play.s[3]/s[5]` (§16.12). Con **AIRE = 0** la
  trayectoria es balística pura.
- La selección de **origen/destino** es interactiva: botón **Seleccionar
  destino** + clic normal sobre una capa encima del canvas, o `Ctrl + clic
  izquierdo/derecho` como atajo; el origen es el cañón del móvil propio, el
  destino es un punto de mundo y se dibuja la trayectoria + la marca de potencia
  (§8.2).

> **Decisión de la primera versión (v1):** el viento se maneja como **0**
> (`AIRE = 0`). La extensión puede leer `wind_power`/`wind_angle` para diagnóstico,
> pero la física, `SIMULAR` y el asistente **no corrigen por viento** en esta
> versión. Ver §7.3.

El proyecto Java de captura **no se descarta formalmente** en este documento: queda
como respaldo hasta completar la validación de la extensión (protocolo, física live
y Pixi verificados; validación live G–O y restricciones pendientes) (ver §15).

> **Importante:** la evidencia de §1 proviene del **remake** `DragonBoundR/DragonBound`,
> no del juego en producción. La verificación live contra `https://dragonbound.net/`
> se realizó el **2026-10-20** y se resume en **§1.2**, **§16.8**, **§16.9** y
> **§16.10**. El protocolo, los opcodes (con variantes por servidor), la física live
> y Pixi quedaron confirmados; las restricciones siguen pendientes.

---

## 1. Hallazgos verificados en el repositorio

Evidencia obtenida del código de `DragonBoundR/DragonBound` (rama `main`).

| Hallazgo | Evidencia |
|---|---|
| Cliente HTML5, un solo bundle | `src/web/public_html/index.html` carga `<script src="/static/js/1023989.js">` (2.26 MB). Sin `.swf` ni `.wasm`. |
| Motor de render: **Pixi.js** | Clases `DisplayObject`, `Sprite`, `DisplayObjectContainer`, `worldTransform`, `renderWebGL` en `1023989.js`. |
| Pixi se oculta tras cargar | `1023989.js:16156-16157`: `var PIXI = window.PIXI; delete window.PIXI;` |
| Red por **WebSocket JSON** | `DragonSocket` en `1023989.js:51356-51412`: `Send(...) → JSON.stringify([opcode, ...args])`; al recibir `d[0]` es el opcode. |
| URL del socket | `1023989.js:41819`: `d.Connect("ws://" + window.location.hostname + ":9001/" + c)`. |
| Envío de disparo | `DragonNetwork.SendPlayerShoot` en `:40746-40758` → `ds.Send(CLIENT_OPCODE.game_shoot, x, y, body, look, ang, …)`. |
| Datos del disparo en el cliente | `CPlayer.prototype.Shoot` en `:43590-43601` envía `(this.x, this.y, this.body, this.look, this.ang, power, time, selectedShot, extra)`. |
| Ángulo vivo | `CPlayer.prototype.ChangeAngleTo` en `:44322-44327`: `this.ang = a` (limitado a `[minang, maxang]`); UI `UpdateGuiAngle` `:44328`, `DrawPlayerAngle` `:44438`. |
| Dirección | `DIR_LEFT = 0`, `DIR_RIGHT = 1` en `:25422-25423`; `this.look`. |
| Mensaje de turno del servidor | `src/game/lib/message.js:431-451`: opcode `play` (6) con posiciones, `look`, viento, thor, clima. |
| Física del servidor | `src/game/lib/shoot.js`: `Vector2` (`:374-379`), `getPosAtTime` (`:174-180`). |
| Parámetros del disparo (servidor) | `src/game/game.js:436-547`: escala potencia (`:440`), espejo por `look` (`:450`), `ang -= body` (`:470`), **`weight: 398`** (`:509`), **`friccion: 0`** (`:510`), viento (`:513-514`). |
| Viento del servidor | `src/game/game.js:18-19,219-235`: `wind_power`, `wind_angle = 90`, generados por turno según `room.max_wind`. |
| Opcodes | `src/game/gametypes.js`: `SERVER_OPCODE` y `CLIENT_OPCODE` (ver §5). |

**Conclusión de la verificación:** es viable leer el estado autoritativo sin visión
por computadora, y replicar la trayectoria con las constantes exactas del juego.

### 1.1 Alcance de la evidencia: remake vs. sitio real

La evidencia anterior proviene del **remake** `DragonBoundR/DragonBound`
("DragonBoundRe — Remake of the original DragonBound game"), **no** del juego en
producción. `https://dragonbound.net/` es el sitio real y **debe verificarse por
separado** (ver §16). Mientras eso no ocurra, en este documento se usa el marcado:

- `↩ repo` = deducido/leído del código del **remake**.
- `⚠ producción` = **pendiente de verificar** en `dragonbound.net`.

Ninguna afirmación sobre el sitio real se da por buena sin pasar el checklist de §16.3.
La captura del **2026-10-20** cubre el transporte y los opcodes; lo que sigue
pendiente se marca explícitamente.

### 1.2 Verificación live (2026-10-20)

Se abrió `https://dragonbound.net/` en un navegador real y se jugó una partida
**1 vs 1** en el servidor **6 (`Bunge`)** con una captura pasiva de WebSocket:
148 frames (80 enviados, 65 recibidos), 5 disparos.

| Hallazgo | Evidencia live |
|---|---|
| Transporte | `wss://s3qu3nc3dis0rdred.dragon.ws/6`, handshake HTTP 101 (nginx). |
| Ofuscación de aplicación | XOR de un byte: cliente → servidor `0xA5`; servidor → cliente `0xDB`. WSS sigue siendo TLS. |
| Framing | Primer byte de opcode + payload textual/JSON-like; **no** es simplemente `JSON.stringify([opcode, ...])`. |
| Disparo | Campos `x,y,body,look,ang,power,time,selectedShot,extra` confirmados. Opcode **variable**: `0x07` (server 6) / `0x3A` (server 5). |
| Turno | `play` es `0x06`; `room_state` `0x05`; `update` `0x0B`; resultado `0x0D`. |
| Movimiento | `game_move` `0x06` (server 6); en server 5 se observó `0x05` para actualizaciones de apuntado. |
| Viento | `play` expone `wind_power` (índice 13) y el ángulo (índice 14); el mapeo de §5.3 es consistente. |
| Cliente | El HTML live no carga `1023989.js`; usa un script inline ofuscado (~202 KB). |
| Runtime | **Pixi.js** confirmado; `window.PIXI` se borra tras cargar. El jugador propio se localiza por `PlayerInfo-*.is_me` en `PlayerContainer-*` (§16.10). |

Pendiente de verificar en producción: las restricciones (CSP/SRI/ToS), la
correlación del ángulo Pixi con `game_shoot` y la validación dentro de la extensión.
La **física live** ya está calibrada (§7.4/§16.9); el protocolo, la física y Pixi
están confirmados.

---

## 2. Por qué extensión y no captura de escritorio

| Problema del enfoque actual (app Java) | Con la extensión |
|---|---|
| `Robot` + `FindWindow` + `SetWindowDisplayAffinity` | **No aplica**; el overlay es DOM/canvas dentro de la página |
| Escalas DPI, rects de ventana, multi-monitor | **No aplica**; coordenadas locales del juego |
| Alineación origen/destino por template matching sobre píxeles | Coordenadas **exactas** del propio juego |
| Ángulo estimado (Hough) | Ángulo **real** (`player.ang`) |
| Viento capturado | Viento **leído** (`wind_power`/`wind_angle`); con AIRE=0 se ignora |
| Windows-only, JNA, click-through | Multiplataforma (Chrome/Edge/Firefox), Manifest V3 |
| Juego debe estar visible y sin tapar | No requiere que la pestaña esté en foco para leer el estado |

---

## 3. Arquitectura de la extensión (Manifest V3)

```
                       Página del juego (origen del server Express)
 ┌─────────────────────────────────────────────────────────────────────┐
 │  MAIN world (página)                    ISOLATED world (contenido)   │
 │  ┌───────────────────────────┐          ┌─────────────────────────┐  │
 │  │ page-hook.js              │          │ content.js              │  │
 │  │  • hook WebSocket.send    │ postMsg  │  • recibe estado        │  │
 │  │  • hook onmessage         │ ───────▶ │  • inyecta overlay      │  │
 │  │  • captura window.PIXI    │          │  • dibuja trayectoria   │  │
 │  │  • lee player.ang/x/y     │ ◀─────── │  • botón SIMULAR        │  │
 │  └───────────────────────────┘ postMsg  └─────────────────────────┘  │
 │             │                                    │                   │
 │             │ WebSocket (wss://…/6)              │ chrome.runtime    │
 └─────────────┼────────────────────────────────────┼───────────────────┘
               ▼                                    ▼
      Servidor de juego (nginx)            Service worker (extensión)
      (autoridad: turno, viento)           • estado/opciones, storage
```

### 3.1 Componentes

| Componente | Contexto | Responsabilidad |
|---|---|---|
| `manifest.json` | — | MV3, `content_scripts` con `"world": "MAIN"` y `"world": "ISOLATED"`, `host_permissions` del origen del juego. |
| `src/page-hook.js` | MAIN | Hooks de `WebSocket` y captura de `window.PIXI`; publica snapshots/eventos por `window.postMessage`. |
| `src/content.js` | ISOLATED | Recibe snapshots, monta el overlay en Shadow DOM, dibuja, gestiona la selección (botón + capa o `Ctrl + clic`) y la solución de potencia. |
| `src/auto-calibration.js` | MAIN | Módulo puro de física/calibración; lo usa `page-hook.js`. |
| `src/background.js` | Service worker | El icono muestra/oculta el panel de página; no hay `default_popup`. |

> **MVP actual:** no hay `overlay.css` ni `core/` separados. La balística AIRE=0
> y la proyección viven en `content.js`; el estado de red y Pixi vive en
> `page-hook.js`. `SIMULAR` todavía no existe como botón. El service worker solo
> reenvía el clic del icono; el panel es una ventanita persistente en la página.

### 3.2 Mensajería entre contextos

MAIN → ISOLATED por `window.postMessage` con un esquema fijo (prefijo propio):

```jsonc
// page-hook → content
{ "src": "dragonshot", "type": "hello",  "data": { "hasWebSocket": true, "hasPixi": true } }
{ "src": "dragonshot", "type": "send",   "data": { "opcode": 7, "args": [/* game_shoot */], "t": 1710000000 } }
{ "src": "dragonshot", "type": "recv",   "data": { "opcode": 6, "args": [/* play */],       "t": 1710000000 } }
{ "src": "dragonshot", "type": "aim",    "data": { "ang": 63, "power": 240, "x": 512, "y": 721, "body": 4, "look": 0, "mine": true } }
{ "src": "dragonshot", "type": "turn",   "data": { "turn": 2, "me": 0, "players": [/* … */] } }
```

`content` envía comandos a MAIN (p. ej. pedir un `aim` bajo demanda) con el mismo
canal y `src:"dragonshot-cmd"`.

> **Implementación actual:** los mensajes usan
> `{ source:'dragonshot-ext', type:'snapshot'|'game-shoot'|'play'|'room-state'|'pixi-ready'|'ws-open'|'ws-close' }`
> y el overlay responde con `{ source:'dragonshot-overlay', type:'get-snapshot' }`.
> No se envía ningún frame crudo ni token; solo el disparo parseado, el perfil y
> el snapshot de Pixi.

---

## 4. Fuente del estado

### 4.1 Red (autoridad) — hook de WebSocket

Interceptar `window.WebSocket` en `document_start` (mundo MAIN) **antes** de que el
cliente cree el socket. En producción el cliente es un script inline ofuscado, así
que el hook no debe depender de `1023989.js`. Envolver:

- `WebSocket.prototype.send` → aplicar XOR `0xA5`, separar el primer byte (opcode) y
  parsear el payload textual/JSON-like.
- Recepción: envolver `onmessage`/`addEventListener`; aplicar XOR `0xDB` y parsear
  igual. No asumir `JSON.stringify([opcode, ...])`: el live usa un framing propio.

Opcodes confirmados en vivo (2026-10-20): `game_shoot(7)`, `game_move(6)`,
`play(6)`, `room_state(5)`, `update(11)`, resultado `(13)`. Los opcodes
`started_to_shoot(60)`/`look(61)` no se observaron en esta captura y quedan
pendientes. Ver §1.2 y §5.

### 4.2 Runtime del cliente (tiempo real) — captura de Pixi

`window.PIXI` se expone y luego se borra (`16156-16157`). Por eso, en la extensión
se instala un **accesor** en `window.PIXI` desde `document_start` (mundo MAIN) que:

1. Guarda la referencia en una variable del hook (`__dsPIXI`).
2. Envuelve `PIXI.Application` (y, si existe, `PIXI.Renderer`) para conservar la
   instancia y su `stage`.
3. Deja que `delete window.PIXI` funcione (propiedad `configurable: true`).

**Confirmado en vivo (2026-10-20):** el cliente usa **Pixi.js**. Con el juego en
marcha, `window.PIXI` ya no existe, pero se puede recuperar el `InteractionManager`
depositando un breakpoint en su handler `onMouseOut`/`onMouseMove` y leyendo `this`;
`this.stage` es el `Stage` de Pixi y `stage.interactionManager` es el propio
`InteractionManager`. El `stage` permite recorrer los `PlayerContainer-*` y leer
`x`, `y`, `look`, `body_angle`, `angle.rotation` y `player_info.is_me`.

En la extensión no se usará el breakpoint (es solo una técnica de diagnóstico): el
hook de `document_start` captura Pixi antes de que el cliente lo borre y conserva
la instancia de `Application`/`InteractionManager` para leer el stage en cada frame.

**Riesgo/plan B:** si el recorrido del stage no fuera estable, el ángulo en vivo se
toma del último `game_shoot` (al disparar) y, como último recurso, de visión in-page
sobre el canvas (§11.4).

### 4.3 Identificación del jugador propio y del turno

- **Confirmado en vivo (2026-10-20):** el jugador propio se identifica por
  `PlayerInfo-<nombre>.is_me === true`, y el `PlayerContainer-<nombre>.player_info`
  apunta a ese mismo `PlayerInfo`. No depender de índices del árbol: se añaden y
  quitan joyas, almas (`PlayerContainer-soul`) y efectos.
- El `PlayerContainer` propio expone `x`, `y` (mundo), `look` (0/1), `body_angle`
  (grados) y `angle.rotation` (radianes, ángulo de apuntado). El turno está en
  `PlayerInfo.is_turn`.
- `my_player_info(1)` (observado en live) identifica tu `position`/`my_player_index`;
  `game_start(7)` no se observó en la captura y no debe confundirse con el
  `game_shoot(7)` cliente → servidor.
- `play(6)` incluye `next_turn_number`, la posición del jugador y la del siguiente
  (`message.js:431-451`).
- Solo se dibuja trayectoria cuando `turn == my_player_number` (o en modo
  "observador" con el jugador seleccionado).

---

## 5. Contratos de mensajes

### 5.1 Opcodes relevantes (`src/game/gametypes.js`)

| Cliente → servidor (envía) | Valor live | Uso en la extensión |
|---|---:|---|
| `game_shoot` | **7** | Ángulo y potencia **reales** del disparo. |
| `game_move` | 6 | Movimiento del jugador (posición/look/ángulo). |
| `started_to_shoot` | ⚠ | El jugador empezó a apuntar; no observado. |
| `look` | ⚠ | Cambio de dirección; no observado. |

| Servidor → cliente (recibe) | Valor live | Uso |
|---|---:|---|
| `room_state` | 5 | `max_wind`, mapa, modo, etc. |
| `play` | 6 | Turno, posiciones, viento, clima, thor. |
| `game_start` | ⚠ | No observado en la captura. |
| `update` | 11 | Actualizaciones de estado. |
| `result` | 13 | Resultado de la partida (`won`, scores, chat). |

> **No reutilizar los valores del remake para producción.** En el remake
> `game_shoot` era `1`; en el sitio live es `7`. La dirección importa: el `7`
> observado es cliente → servidor (disparo), no el `game_start` del servidor.

### 5.2 `game_shoot` (cliente → servidor)

`ds.Send(CLIENT_OPCODE.game_shoot, x, y, body, look, ang, power, time, type, extra)`

**Confirmado en la captura live (2026-10-20).** El opcode es **variable** (`0x07`
en server 6; `0x3A` en server 5). El orden de campos coincide con el remake;
`extra` es la secuencia de apuntado/touch, **no** la trayectoria balística.

| Pos | Campo | Significado |
|---|---|---|
| 0 | `x, y` | Posición del jugador (mundo). |
| 2 | `body` | Inclinación del terreno en la posición del jugador (grados). |
| 3 | `look` | `0 = izquierda`, `1 = derecha`. |
| 4 | `ang` | Ángulo del cañón (grados, 0–180 en pantalla Y-abajo). |
| 5 | `power` | Potencia de la barra (**0–400**; `#powerBar.style.width`, §16.11). |
| 6 | `time` | Tiempo de carga (para `time*10` ms de retardo, `game.js:500`). |
| 7 | `selectedShot` | Tipo de disparo. |
| 8 | `extra` | Datos extra (touch/animación). |

### 5.3 `play` (servidor → cliente)  — `message.js:431-451`

```
[ play,
  [ next_turn_number,
    player.game_position,
    player.x,
    player.y,
    player.look,
    player.added_delay,
    nextplayer.position,
    chat,
    thor.x, thor.y, thor.angle, thor.damage,
    weather.list,
    wind_power,
    wind_angle + 180,
    data,             // datos del disparo
    player.win_gold,
    weather.active,
    next_weather_pos ] ]
```

Campos que usa la extensión: `player.x/y`, `player.look`, `next_turn_number`,
`nextplayer.position`, y **`wind_power`/`wind_angle`** (leídos; ignorados por AIRE=0).

Muestra live (2026-10-20):

```
[2, 0, 1273, 164, 0, 500, 1, [], 1076, -334, -2, 0, -1, 14, 2, [...]]
```

- `wind_power = 14` (índice 13).
- `wind_angle + 180 = 2` (índice 14).
- En la partida capturada `wind_power` varió entre 13 y 15.
- El mapeo coincide con el contrato de arriba; **para v1 se ignora** y la física usa AIRE=0.

### 5.4 `room_state` — `message.js:39-...`

Incluye `max_wind` (índice 9), `game_mode`, `map`, `turn_time`, etc.

---

## 6. Modelo de coordenadas

Se distinguen cuatro espacios (todos presentes en el cliente):

| Espacio | Origen | Uso |
|---|---|---|
| **Mundo/escena** | `(0,0)` del mapa | `player.x/y`, `x0/y0` del disparo, física. |
| **Viewport** | contenedor `#gameui` (CSS px locales) | El overlay se ancla aquí. |
| **Canvas/Pixi** | canvas del render (modo Pixi/3D) | `worldTransform` de la cámara. |
| **Pantalla** | página del navegador | Solo para eventos; **no** se usa para la física. |

**Confirmado en vivo (2026-10-20):** canvas de **1257×943**. `zoom = 1.5716667`
(`root.children[0].scale.x`) y `camera` es la posición del contenedor de mundo
(padre de los `PlayerContainer-*`), `(64.57, 194.09)` en la muestra. La
transformación es:

`screen = zoom * (world + camera)`

Verificado con `PlayerContainer.worldTransform` (`tx = 1347.81`, `ty = 490.50` para
`world = (793, 118)`). La inversa es `world = screen / zoom - camera`. La cámara se
mueve; hay que leerla en cada frame, no fijarla.

**Estrategia de alineación del overlay:**

1. Obtener el rectángulo de `#gameui` (`getBoundingClientRect`) y montar un
   `<canvas>` de posición absoluta exactamente encima (mismo tamaño CSS).
2. Dibujar en el espacio del viewport. Para convertir **mundo → viewport** se usa la
   transformación de la cámara del juego (`camera.MoveBy`, offsets de fondo
   `bg_offset_x/y`, `bg_aspect_ratio_x/y`; `1023989.js:43427-43455`).
3. La cámara se obtiene del runtime capturado (§4.2) o se reconstruye desde el
   estado del turno; si el desplazamiento no está disponible, se **invalida la
   sincronización** en lugar de asumir un offset (no inventar).

**Regla anti-drift:** nunca se fija un offset constante de pantalla. El origen y el
destino se expresan en **coordenadas de mundo** y se reproyectan en cada frame con la
transformación vigente.

---

## 7. Física con AIRE = 0 (modelo repo + calibración live)

El servidor del **remake** construye el disparo en `src/game/game.js:436-547` y
simula en `src/game/lib/shoot.js`. Esas fórmulas y constantes son la referencia
`↩ repo`; **no son las de producción**. Para producción usar §7.4. Con
**AIRE = 0** (sin viento) la trayectoria es balística pura en ambos modelos.

### 7.1 Transformaciones de entrada (servidor)

```
power_srv = parseInt(client_power * 234 / 100)          // game.js:440  (escala 2.34)

ang0 = (look == 0) ? (180 - client_ang) : client_ang    // game.js:450  (espejo izquierda)
ang  = ang0 - body                                      // game.js:470

dis  = (look == 0)                                     // game.js:452-468
         ? (mobile es ADUKA o NAK ?  26 : -11)
         : (mobile es ADUKA o NAK ? -26 :  11)
dy   = (mobile es ADUKA o NAK) ? 31 : 28                // game.js:473-478

origen = rotar({ x: x + dis, y: y - dy }, { x, y }, body)  // game.js:479-486
```

> Los offsets `dis`/`dy` dependen del móvil; se leen de `my_player_info`/`game_start`
> o de la tabla `Types.MOBILES` del repo. Si no se conocen, se documenta la
> incertidumbre (no se inventan).

### 7.2 Simulación de la trayectoria (shoot.js)

Vectores iniciales (`shoot.js:374-379`):

```
vx = cos(ang°) * power_srv
vy = -sin(ang°) * power_srv
```

Posición en función del tiempo (`shoot.js:174-180`), con `a = t / 485`:

```
x(t) = origen.x + vx * a + (friccion * a²) / 2
y(t) = origen.y + vy * a + (weight   * a²) / 2
```

Con **AIRE = 0** → `friccion = 0`, `weight = 398` (gravedad del juego, `game.js:509`):

```
x(t) = origen.x + cos(ang°) * power_srv * (t/485)
y(t) = origen.y - sin(ang°) * power_srv * (t/485) + 398 * (t/485)² / 2
```

> Nota: `wind` en el juego **no** es un acelerador aparte; `setWindEfect()`
> (`shoot.js:263-284`) modifica `friccion` y `weight`. Con `wind_power = 0` ambos
> quedan en sus valores base, por lo que **AIRE = 0 ⇒ `friccion = 0`**.

> **Producción:** las constantes `398 / 2.34 / 485` y los offsets `dis / dy` son
> del **remake**. La captura live del 2026-10-20 midió otras constantes y otra
> unidad de tiempo; ver §7.4. **No mezclar ambos modelos** en el mismo perfil.

### 7.3 Extensibilidad del aire

`core/physics.js` expondrá una interfaz `DragModel` con una única implementación
activa `NO_AIR` (AIRE = 0). Un modelo futuro (viento/arrastre) implementaría el
equivalente de `setWindEfect` sin tocar el resto del sistema. **La UI mantiene AIRE
fijo en 0** y no se implementa arrastre en esta fase.

> **Decisión v1:** el viento se maneja como **0**. Aunque `play` trae
> `wind_power`/`wind_angle` y se puedan leer para diagnóstico, `SIMULAR`, el
> asistente de potencia y el overlay usan **AIRE = 0**. No se corrige por viento en
> esta primera versión del app.

### 7.4 Calibración live (producción, 2026-10-20)

La captura del **servidor 5, sala 48**, con **Armor** y **Metamine**, contiene 8
disparos. El viento no se pudo mantener en 0: en los dos primeros turnos el
`wind_power` del siguiente turno era `0`; después subió a `50` y bajó
`49, 48, …, 42`. Los dos primeros disparos tienen `s[4]=0` (viento 0 real); el
resto tiene viento. Aun así, el mensaje `play` trae el **resultado físico del
servidor**, que permite calibrar sin depender de una sala sin viento.

En `play` (índice 15), cada resultado incluye:

```jsonc
{
  "s": [x0, y0, ang_abs, v0, windX, vertAccel],
  "i": 0, "e": 0,
  "time": 2050,                 // tiempo de vuelo en ms
  "h": [664, 31, 60, 40],       // rectángulo de impacto
  "d": [{ "n": 1, "hp": 900 }]  // daño (si hubo impacto)
}
```

Constantes medidas (8 disparos; `power` es la potencia 0–400 del cliente):

| Magnitud | Live (producción) | Remake |
|---|---:|---:|
| Escala de potencia `v0/power` | **2.269** (fallback; la sala 44 midió **2.364**) | 2.34 |
| Gravedad base con viento 0 | **390** (fallback; la sala 44 midió **402**) | 398 |
| Unidad de tiempo | **segundos** (`time/1000`) | `t/485` |
| Origen Armor `(a, c)` local | **(20.45, 25.83)**, medido con `look=1` | `dis/dy` del repo |

> **Auto-calibración (2026-10-20):** `2.269 / 390` es el **valor inicial**, no una
> constante universal. La captura posterior en la sala 44, también `NORMAL`, mapa
> 9 y `max_wind=0`, dio `v0/power = 2.364` y `s[5] = 402` en cuatro disparos
> `look=0/body=0`. Ni `room_state` ni `game_start` declaran física, así que la
> extensión recalibra desde `play` (§16.12).

El ángulo absoluto es `ang_abs = ang - body` (después del espejo por `look`), y el
origen se obtiene rotando el offset local por `body`. El espejo de `look` se
aplica al offset **local** antes de rotar (igual que `game.js`, que rota
`{x: x + dis, y: y − dy}`), no al `dx` ya rotado:

```text
θ  = radians(body)
sx = (look == 0) ? −1 : +1
dx = sx·20.45·cos(θ) + 25.83·sin(θ)
dy = sx·20.45·sin(θ) − 25.83·cos(θ)
x0 = player.x + dx
y0 = player.y + dy
```

Con `body = 0` esto da `(±20.45, −25.83)`; espejar solo `dx` después de rotar
dejaba el origen del lado equivocado (y la altura cambiada) con `body ≠ 0`.

> Los 8 disparos de la calibración se hicieron con `look=1` (mirando a la
> derecha). La captura posterior de la sala 44 confirmó el espejo `look=0` con
> cuatro disparos: el servidor envió `s=[911,−13,153,461,0,402]` para un jugador
> en `(931,13)`, que corresponde al offset Armor espejado `(−20.45,−25.83)`
> (§16.12).

Trayectoria live (coordenadas de pantalla, Y hacia abajo), con `t = time/1000`:

```text
x(t) = x0 + v0·cos(ang_abs)·t + 0.5·windX·t²
y(t) = y0 − v0·sin(ang_abs)·t + 0.5·vertAccel·t²
```

- **AIRE = 0 (v1):** `windX = 0`, `vertAccel = perfil.gravity` (fallback `390`,
  auto-calibrado desde `s[5]` cuando `s[4] === 0`; §16.12).
- `s[4]` y `s[5]` son la aceleración horizontal del viento y la aceleración
  vertical efectiva (gravedad + componente vertical del viento). Se leen solo
  para diagnóstico; v1 no los usa.
- `game_shoot.time` es el tiempo de carga del disparo; **no** es el tiempo de
  vuelo. El tiempo de vuelo es `play[15].time`.
- `s` llega **después** del disparo: sirve para calibrar y verificar, no para
  predecir antes de disparar. Para `SIMULAR` se usan las constantes y el estado
  actual.

Verificación: con `s` y `time` se predice el impacto y se compara con `h`. En los
8 disparos la diferencia fue de aproximadamente 1–4 px (el rectángulo de impacto
tiene 60×40 px). En **esa captura**, los dos disparos con viento 0 confirmaron
la gravedad `390` y la escala `2.269`; los otros seis validaron la fórmula con
viento. La captura posterior de la sala 44 dio `2.364/402`, por eso esos valores
quedan como fallback y el perfil se auto-calibra (§16.12). Detalle en §16.9.

> **Opcodes:** el disparo no tiene el mismo opcode en todos los servidores. En el
> servidor 6 se observó `0x07`; en el servidor 5, `0x3A` (ASCII `:`). El hook no
> debe hardcodear `0x07`: debe detectar el disparo por la forma del payload o usar
> un perfil por servidor.

---

## 8. `SIMULAR` (diseño funcional)

`SIMULAR` calcula y dibuja la trayectoria **desde el origen real** con el **ángulo
real** y, si se desea, la **potencia actual**, usando §7 con AIRE = 0.

- **Origen:** `origen` del cañón (§7.1) desde `player.x/y`, `body`, `look`, móvil.
  Por defecto es automático; el usuario puede fijarlo con `Ctrl + clic derecho`
  (§8.2).
- **Ángulo:** `PlayerContainer.angle.worldTransform` en vivo. La columna `(a, b)`
  es el eje `+x` del cañón ya transformado por `body` y por los espejos de la
  cadena de padres, así que `ang_abs = −atan2(b, a)` en grados matemáticos (Y
  hacia arriba); no se resta `body` ni se vuelve a espejar por `look`.
  `angle.rotation` queda solo como respaldo si no hay `worldTransform`. Con
  `look=0` el contenedor está espejado en vertical, así que la lectura se
  invierte (`real = −raw`); con `look=1` se usa tal cual
  (`alignAimToLook`, §16.15).
- **Potencia:** `#powerBar.style.width` (0–400) mientras se carga; tras el disparo,
  `#last_power_mark.style.left − 247`. Si no hay barra activa, barrido/rango si el
  usuario quiere ver varias potencias. Ver §16.11.
- **Dibujo:** proyección mundo → viewport (§6), como polilínea/puntos sobre el overlay.
  La trayectoria solución se calcula con el destino marcado por el usuario (§8.2).
- **Fin de trayectoria:** hasta colisión con el terreno del mapa (`IsPixel`/`GetUnder`
  del cliente) o un `t` máximo.

Integración de datos (resumen): identificar jugador/turno → obtener ángulo →
actualizar campo `ÁNGULO` → seguir desplazamiento de escena (cámara) → `SIMULAR` usa
origen/ángulo vigentes → dibujar con **AIRE = 0**.

### 8.1 Asistente de potencia y ángulo ("¿hasta dónde disparo?")

La barra del juego solo muestra la potencia **actual**; el usuario no sabe cuánta
necesita para dar al rival. El asistente resuelve la **balística inversa** (§7 con
AIRE = 0) y muestra el dato explícito.

Definiciones live (producción, §7.4): `N = 1000` (ms → s), `θ` = ángulo efectivo
(espejo por `look` y `body` ya aplicados), `W = perfil.gravity` (fallback `390`),
`v0 = P_cliente × perfil.powerScale` (fallback `2.269`), `Δx = xt − x0`,
`Δy = yt − y0`, `τ = t / N` (segundos).

**Potencia necesaria (con el ángulo actual):**

```
K         = Δx / cos(θ)
τ         = sqrt( 2 * (Δy + sin(θ) * K) / W )
v0        = K / τ
P_cliente = v0 / perfil.powerScale   // 0..400, la unidad de la barra
```

**Ángulo necesario (con la potencia actual):** con `v0 = P_cliente × perfil.powerScale`
y `u = Δx / v0`, se resuelve `s = tan(θ)` en
`(W·u²/2)·s² − (v0·u)·s + (W·u²/2 − Δy) = 0`, eligiendo la raíz de ángulo alto
según `look`.

> Para el **remake**, sustituir por `N = 485`, `W = 398` y
> `v0 = P_cliente × 2.34`. No mezclar perfiles.

**Presentación:**

- Dato principal en el panel: **`POTENCIA NECESARIA = 181 / 400`** (y/o
  `ÁNGULO NECESARIO` para una potencia dada).
- **Marca** en la barra de potencia: *actual* vs. *necesaria*.
- **Trayectoria solución** en verde, que termina exactamente en el objetivo; la actual
  se dibuja en cyan (se ve si "se pasa" o "se queda corta").
- **Abanico** tenue de trayectorias para referencia de alcance.
- Si no hay solución real (discriminante negativo o fuera de `[0, 400]`), mostrar
  **"inalcanzable"** en lugar de un número inventado.

El objetivo lo fija el usuario con **Seleccionar destino** o `Ctrl + clic` (§8.2);
el asistente indica la potencia (o el ángulo) para dar en él.

### 8.2 Selección interactiva de origen y destino (botón/capa o `Ctrl + clic`)

> **Implementación actual:** `extension/src/content.js` implementa la máquina de
> estados `origin`/`destination`, el snap al cañón, el bloqueo de `contextmenu`,
> `Esc`, resize y la marca de potencia. El destino es **fijo** en coordenadas de
> mundo; el modo `entity` (seguir al rival) queda pendiente. El overlay es
> full-viewport con Shadow DOM y proyecta con la misma cámara/zoom del canvas.
> El botón **Seleccionar destino** arma una capa visual con `pointer-events:none`:
> los `mousemove` sí llegan al canvas para que el cañón apunte al cursor, y el
> clic final se intercepta en `window` (captura) antes de que el juego lo reciba.
> Así no se bloquea el apuntado y el clic no dispara. El page-hook recibe
> `selection-mode` para ignorar ese `pointerdown/pointerup` como muestra de
> disparo. `Ctrl + clic` se conserva como atajo.

Regla base: **todo se guarda en coordenadas de mundo**. El clic solo se usa para
capturar una posición de pantalla; nunca se fija un offset de pantalla. El origen y
el destino se reproyectan en cada frame con la cámara/zoom vigentes (§6).

#### Estados

| Estado | Valores | Comportamiento |
|---|---|---|
| `origin` | `auto` / `own-cannon` | `auto` usa el cañón del jugador propio. `own-cannon` también se recalcula cada frame con `originFor`; **no se congela** en el punto del clic. |
| `destination` | `null` / `fixed` / `entity` | `fixed` es un punto de mundo; `entity` sigue al rival si su hitbox está disponible. |
| `selectionStep` | `origin` / `destination` | Empieza en `origin`; después del primer clic pasa a `destination`. |

#### Gesto

- **Botón Seleccionar destino:** arma una capa visual (sin capturar el ratón).
  Mover el ratón apunta el cañón en el juego; el siguiente clic izquierdo dentro
  del canvas fija `destination = world(click)` y desarma la capa. El clic se
  intercepta en captura y no llega al canvas. `Esc`, clic derecho o clic fuera
  del canvas cancela.
- Atajo `Ctrl + clic izquierdo/derecho` sobre `#gameui`:
  1. Si `selectionStep === 'origin'` **o** el clic cae sobre el móvil propio:
     fija `origin = own-cannon` y redirige el marcador a la boca del cañón,
     aunque el clic haya sido lejos. Pasa a `selectionStep = 'destination'`.
  2. Si `selectionStep === 'destination'`: fija `destination = world(click)`.
     Los siguientes `Ctrl + clic` **mueven el destino**.
  3. Si el clic cae sobre el rival: `destination = { kind: 'entity', ref: rival }`
     y se recalcula su centro cada frame. Si no se puede leer el hitbox, se usa un
     punto fijo y se muestra la incertidumbre.
- `Ctrl + clic` sobre el móvil propio, en cualquier momento, vuelve a fijar
  el origen en el cañón.
- `Esc` cancela el modo de selección o limpia el destino.
- `Shift + Ctrl + clic` fuerza el modo destino sin tocar el origen (atajo
  opcional para cuando el origen ya está fijado).
- El clic derecho **sin Ctrl** no se intercepta: sigue siendo del juego/navegador.

#### Captura del evento

- Escuchar `pointerdown` en fase de captura. En modo selección, interceptar
  `button === 0` para fijar el destino; `button === 2` cancela. Sin modo
  selección, el atajo es `button === 2 && ctrlKey`.
- Llamar `preventDefault()` y `stopImmediatePropagation()` solo en esos casos.
  El page-hook que observa `pointerup` ignora el gesto mientras el modo
  selección está activo.
- No usar `click`: el botón derecho no dispara `click`.
- Ignorar el evento si no hay jugador propio (`player_info.is_me`) o si la cámara
  no está sincronizada.
- Convertir pantalla → mundo:

```js
const r = gameui.getBoundingClientRect();
const sx = (event.clientX - r.left) * (canvas.width / r.width);
const sy = (event.clientY - r.top) * (canvas.height / r.height);
const world = { x: sx / zoom - camera.x, y: sy / zoom - camera.y };
```

> Si se usa `stage.toLocal`, hay que aplicar la misma transformación de cámara del
> juego; no mezclar coordenadas de página con coordenadas de canvas.

#### Cálculo y dibujo

1. `origin = originFor(me, me.body, me.look, me.mobile)`.
2. `destination` en mundo.
3. `θ` efectivo con `look`/`body` (§7.4).
4. Validar que el destino esté en el sentido de `look`. Si está detrás, mostrar
   `OBJETIVO DETRÁS` y no calcular potencia.
5. Resolver la potencia con §8.1. Si no hay solución o `P ∉ [0, 400]`, mostrar
   `INALCANZABLE` y no dibujar la trayectoria solución.
6. Dibujar:
   - marcador de **origen** en la boca del cañón;
   - marcador de **destino**;
   - trayectoria **solución** en verde con la potencia calculada;
   - trayectoria **actual** en cyan si se conoce la potencia;
   - marca de potencia necesaria en la barra (§8.2.1).
7. Recalcular en `requestAnimationFrame` o por eventos cuando cambie el ángulo,
   la potencia, la cámara, el tamaño del viewport, el turno o la posición del rival.

#### 8.2.1 Barra de potencia

La barra del juego mide 400 px de potencia. La marca de la **última** potencia usa
`#last_power_mark.style.left = 247 + P` (§16.11). Para la potencia **necesaria**:

```text
P_nec = v0 / perfil.powerScale        // §8.1, 0..400
x_rel = (P_nec / 400) * anchoBarra
```

- Medir `#powerMarkArea` (o `#powerMarks`) con `getBoundingClientRect()`: esas
  áreas representan 0..400 y ya incluyen el zoom. `#powerBar` solo mide la
  potencia actual y se usa como último recurso.
- Dibujar la marca en el **overlay**, no escribiendo en el DOM del juego. Usar
  contorno oscuro, línea sólida y aguja superior para que se distinga sobre el
  fondo; la etiqueta se recorta al viewport.
- Mostrar `POTENCIA NECESARIA = 181 / 400` y la marca sobre la barra.
- Si el jugador está cargando, dibujar también la marca actual
  (`#powerBar.style.width`).
- **No automatizar el disparo en v1:** la extensión solo marca hasta dónde llegar;
  el usuario suelta el clic. Automatizar la barra o el disparo queda fuera de
  alcance y requiere revisar ToS.

#### 8.2.2 Ciclo de vida

- **Cambio de turno:** si no es el turno propio, ocultar la selección; al volver,
  conservar el destino si sigue siendo válido o limpiarlo.
- **Cambio de sala/reconexión:** `destination = null`, `selectionStep = 'origin'`,
  `origin = auto`, `resetPhysics()` en la auto-calibración.
- **Movimiento del jugador:** el origen se recalcula; un destino `fixed` no se
  mueve; un destino `entity` sigue al rival.
- **Pérdida de cámara/zoom/jugador:** no dibujar y mostrar `SIN SINCRONIZACIÓN`.

#### 8.2.3 Pruebas manuales específicas

| # | Escenario | Criterio |
|---|---|---|
| G | Primer `Ctrl + clic derecho` lejos del cañón | El origen queda en la boca del cañón propio, no en el clic. |
| H | Segundo `Ctrl + clic derecho` | Aparecen destino, trayectoria solución y marca de potencia. |
| I | Mover el destino | Se recalcula potencia y trayectoria sin reiniciar. |
| J | `Ctrl + clic derecho` sobre el móvil propio | Vuelve a fijar el origen en el cañón. |
| K | Destino detrás o fuera de alcance | Muestra `OBJETIVO DETRÁS` / `INALCANZABLE`; no inventa potencia. |
| L | Resize, zoom o fullscreen | Origen/destino siguen anclados al mundo, sin drift. |
| M | Destino a la derecha con `look=1` | La trayectoria empieza a la derecha y hay potencia válida. |
| N | Destino a la izquierda con `look=0` | El origen se espeja a la izquierda y la trayectoria también. |
| O | Mover el ratón en modo selección | El cañón del juego gira; el clic final no dispara ni crea muestra. |

---

## 9. Estado, sincronización y estabilidad

- **Staleness:** el estado lleva marca de tiempo; si el ángulo/origen supera un
  umbral sin refresco, se muestra "no disponible" y no se dibuja.
- **Anti-jitter:** mediana corta (ventana ~5) sobre el ángulo; histéresis para no
  alternar entre "disponible"/"no disponible" en el borde.
- **Turnos ajenos:** nunca se usa el ángulo de otro jugador; se filtra por
  `my_player_index`/`is_me`.
- **Revalidación:** al cambiar de turno, mapa o tamaño del viewport, se recomputa la
  transformación; si falta el dato, se invalida la sincronización.
- **Rendimiento:** el dibujo del overlay se hace en `requestAnimationFrame`; el
  estado se actualiza por eventos (red/hook), no por polling pesado.

---

## 10. Plan de implementación por fases

**Estado 2026-10-20 (actualizado):** la verificación live de **protocolo, opcodes,
física y Pixi** está hecha (§1.2, §7.4, §16.9, §16.10). La extensión MV3 ya está
implementada en `extension/` (manifest, hook MAIN, overlay ISOLATED, selección
origen/destino y calibración integrada). Se validó en navegador con Pixi/WebSocket
simulados y con la extensión cargada en `dragonbound.net`; falta la prueba live de
los escenarios G–O y del ciclo de sala/reconexión.

**Fase 0 — Andamiaje** *(implementado)*
- `extension/manifest.json` (MV3), iconos, permisos.
- Inyección MAIN + ISOLATED, canal `postMessage` documentado.
- Harness de integración con WebSocket/Pixi simulados (temporal, fuera del repo).

**Fase 0.5 — Verificación contra el sitio real (parcial, 2026-10-20)**
- **Hecho:** URL del WS, XOR/framing, `play`/`room_state`/`update`/`result`, campos del
  disparo, física live de referencia (`2.269/390`, origen Armor), `room_state` de
  salas 48/44, `look=0`, potencia ↔ `game_shoot.power`, Pixi y localización del
  jugador propio (`player_info.is_me`).
- **Hecho:** auto-calibración `powerScale`/`gravity` con `play.s[3]/s[5]`,
  14/14 pruebas.
- **Pendiente:** CSP/SRI/ToS, offsets de otros móviles, `game_start` live,
  prueba live dentro de una partida y validación de resize/fullscreen.
- El perfil `dragonbound-net` ya lleva las constantes de fallback y la
  auto-calibración; falta completar el resto de §16.3 y marcar `calibrated:true`.

**Fase 1 — Lectura de red** *(implementado en MVP)*
- Hook `WebSocket` send/receive; decodificador de opcodes (§5).
- Estado de turno/jugador desde `play`, `room_state`, `game_start` (parcial:
  `game_start` no se usa todavía).

**Fase 2 — Lectura de runtime (ángulo vivo)** *(implementado en MVP)*
- Hook de `document_start` para capturar `window.PIXI` antes de que se borre;
  incluye fallback por `Renderer.render` si la app se creó antes del hook.
- Localizar `PlayerContainer-*` por `_name` y `player_info.is_me`; leer
  `x`, `y`, `look`, `body_angle`, `angle.rotation` (y `is_turn`).
- Leer `zoom` y `camera` del contenedor de mundo para mundo→viewport.
- Fallback a `game_shoot` y marca de "no disponible".

**Fase 3 — Overlay y coordenadas** *(implementado en MVP)*
- Overlay full-viewport en Shadow DOM; conversión mundo→viewport con la cámara.
- Dibujo de origen/destino, trayectoria solución/actual y marca de potencia.

**Fase 4 — Física AIRE=0 y asistente** *(implementado en MVP)*
- Balística `AIRE=0` dentro de `content.js` (`solvePower`, `drawTrajectory`).
- Panel con potencia necesaria y ángulo efectivo; sin botón `SIMULAR` todavía.
- La marca de potencia es visual; no se escribe en la barra ni se dispara.

**Fase 5 — Pruebas y endurecimiento**
- Unitarias + integración con harness mock; escenarios A–F (§12).
- **Hecho:** 14/14 auto-calibración, 38/38 integración hook→overlay→reset con
  destinos a izquierda/derecha, `look=0/1` y marca de potencia.
- **Pendiente:** escenarios G–O live, resize/fullscreen, CSP/SRI/ToS.

**Árbol de archivos actual:**

```
extension/
  manifest.json
  icons/icon16.png|icon48.png|icon128.png
  src/page-hook.js
  src/content.js
  src/auto-calibration.js
  src/background.js
  test/auto-calibration.test.html|js
  test/integration.test.html
  test/fixtures/captured-shots.js
  README.md
```

---

## 11. Pruebas

### 11.1 Unitarias (JS)
- `auto-calibration.js`: 14 pruebas con payloads reales en
  `extension/test/auto-calibration.test.html` (fallback, `look=0`, `look=1`,
  viento, reset, origen opcional, espejo con `body` y `onChange`). Resultado
  actual: **14/14**.
- `page-hook.js`: decodificación XOR de `game_shoot`/`play`/`room_state` y
  publicación de eventos (probado con frames simulados).
- `content.js`: solución de potencia/trayectoria, proyección mundo→cliente,
  selección por botón/capa y `Ctrl + clic`, `Esc`, reset por sala, acoplamiento
  del panel fuera del canvas y mostrar/ocultar desde el icono (probado con
  Pixi/WebSocket simulados).

### 11.2 Integración
- **Harness mock:** página con WebSocket falso y un `PIXI.Application` falso
  (canvas, cámara, `player_info.is_me`). Valida hook → auto-calibración →
  snapshot → overlay → clic → potencia → `room_state`.
- **Resultado:** **41/41** (ampliado en §16.13, §16.14 y §16.15); calibración `powerScale=2.3641`, `gravity=402`; panel
  `POTENCIA NECESARIA=166/400` para el destino de prueba; reset por `room_state`;
  selección por capa sin bloquear el ratón, destinos a izquierda/derecha con
  `look=0/1`, marcador de potencia y acoplamiento del panel fuera del canvas
  verificados.
- La extensión también se cargó como unpacked en Brave y se verificó que los
  content scripts MAIN/ISOLATED montan el hook y el host del overlay en
  `dragonbound.net` (pantalla de login, sin partida).

### 11.3 End-to-end manual (A–F, pendientes)

> El MVP no tiene botón `SIMULAR`: la trayectoria se dibuja automáticamente al
> fijar el destino. Los escenarios C/D se reinterpretan como “la trayectoria
> respeta `look`/`body`”. Las filas A–F siguen pendientes de una partida live.

| # | Escenario | Criterio |
|---|---|---|
| A | Desplazamiento de escena | Origen/destino siguen al escenario al mover la cámara (sin drift). |
| B | Cambios de ángulo | `ÁNGULO` sigue el `player.ang` real; cuadrantes y signos correctos. |
| C | Dirección del disparo | `SIMULAR` respeta `look` (espejo 180−ang) y `body`. |
| D | Integración `SIMULAR` | Usa origen/ángulo vigentes; se actualiza sin valores viejos. |
| E | Condición de aire | `AIRE = 0` fijo; el viento leído se ignora; trayectoria balística pura. |
| F | Estabilidad/rendimiento | Sin jitter ni parpadeo; overlay fluido; estado "no disponible" correcto. |

> Los escenarios **G–O** de §8.2.3 cubren la selección `Ctrl + clic derecho`, el
> redireccionamiento al cañón, la marca de potencia y los destinos a
> izquierda/derecha con `look=0/1`.

### 11.4 Fallback de visión (opcional)
Si el hook de Pixi no fuera estable en alguna versión, se puede añadir detección
in-page sobre el canvas del juego (mismo principio que la app Java, pero sin captura
de escritorio ni DPI). Queda **fuera de la fase 1–5** y se documenta como plan B.

---

## 12. Limitaciones y riesgos

1. **Dependencia de la estructura interna** (`PlayerContainer.angle.rotation`, stage
   de Pixi) y del protocolo. Mitigación: hook de red como fuente primaria + marca de
   "no disponible"; tests de contrato.
2. **Minificación**: el bundle no tiene API pública; los nombres pueden cambiar entre
   builds. Mitigación: aislar la lectura en `page-hook.js` y detectar versión.
3. **Offsets de cañón** (`dis`, `dy`) dependen del móvil. Mitigación: leer el móvil de
   `my_player_info`/`Types.MOBILES`; si falta, reportar incertidumbre.
4. **Modo 2D vs. Pixi/3D**: objetos distintos (`CPlayer` vs `Player2`). Mitigación:
   cubrir ambos en la lectura.
5. **Permisos**: `host_permissions` para el origen donde se sirve el juego
   (local/dominio). No se piden permisos de más.
6. **Términos de uso**: verificar que automatizar la lectura/asistencia cumpla las
   normas del servidor donde se juegue.
7. **Sincronización de cámara**: si no se obtiene la transformación, se invalida
   (no se asume offset).

---

## 13. Qué se reutiliza del proyecto Java

| Elemento | Destino |
|---|---|
| Fórmulas balísticas y modelo `DragModel.NO_AIR` | Se **portan** a `core/physics.js`. |
| Enfoque de tests A–F | Se **adaptan** a la extensión. |
| Captura `Robot`, `WindowTracker`, JNA, DPI, exclusiones | **Se descartan** (no aplican al navegador). |
| Visión (Hough/template) | **Opcional** como plan B in-page (§11.4). |

---

## 14. Riesgos abiertos y decisiones pendientes

- **Verificación live (2026-10-20):** protocolo, opcodes, física y Pixi están
  confirmados/calibrados contra `https://dragonbound.net/` (§1.2, §7.4, §16.9 y
  §16.10); **las restricciones (CSP/SRI/ToS)** siguen pendientes.
- `window.PIXI` es capturable y el jugador propio se localiza por
  `player_info.is_me`; la correlación de ángulo con `look=0`/`body=0` y la
  potencia ya están validadas. Queda integrar el módulo en el hook/overlay.
- Definir el modo "observador" (dibujar para el jugador en turno, no solo el propio).
- Decidir empaquetado: solo extensión, o extensión + app Java como respaldo.

---

## 15. Estado del proyecto de escritorio (respaldo)

La app Java (`src/…`, `build.cmd`, `run.cmd`, tests, `README.md`) permanece intacta y
funcional como **respaldo** hasta que la extensión se valide contra el juego real. No
se elimina nada en este documento.

---

## 16. Verificación contra el sitio real (`https://dragonbound.net/`)

### 16.1 Por qué es necesaria

El análisis original de §1–§8 se hizo sobre el repositorio
`DragonBoundR/DragonBound`, que es un **remake** ("DragonBoundRe — Remake of the
original DragonBound game"). La captura live del **2026-10-20** verificó el
transporte, los opcodes, la física y el runtime Pixi contra producción (§1.2, §7.4,
§16.8, §16.9 y §16.10). `https://dragonbound.net/` es el **juego en producción**. No hay garantía de que remake y producción compartan:

- versión/estructura del cliente (bundle, motor, nombres internos),
- opcodes del protocolo y forma de los mensajes,
- constantes físicas (el remake usa `398/2.34/485`; producción parte de
  `390/2.269/segundos` como fallback y se auto-calibra desde `play`, §7.4 y
  §16.12),
- transporte (puerto, `ws://` vs. `wss://`, framing, token/ruta).

Por tanto, **todo lo deducido debe confirmarse** contra el sitio real antes de
considerar el diseño válido para producción. Lo deducido del remake queda marcado
`↩ repo`; lo pendiente en producción, `⚠ producción`.

### 16.2 Estado actual (verificación parcial)

- El sitio se abrió en un **navegador real** (Brave) y se capturó una partida 1 vs 1
  en el servidor 6 (`Bunge`) el **2026-10-20**.
- El **403** anterior era de `curl`/red automatizada; el navegador real cargó el
  cliente sin ese bloqueo.
- Confirmado: URL del WebSocket, XOR de aplicación, framing, `play(6)`,
  `room_state(5)`, `update(11)`, resultado `(13)`.
- Disparo confirmado, pero con **opcode variable**: `0x07` en servidor 6 y `0x3A`
  en servidor 5 (2026-10-20). No hardcodear; detectar por payload/perfil.
- Física live **de referencia** con 8 disparos (servidor 5, Armor/Metamine):
  `v0 = 2.269·power`, `gravity = 390`, tiempo en segundos y origen Armor
  `(20.45, -25.83)` rotado por `body` (§7.4, §16.9). La sala 44 midió
  `2.364/402`; el perfil se auto-calibra desde `play` (§16.12).
- **Pixi confirmado** y jugador propio localizado en el stage (2026-10-20):
  `window.PIXI` se borra, pero el `InteractionManager`/`Stage` se recuperó en vivo;
  el jugador propio es `PlayerInfo-*.is_me === true` dentro de `PlayerContainer-*`.
  Ver §16.10.
- Pendiente: CSP/SRI, términos de uso y validación de la física dentro de la
  extensión.

### 16.3 Qué hay que verificar (checklist)

Ejecutar desde un navegador con acceso real (sesión normal) con DevTools abierto:

1. **Cliente / motor**
   - ¿Es Pixi.js? Buscar `PIXI`, `canvas`, `getContext`, `worldTransform`, `requestAnimationFrame`.
   - ¿Un bundle único? Anotar ruta y tamaño (comparar con `1023989.js`).
     **Live 2026-10-20:** no hay `1023989.js`; el HTML carga un script inline ofuscado (~202 KB). **Pixi.js confirmado** (§16.10).
   - **Confirmado:** canvas HTML5 con Pixi.js y contexto WebGL (listeners `webglcontextlost`/`restored`); no es SWF/Flash.
2. **WebSocket**
   - Network → WS → anotar la **URL exacta** (esquema `ws`/`wss`, host, puerto, ruta/token).
     **Confirmado:** `wss://s3qu3nc3dis0rdred.dragon.ws/6` (servidor 6, `Bunge`).
   - Capturar varios **frames** enviados y recibidos y guardarlos como evidencia.
     **Hecho:** 148 frames; XOR `0xA5` C→S y `0xDB` S→C.
3. **Opcodes / contratos**
   - ¿Los mensajes son `[opcode, ...args]` serializados en JSON?
     **No exactamente:** primer byte de opcode + payload textual/JSON-like.
   - Confirmar el opcode de disparo y sus campos (ángulo, potencia, `look`, `body`).
     **Campos confirmados:** `x,y,body,look,ang,power,time,selectedShot,extra`.
     **Opcode variable:** `0x07` en servidor 6; `0x3A` en servidor 5 (2026-10-20).
   - Confirmar el mensaje de turno y sus campos (posiciones, viento).
     **Confirmado:** `play = 0x06`; `wind_power` en índice 13, ángulo en índice 14.
4. **Constantes de física**
   - Buscar `398`, `234`, `485` (o equivalentes) en el bundle.
   - **Validación empírica live (2026-10-20):** el resultado `play[15]` trae
     `s=[x0,y0,ang,v0,windX,vertAccel]`, `time` (ms) y `h` (impacto). De 8 disparos:
     `v0 = 2.269·power`, `gravity = 390` con viento 0, `ang_abs = ang − body` y
     origen Armor `(20.45, −25.83)`. La predicción coincide con `h` dentro de ~4 px.
     La sala 44 dio `2.364/402`; el perfil se auto-calibra con `s[3]/s[5]`.
     Ver §7.4, §16.9 y §16.12.
   - **No usar `extra`** como trayectoria: es apuntado/touch.
5. **Estado en vivo**
   - **Confirmado (2026-10-20):** Pixi.js; `window.PIXI` se borra, pero el
     `InteractionManager`/`Stage` es accesible y el stage contiene el árbol del juego.
   - **Confirmado:** jugador propio = `PlayerInfo-*.is_me === true`; se leen
     `x`, `y`, `look`, `body_angle`, `angle.rotation` y `player_info`.
   - **Confirmado (2026-10-20):** `angle.rotation`/`aim.deg` contra `game_shoot.ang`
     con `look=0` y `body=0` (`aim.deg=200` → `ang=20`); el espejo `look=0` y la
     potencia quedaron validados. `body≠0` ya era consistente en la calibración
     previa.
6. **Superficie del overlay**
   - Contenedor del viewport y su rectángulo; cámara y su transformación.
7. **Restricciones**
   - CSP / `script-src` / SRI del bundle: ¿permite inyectar en MAIN world?
     (Chrome 111+ `world:"MAIN"`; si no, `<script>` con `nonce` o plan B).
   - Términos de servicio / normas del servidor.
   - **Pendiente:** no se auditó CSP/SRI ni ToS en esta captura.
8. **Permisos de la extensión**
   - `host_permissions` para el dominio real (y para `wss://`) según la URL capturada.
   - **Confirmado:** se necesita acceso a `https://dragonbound.net/*` y a
     `wss://*.dragon.ws/*` (el host concreto depende del servidor elegido).

### 16.4 Cómo obtener más evidencia

- Repetir la captura desde un **navegador real** con una cuenta propia y una partida
  de prueba, como en §16.8.
- Alternativa offline: exportar un **HAR** con los frames de WebSocket y una
  **captura** del árbol de red, y analizarlos aquí para ajustar el perfil (§16.5).
- Entregables: URL del WS, lista de opcodes con ejemplos de frames, presencia/ausencia
  de Pixi, constantes físicas confirmadas o corregidas, rect del viewport.
- Si se vuelve a usar captura automatizada, tener en cuenta que `curl`/redes de
  datacenter pueden recibir **403** de Cloudflare; el navegador real es la referencia.

### 16.5 Perfil de compatibilidad (soportar remake y producción)

La extensión se diseñará con un **perfil** conmutable (`profiles/*.json`) para que el
mismo motor funcione con el remake y con el sitio real:

```jsonc
{
  "id": "dragonbound-net",
  "match": ["https://dragonbound.net/*", "https://*.dragonbound.net/*"],
  "ws": { "urlPattern": "wss://*.dragon.ws/*", "framing": "opcode+xor" },
  "xor": { "clientToServer": 165, "serverToClient": 219 },
  "opcodes": { "game_shoot": null, "game_move": 6, "play": 6, "room_state": 5,
               "update": 11, "result": 13,
               "game_shootByServer": { "6": 7, "5": 58 } },
  "client": { "engine": "pixi", "bundle": "inline-obfuscated",
              "pixiCapturable": true, "playerAngleField": "angle.rotation",
              "meFlag": "player_info.is_me", "camera": "world-container" },
  "physics": { "model": "live", "gravity": 390, "powerScale": 2.269,
               "timeUnit": "s", "timeDivisor": 1000, "unit": "px/s",
               "armorOrigin": { "a": 20.45, "c": 25.83, "x": 20.45, "y": -25.83 },
               "autoCalibration": { "enabled": true, "source": "play",
                                    "window": 8, "fallback": "2.269/390" },
               "remake": { "gravity": 398, "powerScale": 2.34, "timeDivisor": 485 } },
  "wind": { "mode": "ignored", "value": 0 },
  "verified": { "protocol": true, "physics": true, "pixi": true, "date": "2026-10-20" },
  "calibrated": false
}
```

- El perfil `dragonbound-net` tiene el **protocolo, la física live y Pixi
  confirmados** (2026-10-20), pero sigue `calibrated:false`: las restricciones no
  están verificadas y la física aún no se ha validado dentro de la extensión.
- `game_shoot` no es estable entre servidores (`0x07` en server 6, `0x3A` en
  server 5); el hook debe detectarlo por la forma del payload o por perfil.
- La captura live usó el servidor 6 (`Bunge`) y la ruta `/6`; el `urlPattern` cubre
  otros servidores de `*.dragon.ws`.
- `wind.mode = "ignored"` y `wind.value = 0` son la **decisión v1**: el viento se
  lee si está disponible, pero no se usa en la física.
- Si algún valor difiere, se corrige **solo en el perfil**, sin tocar el motor.
- La gravedad live parte de `390` y la escala de `2.269`, pero el perfil se
  **auto-calibra** desde `play` (§16.12); no se asume que sean universales.
  El perfil permite recalibrar si cambia la versión del servidor.

### 16.6 Criterios de aceptación de la verificación

- [x] URL del WebSocket confirmada: `wss://s3qu3nc3dis0rdred.dragon.ws/6` (2026-10-20).
- [x] Disparo y mensaje de turno (`play = 0x06`) confirmados con frames reales.
  El opcode del disparo es variable: `0x07` (server 6) / `0x3A` (server 5).
- [x] Constantes físicas live de referencia: `v0 = 2.269·power`, `gravity = 390`
  (viento 0), tiempo en segundos, origen Armor `(20.45, −25.83)`; quedan como
  **fallback** porque la sala 44 midió `2.364/402`.
- [x] Motor del cliente confirmado: **Pixi.js**; jugador propio localizado por
  `player_info.is_me` y ángulo leído de `angle.rotation` (2026-10-20).
- [x] Correlación `angle.rotation` ↔ `ang` con `look=0`/`body=0` (2026-10-20):
  `aim.deg=200` → `ang=20`. `look=1` ya estaba cubierto por la calibración previa.
- [x] Potencia DOM ↔ `game_shoot.power` (195/250/240/223) y auto-calibración
  `powerScale`/`gravity` implementada y probada (14/14).
- [x] `room_state` de salas 48 y 44 comparado: mismo modo/mapa/`max_wind`; no
  declara física. `game_start` conocido tampoco. Ver §16.12.
- [x] Física y auto-calibración integradas en la extensión y probadas con un
  harness Pixi/WebSocket simulado (calibración `2.364/402`, reset por `room_state`).
- [ ] Física validada dentro de una partida live (no solo con harness).
- [x] Selección `Ctrl + clic derecho`, snap al cañón, trayectoria y marca de
  potencia implementados en `extension/src/content.js`; integración 38/38 con
  destinos izquierdo/derecho y `look=0/1`.
- [ ] Escenarios live G–O, resize/fullscreen y cambio de sala/reconexión.
- [ ] Restricciones (CSP/SRI/ToS) documentadas.
- [ ] Perfil `dragonbound-net` marcado como `calibrated:true` con fecha/versión.

### 16.7 Regla de honestidad

Tras las capturas del **2026-10-20**, el **protocolo**, la **física live** y **Pixi**
están confirmados para producción (opcodes con variantes por servidor). La
extensión MV3 ya implementa el hook, el overlay y la selección origen/destino, y
pasó las pruebas de auto-calibración (14/14) y de integración con Pixi/WebSocket
simulados (38/38). **Las restricciones (CSP/SRI/ToS), la prueba G–O en una partida real y
la validación de resize/fullscreen/reconexión** siguen pendientes. Nada se declara
"funciona en producción" hasta completar esos puntos. La extensión no debe
presentar valores estimados como confirmados.

### 16.8 Captura live (2026-10-20)

- Entorno: navegador Brave real, cuenta propia, servidor **6 (`Bunge`)**, partida 1 vs 1.
- Endpoint: `wss://s3qu3nc3dis0rdred.dragon.ws/6` (handshake 101, nginx).
- 148 frames: 80 cliente → servidor y 65 servidor → cliente; 5 disparos y resultado final `won: 0`.
- XOR de aplicación: `0xA5` cliente → servidor; `0xDB` servidor → cliente.
- `game_shoot` observado: `0x07`; ejemplo de campos:
  `748,214,-12,1,25,100,3155,0,[...]` → `x,y,body,look,ang,power,time,selectedShot,extra`.
- `play` observado: `0x06`; ejemplo:
  `[2,0,1273,164,0,500,1,[],1076,-334,-2,0,-1,14,2,[...]]`
  → `wind_power = 14` (índice 13), `wind_angle + 180 = 2` (índice 14).
- `extra` es apuntado/touch, no la trayectoria física.
- La captura cruda **no se versiona** porque contiene un token de sesión; cualquier
  muestra que se agregue al repo debe ir redactada.

### 16.9 Calibración live (servidor 5, sala 48, 2026-10-20)

- Entorno: Brave real, **Armor**, mapa **Metamine**, 1 vs 1, rival inmóvil.
- Se capturaron **8 disparos**. Viento: `0` en los dos primeros turnos; después
  `50, 49, 48, …, 42`. Por eso solo los dos primeros son válidos para AIRE = 0.
- En `play` (índice 15) el servidor envía el resultado físico:
  `s=[x0,y0,ang_abs,v0,windX,vertAccel]`, `time` (ms) y `h` (impacto).
- Importante: `wind_power` (índice 13) es el viento del **siguiente** turno. El
  viento que actuó en el disparo está en `s[4]`/`s[5]`. Por eso el disparo 2 tiene
  `s[4]=0` aunque el `play` que lo contiene muestre `wind_power=50`.
- `s` y `time` reproducen `h` dentro de ~1–4 px en los 8 disparos. Ejemplos:

| # | Viento | power | ang | body | `s` | `time` | `h` |
|---:|---:|---:|---:|---:|---|---:|---|
| 1 | 0 | 175 | 55 | −17 | `[414,−11,72,397,0,390]` | 2050 | `[664,31,60,40]` |
| 2 | 0 | 133 | 55 | −15 | `[265,40,70,302,0,390]` | 50 | `[269,29,60,40]` |
| 3 | 49 | 188 | 55 | −23 | `[331,15,78,427,−201,378]` | 2175 | `[50,0,60,40]` |
| 5 | 46 | 164 | 20 | −6 | `[385,14,26,372,−189,378]` | 1150 | `[645,75,60,40]` |
| 8 | 42 | 102 | 21 | −1 | `[511,27,22,232,−173,382]` | 1275 | `[645,222,37,27]` |

- Constantes de esta captura: `v0 = power × 2.269` (2.266–2.275),
  `gravity = 390` con viento 0, `ang_abs = ang − body`, origen Armor
  `(20.45, −25.83)` rotado por `body`. Los 8 disparos se hicieron con `look=1`.
- **Captura posterior (sala 44, `look=0`, `body=0`):** cuatro disparos dieron
  `v0/power = 2.364` y `s[5] = 402`; el espejo `look=0` y la potencia quedaron
  confirmados. Ver §16.12.
- El disparo del servidor 5 empezó con byte `0x3A` (ASCII `:`), no con `0x07` como
  en el servidor 6. Es un opcode variable; ver §7.4.
- **Pendiente:** repetir con viento realmente desactivado y 4–6 impactos para
  validar los offsets de Armor y la gravedad con más puntos. La física básica ya
  está confirmada; la captura actual es suficiente para implementar y comparar.

### 16.10 Pixi y jugador propio en vivo (2026-10-20)

- Entorno: Brave real, `https://dragonbound.net/#server-5/room-43`, partida en curso.
- `window.PIXI` es `undefined` después de cargar. Para inspeccionar sin recargar se
  usó un breakpoint en `InteractionManager.onMouseOut`; `this` es el
  `InteractionManager`, `this.stage` el `Stage` de Pixi y `stage.interactionManager`
  el propio manager. Técnica solo de diagnóstico; la extensión captura Pixi en
  `document_start` (§4.2).
- Canvas: **1257×943**. `zoom = 1.5716667` (`root.children[0].scale.x`).
- Cámara: posición del contenedor de mundo (padre de los `PlayerContainer-*`):
  `(64.57, 194.09)` en la muestra. Transformación confirmada:
  `screen = zoom * (world + camera)`.
- Jugador propio: `PlayerContainer-ZinComPaSioN` (mundo `(793, 118)`, `look=0`,
  `body_angle=26`, `angle.rotation=4.1015 rad = 235°`, `player_info.is_me=true`).
- Oponente: `PlayerContainer-Alex285` (mundo `(360, 174)`, `look=1`,
  `angle.rotation=213°`). También aparece `PlayerContainer-soul` para un jugador
  muerto: **no** es un jugador activo.
- `PlayerInfo-<nombre>` expone `is_me`, `is_turn`, `hp`, `max_hp`, `team`, `guild` y
  `country`; el `PlayerContainer` expone `x`, `y`, `look`, `body_angle`, `angle`
  (rotación del apuntado), `mobile` (`Armor2D.png`) y `aimIsMe`.
- **No usar índices** (`stage/0/2/3`, etc.): el árbol cambia al añadir/quitar joyas,
  almas y efectos. Buscar por `_name` (`PlayerContainer-*`, `PlayerInfo-*`) y por
  `player_info.is_me`.
- **Resuelto (2026-10-20):** en el último disparo `aim.deg = 200` y el
  `game_shoot` envió `ang = 20`; con `look=0` y `body=0`,
  `ang = aim.deg − 180`. Los otros tres disparos son consistentes. `look=0`
  queda confirmado; `look=1` ya estaba cubierto por la calibración previa.
  Queda pendiente `body ≠ 0` en producción.

### 16.11 Potencia en vivo desde el DOM (2026-10-20)

La potencia **no** está en Pixi: es DOM dentro de `#gameui`.

| Elemento | Valor observado | Uso |
|---|---|---|
| `#powerBar` | `offsetLeft=244`, `offsetTop=569`, alto 22 px; `style.width` = potencia | Barra de carga. Mientras se mantiene el clic crece de 0 a 400 px. |
| `#powerMarks` | `offsetWidth=400`, mismo origen | Contenedor de marcas. |
| `#powerMarkArea` | `offsetWidth=400` | Área de la barra. |
| `#powerMark` | `offsetLeft=195`, ancho 11 px, `left:200px` | Marca fija (referencia visual). |
| `#last_power_mark` | ancho 1 px; `style.left = 247 + potencia` | Marca de la **última** potencia disparada. |

Lectura:

```js
// durante la carga
const power = parseFloat(document.querySelector('#powerBar').style.width) || 0;

// después del disparo (última potencia)
const lastPower =
  (parseFloat(document.querySelector('#last_power_mark').style.left) || 0) - 247;
```

Muestras reales (server 5, sala 19, 2026-10-20):

| Disparo | `#powerBar` | `#last_power_mark.left` | `left − 247` |
|---|---:|---:|---:|
| 1 | 182 px | 429 px | 182 |
| 2 | 117 px | 364 px | 117 |

La relación es lineal y exacta dentro de 1 px. La barra **conserva** el ancho de la
última potencia después del disparo; para la potencia *actual* hay que leerla
mientras el jugador mantiene el clic. `#powerMark` no es la potencia actual.

**Resuelto (2026-10-20):** los cuatro disparos de la sala 44 coincidieron
exactamente con `game_shoot.power`: `195, 250, 240, 223`. La potencia DOM es la
misma unidad 0–400 que usa el servidor y alimenta la auto-calibración (§16.12).

### 16.12 Captura `room_state` y auto-calibración (2026-10-20)

#### Captura y comparación

Se capturó `room_state` de la sala 44 al volver a entrar, y se comparó con el
`room_state` de la calibración previa (sala 48):

| Sala | `room_state` | `game_mode` | `map` | `max_wind` | `turn_time` |
|---:|---|---:|---:|---:|---:|
| 48 | `[1,48,"DragonBound","",2,0,9,0,0,0,0,0,0,0,0,20,0,0,0,0]` | 0 (`NORMAL`) | 9 | 0 | 20 |
| 44 | `[1,44,"Ven a jugar","",2,0,9,0,0,0,0,0,0,0,0,20,0,0,0,0]` | 0 (`NORMAL`) | 9 | 0 | 20 |

Las dos salas coinciden en **modo, mapa, viento máximo y tiempo de turno**. La
UI de la sala 44 mostraba `NORMAL`, `Meta Mine`, `Wind 0`, turno 20. Por tanto,
la diferencia `2.269/390` vs. `2.364/402` **no está declarada por `room_state`**.

`game_start` no aporta constantes de física en el contrato conocido: envía
jugadores, posición de turno, `thor`, clima, `wind_power`/`wind_angle`, mapa,
`is_s1_disabled`, `event_game`, `game_mode`, `score`, `turn_time` y clima activo.
No hay `v0`, `powerScale` ni gravedad. No se capturó un `game_start` live nuevo
porque la partida ya había terminado; si se quiere evidencia live adicional, se
captura al iniciar otra partida, pero **no es una fuente de calibración**.

#### Implementación

Archivo: `extension/src/auto-calibration.js`.

- Entrada: payload descifrado de `game_shoot` y payload descifrado de `play`.
- Empareja el `play` con un disparo propio pendiente por `x`, `y` y `look`;
  descarta `play` de otros jugadores.
- `powerScale = s[3] / game_shoot.power`.
- `gravity = s[5]` **solo si `s[4] === 0`** (viento horizontal nulo). Si hay
  viento, la muestra se usa para `powerScale` pero no para gravedad. `s[5]` es la
  aceleración vertical **efectiva**: si hubiera viento vertical, `s[4]` podría
  ser 0 y `s[5]` incluir ese componente; se acepta como gravedad efectiva de la
  sala. Para AIRE=0 estricto hay que validar con viento total 0.
- Mediana de las últimas 8 muestras (`powerScale` y `gravity`) para resistir
  outliers y cambios de sala.
- Fallback inicial: `2.269`, `390`, origen Armor `(a=20.45, c=25.83)`.
- Origen opcional: `calibrateOrigin:true` resuelve `(a,c)` desde
  `play.s[0]/s[1]`; desactivado por defecto porque `s` llega redondeado a píxel.
- `resetPhysics()` al cambiar de sala/reconectar; conserva los orígenes por móvil.
- `originFor(player, body, look, mobile)` espeja el offset local por `look` y
  después lo rota por `body` (§7.4).

API mínima:

```js
const cal = new DragonShotAutoCalibration.AutoCalibration();
cal.observeGameShoot(gameShootRaw, { mobile: '/static/images/mobiles/Armor2D.png' });
cal.observePlay(playRaw);
const profile = cal.getProfile('/static/images/mobiles/Armor2D.png');
// profile.powerScale, profile.gravity, profile.origin, profile.samples
```

Pruebas: `extension/test/auto-calibration.test.html`.
Resultado actual: **14/14**. Cubren `look=0`, `look=1`, parseo de ambos
opcodes (`:` y `0x07`), `play` con metadatos, `play` ajeno, viento horizontal,
reset, origen opcional, espejo local del origen con `body` y callback.

#### Resultado con los cuatro disparos

| Disparo | `power` | `s[3]` | `v0/power` | `s[4]` | `s[5]` |
|---:|---:|---:|---:|---:|---:|
| 1 | 195 | 461 | 2.36410 | 0 | 402 |
| 2 | 250 | 591 | 2.36400 | 0 | 402 |
| 3 | 240 | 567 | 2.36250 | 0 | 402 |
| 4 | 223 | 527 | 2.36323 | 0 | 402 |

Mediana: `powerScale = 2.3636143497757846`, `gravity = 402`. La muestra
`look=1` previa (`power=175`, `s[3]=397`, `s[5]=390`) sigue dando `2.26857` y
`390`, así que el módulo conserva ambos perfiles sin mezclarlos.

> **Decisión:** el perfil live **no** se fija a `2.269/390`. Esos valores quedan
> como respaldo hasta el primer `play` propio; después manda la mediana observada.
> Esto cubre cambios de sala, versión del servidor o bonificaciones de cuenta sin
> hardcodear una constante que ya se demostró variable.

### 16.13 Corrección de apuntado y espejo del origen (2026-10-20, live)

La primera prueba en partida real mostró dos fallos que las fixtures sintéticas
no cubrían:

1. **Destino a la derecha:** la trayectoria salía invertida en vertical (el
   cañón apuntaba arriba y la curva bajaba). El código trataba
   `angle.rotation` como el `ang` relativo del cliente y le restaba `body`:
   `angAbs = rotation − body` para `look=1`.
2. **Origen con el móvil a la izquierda:** seguía dibujándose a la derecha. El
   espejo se aplicaba al `dx` ya rotado por `body`, y el lado se deducía solo de
   `player.look`, que puede quedarse atrás respecto al sprite.

**Causa raíz del ángulo.** `angle.rotation` es la rotación de pantalla de Pixi
(Y hacia abajo, positiva horaria) y ya incluye `look` y `body`: es
`rotation = −ang_abs`. La única muestra live con ambos datos
(`aim.deg = 200` ↔ `ang = 20`, `look=0`, `body=0`) cumple
`ang_abs = 360 − aim.deg = 160`. Para `look=1` la fórmula anterior devolvía
`360 − ang_abs` (reflejo horizontal), de ahí la curva invertida.

**Corrección aplicada.**

- El ángulo se lee de `player.angle.worldTransform`: la columna `(a, b)` es el
  eje `+x` del cañón tras `body`, `look` y cualquier espejo de la cadena de
  padres. `ang_abs = normalize(−atan2(b, a)·180/π)`. No se resta `body` ni se
  vuelve a espejar por `look`. `angle.rotation` queda como respaldo
  (`ang_abs = normalize(−rotation·180/π)`).
- Si el destino está a la derecha y el cañón apunta arriba-derecha, el ángulo
  resultante es `≈ ang` (no `360 − ang`), y la trayectoria sube.
- El lado del móvil se toma del signo horizontal del apuntado cuando es
  claramente horizontal (`|cos ang_abs| ≥ 0,5`) y, si no, de `player.look` o del
  espejo del contenedor. Así un `look` atrasado no deja el origen del lado
  equivocado.
- `originFor` espeja el offset **local** `(a, −c)` antes de rotar por `body`
  (§7.4). Con `body = 26` y `look = 0` el origen pasa de `(−29,70, −14,25)` a
  `(−7,06, −32,18)`, que es lo que hace `game.js`.
- `game_shoot.ang` reconstruido desde el absoluto al simular un disparo por
  `pointerup` (`look=0 → 180 − abs − body`; `look=1 → abs + body`).

**Pruebas.** Se actualizaron las fixtures de integración para usar
`worldTransform` (`rotation = −ang_abs`) y se añadieron tres casos: destino
derecho con `look=1`, `look` atrasado corregido por el apuntado y apuntado casi
vertical que respeta `look`. Resultado: **14/14** auto-calibración y **38/38**
integración. Queda pendiente la validación en partida real con `body ≠ 0`.

> La regla de deducir el lado desde el signo del apuntado quedó **sustituida**
> por §16.14: era circular cuando la lectura del cañón venía espejada.

### 16.14 Corrección del espejo del apuntado (2026-10-21, live)

La validación en partida real mostró que el tiro al oponente de la **izquierda**
seguía descalibrado, tanto en la potencia sugerida como en la trayectoria. El
dato del panel fue concluyente: apuntando al rival de la izquierda, `ÁNGULO`
marcaba **50–70°**.

Un cañón que apunta al oponente situado a la izquierda debe dar `> 90°`. El
valor mostrado era el **reflejo vertical** del real (`180 − 120 = 60`): el
`worldTransform` de `angle` no siempre incluye el espejo del sprite, así que la
lectura puede venir con el coseno cambiado y el mismo seno.

**Cadena del fallo.** La lectura espejada (coseno positivo) hacía que
`detectLook` dedujera `look=1` aunque `player.look` fuera `0`. Con `look=1` el
origen no se espejaba (cañón a la derecha), el ángulo quedaba en la rama
contraria y la solución de potencia se calculaba para otro lado. Un solo error
producía los dos síntomas.

**Corrección aplicada (v0.1.2).**

- `detectLook` usa `player.look` como fuente principal; `scale.x` y el signo del
  apuntado quedan solo como último recurso. Se elimina el círculo vicioso.
- `alignAimToLook(deg, look)`: si el signo horizontal del ángulo no coincide con
  el lado del móvil, se refleja con `180 − deg`. Vale para `look=0` (lee 60°,
  usa 120°) y para `look=1` (lee 160°, usa 20°). Cerca de la vertical
  (`|cos| < 0,1`) no se toca.
- El snapshot expone `aimRaw` (lectura cruda), `aimMirrored` y `aimCheck`.
- `aimCheck` compara, en cada `game_shoot` real, el ángulo mostrado con el que
  envió el cliente (`(look=0 ? 180 − ang : ang) − body`). El residuo se muestra
  en el panel como `e0°`; si algún día deja de ser ~0, el diagnóstico queda a la
  vista sin abrir DevTools.
- Línea de diagnóstico en el panel: `L0 · B-17° · R72° · θ72° · e0°` (lado,
  cuerpo, rotación Pixi, ángulo leído, error del último disparo).

**Pruebas.** Nuevos casos de integración: lectura espejada con `look=0` que se
alinea a 120° con origen a la izquierda y solución válida, y lectura espejada
con `look=1` que se alinea a 20° con origen a la derecha. Resultado: **14/14**
auto-calibración y **40/40** integración.

> §16.14 quedó **incompleta**: el espejo no es horizontal (`180 − deg`) sino
> vertical (`−deg`). Ver §16.15.

### 16.15 Corrección definitiva del eje del cañón (2026-10-21, live)

La comprobación visual en partida real (capturas del canvas y del dial que el
juego dibuja alrededor del móvil) cerró el diagnóstico: con `look=0` y
`raw=235°` el cañón apuntaba a **~135°**, no a 235°. La relación correcta es
una **inversión vertical**, no un reflejo horizontal.

**Por qué.** El nodo `player.angle` cuelga de un contenedor que se espeja en
vertical cuando el móvil mira a la izquierda. Su `worldTransform` devuelve la
dirección con el eje Y invertido:

```
raw  = 180 + ang + body        (look = 0)
real = 180 − ang − body = −raw (mod 360)
```

Mirando a la derecha no hay espejo y la lectura ya es el ángulo absoluto:

```
real = raw                     (look = 1)
```

Evidencia en vivo:

| caso | dato |
| --- | --- |
| `look=0`, `raw=235°`, `body=0` | dial del juego ~135° → `−235 ≡ 125` |
| `look=0`, `raw=200°`, `ang` del cliente 20° | `180 − 20 = 160 = −200` |
| `look=1`, `raw=24°` | rival a 28° → el cañón apuntaba al rival |
| fixtures `look=0` (`ang=27/55/20`) | servidor `153/125/160 = 180 − ang` |
| fixtures `look=1` (`ang=55`, `body=−17`) | servidor `72 = ang − body` |

**Corrección aplicada (v0.1.3).**

- `alignAimToLook(deg, look, source)` con `source === 'world'` devuelve
  `−deg` si `look === 0` y `deg` si `look === 1`. Desaparece el umbral
  `|cos| < 0,1`: la inversión es sistemática, no condicional.
- La lectura de respaldo (`source === 'rotation'`, local, sin padre) conserva
  la heurística anterior porque no permite componer el espejo.
- El panel muestra el ángulo ya corregido; `aimMirrored` pasa a indicar que se
  aplicó la inversión de `look=0`.
- El mensaje `play` incluye `truth` (`s[2]` del servidor), `raw`, `aimSource` y
  `look` en el mismo instante, para verificar la conversión en vivo sin abrir
  DevTools.

**Pruebas.** Casos de integración reescritos con lecturas reales:
`look=0, raw=240 → 120`, `look=0, raw=200 → 160`, `look=1, raw=20 → 20`,
`look=0, raw=265 → 95` (cerca de la vertical). Resultado: **14/14**
auto-calibración y **41/41** integración.

**Verificación live (misma sesión, 8 disparos).** Cada `play` publica `truth`
(`s[2]`) y `raw` del mismo instante; el residuo fue **0° en todos**:

| look | raw | mostrado | verdad |
| --- | --- | --- | --- |
| 0 | 231° | 129° | 129° |
| 0 | 235° | 125° | 125° |
| 0 | 230° | 130° | 130° |
| 0 | 206° | 154° | 154° |
| 1 | 50° | 50° | 50° |
| 1 | 63° | 63° | 63° |

La conversión `look=0 → −raw`, `look=1 → raw` queda confirmada contra el
servidor, no solo contra el arnés.

### 16.16 Pendiente: calibración de potencia por tipo de disparo

Detectado al cerrar §16.15. **No implementado todavía**; queda documentado para
no perder el contexto.

> **Actualización 2026-10-29 (v0.1.4).** §16.17 sustituyó los arrays globales
> por cubos por móvil, así que la parte de "gravedad por móvil" ya está hecha.
> El tipo de bala **sigue sin separarse**: la clave del cubo es solo el móvil y
> `pendingShots` tampoco compara `selectedShot`. La propuesta técnica de abajo
> se mantiene, pero la clave pasa a ser `móvil|bala` sobre el `Map` existente.

#### Síntoma funcional

La `POTENCIA NECESARIA` y la trayectoria se desvían según la bala elegida. En la
sesión live el perfil quedó en `powerScale=2.2919`, `gravity=386` (8 muestras),
mientras que los fixtures de la misma móvil daban `2.3641` / `402`: un **3–4 %**
de diferencia en `v0`. A 800–1200 unidades de alcance son decenas de píxeles, y
la marca de potencia queda corta o larga de forma sistemática.

La trayectoria dibujada y la marca son coherentes entre sí (ambas usan el mismo
`powerScale`), así que el error no se ve en el overlay: se ve al disparar.

#### Evidencia

- Durante la sesión live, el arma seleccionada era la **segunda** bala:
  `#btnShot2` tenía la clase `Pressed` (`#btnShot1` sin clase, `#btnShotSS`
  con `grayscale`).
- Todos los fixtures capturados tienen `selectedShot=0` (primera bala).
- El cliente envía el tipo en `game_shoot` (campo 8, índice `s[7]`), pero la
  calibración lo ignora.

#### Causa técnica (estado actual del código)

1. `AutoCalibration` separa por móvil (`buckets`), pero **no por tipo de
   bala**: `observePlay` hace `powerScale = s[3] / shot.power` y escribe en el
   cubo del móvil sin mirar `shot.selectedShot`.
2. `getProfile(mobile)` solo separa por móvil; el perfil que consume
   `content.js` es la mediana mezclada de todas las balas.
3. La ruta sintética de disparo (`page-hook.js`, `onSyntheticShoot`, la que
   reconstruye el `game_shoot` cuando el frame real no se captura) fija
   **`selectedShot: 0`** de forma literal. Es la ruta habitual: en la captura
   live cada `play` vino precedido de un `game-shoot` sintético
   (`time: 0`, `selectedShot: 0`).
4. `pendingShots` se empareja con `play` por `(x, y, look, antigüedad)`; no
   compara `selectedShot`. Si conviven el sintético (0) y el real (2), el
   `splice` se queda con el más reciente y el resultado depende del orden de
   llegada.
5. `page-hook.profile()` y el snapshot no publican el tipo de bala, así que el
   panel tampoco puede avisar de qué perfil está usando.

#### Propuesta funcional

- El panel debe mostrar la bala activa y el perfil en uso:
  `CALIBRADO S2 (5)` / `FALLBACK S1`.
- La calibración se lleva **por (móvil, tipo de bala)**. Al cambiar de bala, la
  potencia sugerida y la trayectoria se recalculan con su perfil, sin esperar a
  un nuevo disparo.
- Si una bala aún no tiene muestras suficientes, se usa la mediana global como
  puente y, si tampoco hay, `DEFAULT_PROFILE`. El panel indica de dónde sale.
- No mezclar muestras de tipos distintos: una bala con multiplicador propio
  contamina la mediana de las demás.
- Las balas sin efecto de potencia (si las hay) pueden compartir cubo, pero eso
  se decide con datos, no por suposición.

#### Propuesta técnica

- `auto-calibration.js`
  - `shotKey(mobile, selectedShot)` → `normalizeMobile(mobile) + '|' + slot`
    (`slot = selectedShot == null ? 0 : selectedShot`).
  - Sustituir los arrays por
    `Map<string, { power: [], gravity: [] }>`; `observeGameShoot` guarda
    `selectedShot` y `observePlay` escribe en el cubo del disparo emparejado.
  - `getProfile(mobile, selectedShot)` devuelve mediana del cubo → mediana
    global → `DEFAULT_PROFILE`, y añade `shotType` y `samples` al perfil.
  - `minSamples` por cubo (p. ej. 3) antes de abandonar el *fallback*.
  - `resetPhysics()` sigue limpiando todos los cubos en `room_state`.
- `page-hook.js`
  - `readSelectedShot()`: `#btnShot1.Pressed` → `0`, `#btnShot2.Pressed` → `1`,
    `#btnShotSS.Pressed` → `'SS'`; si no hay clase reconocible, `null` y se
    conserva el último valor visto en `game_shoot` (o `0`).
  - La ruta sintética usa `readSelectedShot()` en vez del `0` literal.
  - Publicar `selectedShot` en `profile()` y en el snapshot.
- `content.js`
  - Consumir el perfil del cubo activo y mostrar `S1/S2/SS` en la línea de
    diagnóstico (`L0 · B-29° · R206° · θ154° · S2`).
  - Recalcular al detectar cambio de bala (la clase `Pressed` viaja en el
    snapshot), sin esperar a moverse.
- Pruebas
  - Fixtures nuevos con `selectedShot=1` (segunda bala) y dos cubos en la misma
    instancia: cada uno debe dar su `powerScale` y no contaminarse.
  - Test de que un `play` empareja con el `pendingShot` del mismo tipo.
  - Test de cambio de bala: `Pressed` en `#btnShot2` → el snapshot publica
    `S2` y el perfil cambia.
  - Regresión: con menos de `minSamples` en el cubo, el resultado sigue siendo
    válido (fallback) y no rompe los 41/41.

#### Riesgos y bordes

- Los IDs/clases (`btnShot1`, `btnShot2`, `btnShotSS`, `Pressed`, `grayscale`)
  son del cliente actual; si cambian, `readSelectedShot()` debe degradar a
  `null` sin romper (y el panel mostrar `S?`).
- `SS` deshabilitada (`grayscale`) no debe contar como selección.
- Payloads viejos sin `selectedShot` → cubo `0`.
- Cambio de bala durante la carga: manda el tipo en el instante del disparo; la
  ruta sintética lo lee en el `pointerup`, que es cuando se envía.
- El origen calibrado (`calibrateOrigin`) no depende de la bala, pero si algún
  día se activa conviene conservarlo por móvil, no por bala.

#### Criterio de aceptación

Con 5 disparos por bala al mismo destino: `powerScale` de cada cubo estable
(desviación < 1 %), la potencia sugerida acierta en ambos y el panel indica la
bala y el número de muestras. Sin regresiones en 20/20 y 41/41.

### 16.17 Calibración por móvil: soporte de Boomer (2026-10-29, v0.1.4)

#### Síntoma funcional

Con Armor la v0.1.3 era estable (aire 0), pero al cambiar a **Boomer** los
disparos se quedaban cortos: la gravedad del mundo es **por móvil** y la
calibración la promediaba entre todos. La tabla del cliente
(`script.js:22-110`, ver `analisis-aimbot-dragonbound.md` §3.2) da
`a = 75` para Armor y `a = 62,5` para Boomer: un **17 % menos** de gravedad.
Usar la gravedad de Armor para Boomer desplaza el punto de caída cientos de
unidades a larga distancia.

Además, el primer disparo de un móvil nuevo usaba el respaldo `390` (Armor),
no una estimación de su propia gravedad, y la escala de potencia arrancaba en
`2.269` aunque ya hubiera muestras válidas de la sesión.

#### Tabla usada (coeficiente `a`)

| Móvil | a | Móvil | a | Móvil | a |
|---|---|---|---|---|---|
| armor | 75 | ice | 63,5 | aduka | 60,5 |
| lightning | 65 | bigfoot | 90 | jd | 63,5 |
| asate | 75,5 | random | 81 | knight | 65,5 |
| fox | 61 | dragon | 95 | dragon2 | 120 |
| nak | 79,5 | trico | 84 | mage | 71,5 |
| turtle | 74,5 | **boomer** | **62,5** | electrico | 73,5 |
| frog | 65,5 | kalsiddon | 65,5 | grub | 63,5 |

La gravedad del mundo es `g = a · unidad`. La `unidad` se aprende de
`play.s[5] / a` de cualquier móvil observado; sin observaciones se usa la de
respaldo `390 / 75 = 5,2`. Ejemplos:

- Armor observada en `402` → unidad `5,36` → **Boomer predice `335`**
  (`5,36 · 62,5`).
- Sesión nueva, sin muestras → Boomer predice `325` (`5,2 · 62,5`), mejor que
  el `390` de Armor.

#### Implementación

- `auto-calibration.js`
  - `MOBILE_A` con los 21 coeficientes y `mobileKey()`, que quita ruta y
    extensión y normaliza el sufijo `2D`
    (`/static/images/mobiles/Boomer2D.png` → `boomer`; `Dragon22D.png` →
    `dragon2`).
  - `buckets: Map<móvil, { power: [], gravity: [] }>` con ventana de 8. Se
    eliminaron los arrays globales de física.
  - `observePlay` escribe en el cubo del disparo emparejado (no en uno global) y
    añade `s[5] / a` a `gravityUnits` cuando el viento horizontal es 0.
  - `getProfile(mobile)`: mediana del cubo propio → gravedad **predicha** por
    tabla/unidad → `DEFAULT_PROFILE`; potencia del cubo propio → mediana global
    → `DEFAULT_PROFILE`. Publica `mobile`, `gravitySource`
    (`observed` / `predicted` / `default`), `samples` del cubo y
    `originMeasured` (origen medido, se aplica solo con `calibrateOrigin`).
  - `resetPhysics()` limpia cubos, potencia global y unidades (la sala puede
    cambiar las constantes); conserva los orígenes por móvil, que no dependen
    de la sala.
- `page-hook.js` no necesitó cambios: ya pasa `mobileNameOf(me)` a
  `getProfile` y a `originFor`, y ahora la clave se normaliza dentro del módulo.
- `content.js` consume `snap.profile`; la trayectoria, la potencia sugerida y
  la marca usan la gravedad del móvil activo. El panel muestra
  `CALIBRADO (n)` con muestras propias del móvil, `PREDICHO` si solo hay
  predicción por tabla y `FALLBACK` si no hay datos.
- `manifest.json` sube a **0.1.4** (recargar la extensión descomprimida).

#### Pruebas

- Auto-calibración **20/20** (antes 14/14). Casos nuevos:
  - `mobileKey` normaliza ruta, extensión y sufijo `2D`.
  - Boomer predice `402/75·62,5` tras una muestra de Armor y hereda la potencia
    global sin mezclar `samples`.
  - Boomer sin ninguna observación predice `390/75·62,5 = 325`.
  - Un móvil sin tabla cae al perfil por defecto (`390` / `2.269`).
  - Cubos separados: Armor conserva `402` / `2.3641` y Boomer `335` / `2.29`.
  - El origen se mide en cada disparo (`originMeasured`) pero no se aplica si
    `calibrateOrigin` está desactivado.
- Integración **41/41** sin regresiones.

#### Límites y pendientes

- **Origen de Boomer**: implementado en v0.1.6 (§16.19). Se auto-calibra tras
  tres muestras estables; hasta entonces usa el offset de Armor
  `(20,45, 25,83)`. La medición queda en `profile.originMeasured` (`a`, `c`,
  `samples`, `mad`, `stable`).
- **Tipo de bala**: los cubos son por móvil, no por `S1/S2/SS`; sigue vigente
  §16.16. Con Boomer y la segunda bala, la potencia puede desviarse ~3–4 %.
- **Viento**: con `AIRE = 0` el factor `b` no interviene. Si se habilita el
  viento, `b` también es por móvil (Boomer `1,39`, el mayor del juego) y habrá
  que calibrarlo por móvil, no con una constante global.
- **Validación live**: falta confirmar con el cliente real que Boomer cae en
  `~335` y que el origen no necesita corrección. Criterio: 5 disparos al mismo
  destino, `powerScale` estable < 1 % y error de caída dentro del marcador.

#### Criterio de aceptación

Boomer debe usar su propia gravedad desde el primer disparo (predicha) y
afinarla con `play.s[5]` en los siguientes; el panel y el perfil deben indicar
de dónde sale el valor (`observed` / `predicted` / `default`), sin regresiones
en 20/20 y 41/41.


### 16.18 Cobertura del resto de móviles (2026-10-29, v0.1.5)

#### Alcance

La v0.1.4 dejó Boomer funcionando con gravedad propia. Esta iteración cierra la
cobertura de los **21 móviles** de la tabla del cliente: no hay física especial
por móvil, solo cambia el coeficiente `a` (gravedad) y el offset del cañón. El
motor de trayectoria y el cálculo de potencia son los mismos para todos; lo que
cambia es el perfil que consumen.

#### Cómo queda cada móvil

- **Gravedad**: `g = unidad · a`, con la unidad aprendida de `play.s[5]` y la de
  respaldo `390/75 = 5,2`. Si el móvil aún no disparó, se predice con su `a`
  (§16.17). Tras el primer disparo, su cubo guarda el `s[5]` real.
- **Potencia**: la escala `v0 = potencia · powerScale` es del disparo, no del
  móvil. Se usa el cubo del móvil → mediana global → `DEFAULT_PROFILE`.
- **Trayectoria**: `drawTrajectory` usa la gravedad del perfil activo; con la
  gravedad correcta la parábola coincide con la del juego.
- **Barra de poder**: `solvePower` despeja la potencia con esa misma gravedad y
  el origen; `drawPowerMarker` la sitúa en `0..400`. Un móvil de gravedad alta
  (Dragon2, `a=120`) pide más potencia que Armor para el mismo destino; uno de
  gravedad baja (Aduka `60,5`, Boomer `62,5`) pide menos. Si la potencia
  necesaria supera 400, el panel muestra `INALCANZABLE` y no dibuja la marca.
- **Origen**: auto-calibrado por móvil desde v0.1.6 (§16.19). Se mide en cada
  `play` y se aplica tras tres muestras estables; Armor conserva su valor
  ajustado. Es el único parámetro que puede quedar unos píxeles desplazado.

#### Tabla de referencia

La tabla completa está en §16.17 (coeficiente `a`). Los valores vienen de
`analisis-aimbot-dragonbound.md` §3.2 (`script.js:22-110`), contrastados con la
gravedad observada de Armor (`402/75 = 5,36`). NAK tiene un caso especial en el
cliente (`Zero1`/`Zero2`, `b1 *= -8`); su predicción es aproximada y se corrige
con el primer `s[5]`.

#### Normalización de nombres

`mobileKey()` quita ruta y extensión, elimina acentos (`Eléctrico2D.png` →
`electrico`) y resuelve el sufijo `2D`:
`/static/images/mobiles/Boomer2D.png` → `boomer`,
`Dragon22D.png` / `Dragon2_2D.png` → `dragon2`.
Un móvil desconocido cae al perfil por defecto y aprende su gravedad con el
primer disparo.

#### Pruebas

- Auto-calibración **23/23**. Nuevas:
  - `mobileKey` con acentos, rutas y guion bajo.
  - **Los 21 móviles de la tabla predicen `unidad · a`** tras una muestra de
    Armor, con la clave y la fuente correctas.
  - Dragon2 aprende `643,2` sin tocar el cubo de Armor (`402`).
- Integración **50/50**. Nuevas:
  - Con el mismo destino y ángulo, `data-power` de Boomer (`335`) es menor que
    el de Armor (`402`) y el de Dragon2 (`643,2`) mayor.
  - El snapshot publica `mobile`/`gravity` correctos y el panel muestra
    `PREDICHO` para un móvil sin muestras.
  - El panel expone `data-mobile`, `data-gravity` y `data-gravity-source` para
    diagnóstico.

#### Límites

- La tabla cubre los móviles conocidos; si el juego agrega uno nuevo, el
  fallback es 390 hasta el primer disparo.
- El **origen** de cada móvil se auto-calibra en v0.1.6 (§16.19); los tres
  primeros disparos usan el de Armor hasta reunir muestras estables.
- El **viento** sigue en `AIRE = 0`; el factor `b` (Boomer `1,39`, el mayor)
  tendrá que calibrarse por móvil si algún día se habilita.
- Falta validación live móvil por móvil; la tabla y el motor están cubiertos por
  pruebas, pero el cliente real solo se puede comprobar en partida.

#### Criterio de aceptación

Para cualquier móvil de la tabla, el perfil debe usar su `a` (predicha o
observada), la trayectoria y la marca de potencia deben recalcularse al cambiar
de móvil, y no debe haber contaminación entre cubos. Sin regresiones en 23/23 y
50/50.



### 16.19 Origen del cañón por móvil (2026-10-29, v0.1.6/v0.1.7)

#### Problema

El origen del cañón es un offset local del sprite, distinto para cada móvil.
Hasta v0.1.5 todos usaban el de Armor `(a=20,45, c=25,83)`; en Boomer y el
resto el punto de salida quedaba desplazado, lo que afectaba a la parábola y a
la potencia. La v0.1.6 lo auto-calibró con `play`, pero los primeros tiros de
un móvil seguían usando el offset de Armor y la medición podía tardar en
estabilizarse. La v0.1.7 añade la **tabla `aim` del cliente** como origen
inmediato: el disparo sale del mismo punto donde nace el ángulo de cada móvil
desde el primer tiro.

#### Modelo

El offset se expresa en el marco local del jugador:

```text
dx = sx·a·cos(body) + c·sin(body)
dy = sx·a·sin(body) − c·cos(body)
```

con `sx = -1` si el móvil mira a la izquierda (`look = 0`) y `sx = 1` si mira a
la derecha. Cada `play` propio trae la salida real en `s[0]/s[1]`; el solver
deshace el espejo y la rotación del cuerpo y obtiene `(a, c)`:

```text
dx = s[0] − x;  dy = s[1] − y
si look = 0: dx = −dx
a = dx·cos(body) + dy·sin(body)
c = dx·sin(body) − dy·cos(body)
```

No usa el ángulo de apuntado ni la potencia: el offset es fijo en el marco del
cuerpo. Se descartan muestras con `|a|` o `|c|` fuera de rango (> 100).

#### Tabla `aim` (origen inmediato)

La ficha de cada móvil del cliente trae un vector `[ángulo, radio]` con
`0° = horizontal hacia delante` y positivo hacia arriba. El origen local es:

```text
a = radio · cos(ángulo)
c = radio · sin(ángulo)
```

La tabla viene de `helper.js:690-699` (`aim`) y coincide con el origen live de
Armor: `33·(cos 52°, sin 52°) = (20,32, 26,00)` frente a `(20,45, 25,83)`
medido en vivo. Se usa como **semilla** para los 21 móviles:

| Móvil | `aim` | `a` | `c` |
| --- | --- | ---: | ---: |
| Armor / Eléctrico / Frog / Kalsiddon / Grub | 52°, 33 | 20,32 | 26,00 |
| Ice / Bigfoot / Dragon / Dragon2 | 58°, 50 | 26,50 | 42,40 |
| Lightning | 58°, 44 | 23,32 | 37,31 |
| JD | 68°, 45 | 16,86 | 41,72 |
| Asate | 40°, 30 | 22,98 | 19,28 |
| Random / Knight / Trico / Mage / Turtle | 51°, 51 | 32,10 | 39,63 |
| Fox | 30°, 44 | 38,11 | 22,00 |
| Aduka / NAK / Boomer | 130°, 40 | −25,71 | 30,64 |

Aduka y NAK tienen un `adukafix` especial en el cliente que no se replica; su
primer tiro puede quedar algo desplazado hasta que la auto-calibración lo
corrija. Boomer no lleva ese fix: su cañón nace detrás/arriba del centro.

#### Aplicación

`getProfile(mobile)` combina tres fuentes, en este orden:

1. **Medición** (`originMeasured`): mediana de las últimas 8 muestras del móvil.
2. **Ajuste fijo** (`TUNED_ORIGINS`): Armor `(20,45, 25,83)`, validado en vivo.
3. **Predicción** (`MOBILE_AIM`): vector `aim` del móvil.
4. **Defecto**: Armor si el móvil no está en la tabla.

`getOrigin(mobile)` devuelve la mediana, la MAD (mediana de las distancias a la
mediana) y `stable = MAD ≤ maxOriginMad` (2 unidades). La decisión es:

| Modo | Condición | Origen aplicado | `originSource` |
| --- | --- | --- | --- |
| `'auto'` (defecto) | móvil sin ajuste fijo, ≥ 3 muestras, estable y a ≤ 8 del `aim` | mediana medida | `measured` |
| `'auto'` | móvil con `TUNED_ORIGINS` (Armor) | valor ajustado | `tuned` |
| `'auto'` | móvil con `MOBILE_AIM` y < 3 muestras o inestable | vector `aim` | `predicted` |
| `'auto'` | móvil desconocido y sin muestras | `DEFAULT_PROFILE.origin` | `default` |
| `true` | ≥ 1 muestra | mediana medida | `measured` |
| `false` | siempre | ajuste fijo, `aim` o defecto | `tuned` / `predicted` / `default` |

Opciones: `calibrateOrigin` (`'auto'` por defecto), `minOriginSamples` (3),
`maxOriginMad` (2) y `maxOriginSeedDelta` (8). `resetPhysics()` conserva los orígenes porque son una
propiedad del sprite, no de la sala; `reset()` los borra.

#### Diagnóstico

El perfil publica `origin` (aplicado), `originSource`, `originCalibrated`
(`source === 'measured'`) y `originMeasured = { a, c, samples, mad, stable }`.
El panel añade el origen al tooltip de calibración (`origen medido (n)`,
`origen fijo`, `origen predicho`, `origen por defecto`) y `data-origin-source`.

#### Pruebas

- Auto-calibración **30/30**. Nuevas:
  - La tabla `aim` siembra los 21 móviles y el vector de Armor coincide con el
    origen live (distancia < 0,5).
  - Boomer arranca con `(a=−25,71, c=30,64)` (`predicted`) y tras 3 muestras
    estables cerca de su `aim` aplica la mediana (`measured`).
  - Con 2 muestras sigue el vector `aim`; una secuencia inestable (MAD 20)
    también cae al `aim`.
  - Una medición estable pero a ~58 unidades del `aim` **no** se aplica
    (guarda `maxOriginSeedDelta = 8`); `calibrateOrigin: true` sí la fuerza.
  - Armor conserva el valor ajustado aunque acumule muestras (`tuned`).
- Integración **56/56**. Nuevo: Boomer usa su origen `aim` desde el primer
  snapshot; tras 3 disparos con la boca en `(761, 95)` el origen medido refina
  el perfil y el snapshot usa ese punto; el panel publica `data-origin-source`.

#### Límites

- El vector `aim` es una tabla del cliente/helper; si una versión futura cambia
  un sprite, la auto-calibración lo corrige tras 3 muestras estables.
- `play.s[0]/s[1]` llega redondeado a píxel; la MAD absorbe el ruido normal,
  pero una secuencia con `body` mal resuelto se descarta hasta estabilizarse.
- Aduka/NAK (`adukafix`) y Boomer son los casos más sensibles; falta validarlos
  en vivo móvil por móvil.
- Con el origen propio de Boomer (detrás/arriba) la distancia al destino es algo
  mayor; su gravedad más baja compensa y la potencia queda cerca de la de
  Armor, no siempre por debajo.

---

## Anexo A — Referencias de código (runtime del juego)

- `power = parseInt((power * 234) / 100)` → `src/game/game.js:440`
- `ang = 180 - ang` (si `look === 0`) → `src/game/game.js:450`
- `ang -= body` → `src/game/game.js:470`
- `weight: 398`, `friccion: 0` → `src/game/game.js:509-510`
- `Vector2(a,b)`: `x = cos(a·π/180)·b`, `y = -sin(a·π/180)·b` → `src/game/lib/shoot.js:374-379`
- `getPosAtTime`: `a = t/485`; `x = x0 + vx·a + friccion·a²/2`; `y = y0 + vy·a + weight·a²/2` → `src/game/lib/shoot.js:174-180`
- `game_shoot` cliente → `src/web/public_html/data/js/1023989.js:43590-43601`
- `DragonSocket` → `1023989.js:51356-51412`
- `play` (servidor) → `src/game/lib/message.js:431-451`
- Opcodes → `src/game/gametypes.js` (`SERVER_OPCODE`, `CLIENT_OPCODE`)
- Captura live 2026-10-20 → `wss://s3qu3nc3dis0rdred.dragon.ws/6`, XOR
  `0xA5`/`0xDB`, `game_shoot=0x07` (server 6) / `0x3A` (server 5), `play=0x06`
  (ver §16.8 y §16.9)
