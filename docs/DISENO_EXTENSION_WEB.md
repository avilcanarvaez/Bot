# Diseño: Extensión de navegador para DragonBound (juego web)

> Documento de diseño y plan de implementación. **No incluye código todavía.**
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
- La física se puede **replicar exactamente** porque las constantes del servidor
  están en el repo (`weight = 398`, escala de potencia `×2.34`, divisor `485`), y
  con **AIRE = 0** la trayectoria es balística pura.

El proyecto Java de captura **no se descarta formalmente** en este documento: queda
como respaldo hasta validar la extensión contra el juego real (ver §15).

> **Importante:** la evidencia de §1 proviene del **remake** `DragonBoundR/DragonBound`,
> no del juego en producción. La verificación contra el sitio real
> `https://dragonbound.net/` es un paso **bloqueante** y está documentada en **§16**
> (desde este equipo el sitio está bloqueado / responde 403).

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
 │             │ WebSocket (ws://host:9001)         │ chrome.runtime    │
 └─────────────┼────────────────────────────────────┼───────────────────┘
               ▼                                    ▼
      Servidor de juego Node               Service worker (extensión)
      (autoridad: turno, viento)           • estado/opciones, storage
```

### 3.1 Componentes

| Componente | Contexto | Responsabilidad |
|---|---|---|
| `manifest.json` | — | MV3, `content_scripts` con `"world": "MAIN"` y `"world": "ISOLATED"`, `host_permissions` del origen del juego. |
| `inject/page-hook.js` | MAIN | Hooks de `WebSocket` y captura de `window.PIXI`; publica estado por `window.postMessage`. |
| `content/content.js` | ISOLATED | Puente de mensajes, monta el overlay, dibuja, gestiona `SIMULAR`. |
| `overlay/overlay.js` + CSS | ISOLATED (DOM) | Canvas/DOM superpuesto al viewport del juego. |
| `core/physics.js` | ISOLATED | Balística con **AIRE=0** (misma matemática que el servidor). |
| `core/state.js` | ISOLATED | Estado de sesión, sincronización, staleness. |
| `background/service-worker.js` | SW | Persistencia de opciones, badge de estado. |
| `popup/`, `options/` | UI | Configuración: origen, offset de cañón, modelo de aire (fijo 0), depuración. |

### 3.2 Mensajería entre contextos

MAIN → ISOLATED por `window.postMessage` con un esquema fijo (prefijo propio):

```jsonc
// page-hook → content
{ "src": "dragonshot", "type": "hello",  "data": { "hasWebSocket": true, "hasPixi": true } }
{ "src": "dragonshot", "type": "send",   "data": { "opcode": 1, "args": [/* game_shoot */], "t": 1710000000 } }
{ "src": "dragonshot", "type": "recv",   "data": { "opcode": 6, "args": [/* play */],       "t": 1710000000 } }
{ "src": "dragonshot", "type": "aim",    "data": { "ang": 63, "power": 240, "x": 512, "y": 721, "body": 4, "look": 0, "mine": true } }
{ "src": "dragonshot", "type": "turn",   "data": { "turn": 2, "me": 0, "players": [/* … */] } }
```

`content` envía comandos a MAIN (p. ej. pedir un `aim` bajo demanda) con el mismo
canal y `src:"dragonshot-cmd"`.

---

## 4. Fuente del estado

### 4.1 Red (autoridad) — hook de WebSocket

Interceptar `window.WebSocket` en `document_start` (mundo MAIN) **antes** de que el
bundle lo capture (`1023989.js:51353`). Envolver:

- `WebSocket.prototype.send` → `JSON.parse(datos)` → emitir `type:"send"` con `{opcode, args}`.
- El canal de recepción: el cliente asigna `ws.onmessage`; se redefine el setter o se envuelve para parsear igual que `DragonSocket` (`d[0]` = opcode, resto = args).

Opcodes de interés (ver §5 para detalles): `game_shoot(1)`, `game_move(6)`,
`started_to_shoot(60)`, `look(61)` enviados; `play(6)`, `room_state(5)`,
`game_start(7)`, `update(11)`, `started_to_shoot(60)`, `look(61)` recibidos.

### 4.2 Runtime del cliente (tiempo real) — captura de Pixi

`window.PIXI` se expone y luego se borra (`16156-16157`). En `document_start`
definimos un **accesor** en `window.PIXI` que:

1. Guarda la referencia en una variable del script de hook (`__dsPIXI`).
2. Deja que `delete window.PIXI` funcione (propiedad `configurable: true`).

Con la referencia a Pixi se recorre el escenario para localizar el contenedor del
jugador propio y leer en vivo: `ang`, `x`, `y`, `body`, `look` (y `is_me`). En modo
2D el jugador es `CPlayer`; en modo Pixi/3D es `Player2`/`PlayerContainer`
(`ChangeAngle :55195`, `ChangeBodyAngle :55191`).

**Riesgo/plan B:** si el recorrido del stage no fuera estable, el ángulo en vivo se
toma del último `game_shoot` (al disparar) y, como último recurso, de visión in-page
sobre el canvas (§11.4).

### 4.3 Identificación del jugador propio y del turno

- `my_player_info(1)` / `game_start(7)` identifican tu `position`/`my_player_index`.
- `play(6)` incluye `next_turn_number`, la posición del jugador y la del siguiente
  (`message.js:431-451`).
- Solo se dibuja trayectoria cuando `turn == my_player_number` (o en modo
  "observador" con el jugador seleccionado).

---

## 5. Contratos de mensajes

### 5.1 Opcodes relevantes (`src/game/gametypes.js`)

| Cliente → servidor (envía) | Valor | Uso en la extensión |
|---|---|---|
| `game_shoot` | 1 | Ángulo y potencia **reales** del disparo. |
| `game_move` | 6 | Movimiento del jugador (posición/look/ángulo). |
| `started_to_shoot` | 60 | El jugador empezó a apuntar. |
| `look` | 61 | Cambio de dirección. |

| Servidor → cliente (recibe) | Valor | Uso |
|---|---|---|
| `room_state` | 5 | `max_wind`, mapa, modo, etc. |
| `play` | 6 | Turno, posiciones, viento, clima, thor. |
| `game_start` | 7 | Arranque de partida. |
| `update` | 11 | Actualizaciones de estado. |
| `started_to_shoot` / `look` | 60 / 61 | Apuntado/dirección de otros jugadores. |

### 5.2 `game_shoot` (cliente → servidor)

`ds.Send(CLIENT_OPCODE.game_shoot, x, y, body, look, ang, power, time, type, extra)`

| Pos | Campo | Significado |
|---|---|---|
| 0 | `x, y` | Posición del jugador (mundo). |
| 2 | `body` | Inclinación del terreno en la posición del jugador (grados). |
| 3 | `look` | `0 = izquierda`, `1 = derecha`. |
| 4 | `ang` | Ángulo del cañón (grados, 0–180 en pantalla Y-abajo). |
| 5 | `power` | Potencia de la barra (**0–400**). |
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

## 7. Física con AIRE = 0 (exacta del juego)

El servidor construye el disparo en `src/game/game.js:436-547` y simula en
`src/game/lib/shoot.js`. Con **AIRE = 0** (sin viento) se replica sin calibración:

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

### 7.3 Extensibilidad del aire

`core/physics.js` expondrá una interfaz `DragModel` con una única implementación
activa `NO_AIR` (AIRE = 0). Un modelo futuro (viento/arrastre) implementaría el
equivalente de `setWindEfect` sin tocar el resto del sistema. **La UI mantiene AIRE
fijo en 0** y no se implementa arrastre en esta fase.

---

## 8. `SIMULAR` (diseño funcional)

`SIMULAR` calcula y dibuja la trayectoria **desde el origen real** con el **ángulo
real** y, si se desea, la **potencia actual**, usando §7 con AIRE = 0.

- **Origen:** `origen` del cañón (§7.1) desde `player.x/y`, `body`, `look`, móvil.
- **Ángulo:** `player.ang` en vivo (Pixi) o el `ang` del último `game_shoot`.
- **Potencia:** la de la barra en vivo, o un barrido/rango si el usuario quiere ver
  varias potencias.
- **Dibujo:** proyección mundo → viewport (§6), como polilínea/puntos sobre el overlay.
- **Fin de trayectoria:** hasta colisión con el terreno del mapa (`IsPixel`/`GetUnder`
  del cliente) o un `t` máximo.

Integración de datos (resumen): identificar jugador/turno → obtener ángulo →
actualizar campo `ÁNGULO` → seguir desplazamiento de escena (cámara) → `SIMULAR` usa
origen/ángulo vigentes → dibujar con **AIRE = 0**.

### 8.1 Asistente de potencia y ángulo ("¿hasta dónde disparo?")

La barra del juego solo muestra la potencia **actual**; el usuario no sabe cuánta
necesita para dar al rival. El asistente resuelve la **balística inversa** (§7 con
AIRE = 0) y muestra el dato explícito.

Definiciones: `N = 485`, `θ` = ángulo efectivo (espejo por `look` y `body` ya
aplicados), `W = 398`, `Δx = xt − x0`, `Δy = yt − y0`, `τ = t / N`.

**Potencia necesaria (con el ángulo actual):**

```
K         = Δx / cos(θ)
τ         = sqrt( 2 * (Δy + sin(θ) * K) / W )
P_srv     = K / τ
P_cliente = P_srv * 100 / 234        // 0..400, la unidad de la barra
```

**Ángulo necesario (con la potencia actual):** con `P_srv = P_cliente * 234 / 100` y
`u = Δx / P_srv`, se resuelve `s = tan(θ)` en
`(W·u²/2)·s² − (P_srv·u)·s + (W·u²/2 − Δy) = 0`, eligiendo la raíz de ángulo alto
según `look`.

**Presentación:**

- Dato principal en el panel: **`POTENCIA NECESARIA = 181 / 400`** (y/o
  `ÁNGULO NECESARIO` para una potencia dada).
- **Marca** en la barra de potencia: *actual* vs. *necesaria*.
- **Trayectoria solución** en verde, que termina exactamente en el objetivo; la actual
  se dibuja en cyan (se ve si "se pasa" o "se queda corta").
- **Abanico** tenue de trayectorias para referencia de alcance.
- Si no hay solución real (discriminante negativo o fuera de `[0, 400]`), mostrar
  **"inalcanzable"** en lugar de un número inventado.

El objetivo lo fija el usuario marcando al rival (o un punto del mapa); el asistente
indica la potencia (o el ángulo) para dar en él.

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

**Fase 0 — Andamiaje**
- `extension/manifest.json` (MV3), iconos, permisos.
- Inyección MAIN + ISOLATED, canal `postMessage` documentado.
- Página de prueba estática con un WebSocket mock.

**Fase 0.5 — Verificación contra el sitio real (bloqueante)**
- Ejecutar el checklist de §16.3 sobre `https://dragonbound.net/` desde un entorno con acceso.
- Fijar/confirmar el **perfil** (§16.5): opcodes, motor, constantes físicas, URL del WS y permisos.
- Sin esta fase, el diseño aplica **solo al remake**.

**Fase 1 — Lectura de red**
- Hook `WebSocket` send/receive; decodificador de opcodes (§5).
- Estado de turno/jugador desde `play`, `room_state`, `game_start`.

**Fase 2 — Lectura de runtime (ángulo vivo)**
- Captura de `window.PIXI`; recorrido del stage; lectura `ang/x/y/body/look`.
- Fallback a `game_shoot` y marca de "no disponible".

**Fase 3 — Overlay y coordenadas**
- Montaje del overlay sobre `#gameui`; conversión mundo→viewport con la cámara.
- Dibujo de origen/destino y del indicador de ángulo.

**Fase 4 — Física AIRE=0 y `SIMULAR`**
- `core/physics.js` con `DragModel.NO_AIR`.
- Botón `SIMULAR`, campo `ÁNGULO`, campo `AIRE = 0` (fijo).

**Fase 5 — Pruebas y endurecimiento**
- Unitarias + integración con harness mock; escenarios A–F (§12).
- Manejo de errores, logging, opciones.

**Árbol de archivos previsto (no creado aún):**

```
extension/
  manifest.json
  inject/page-hook.js
  content/content.js
  overlay/overlay.js
  overlay/overlay.css
  core/physics.js
  core/protocol.js
  core/state.js
  background/service-worker.js
  popup/popup.html|js
  options/options.html|js
  tests/
```

---

## 11. Pruebas

### 11.1 Unitarias (JS)
- `physics.js`: casos con `AIRE=0` comparados con `shoot.getPosAtTime` del repo
  (mismos `x0,y0,ang,power,weight=398,friccion=0,485`).
- `protocol.js`: decodificación de `game_shoot`/`play` con muestras reales del formato.
- `state.js`: staleness, mediana, filtrado por `is_me`, revalidación por cambio de turno.

### 11.2 Integración
- **Harness mock**: página que simula un WebSocket y un "jugador" con `ang` mutable,
  para validar el flujo hook → estado → física → overlay sin el juego real.

### 11.3 End-to-end manual (A–F)
| # | Escenario | Criterio |
|---|---|---|
| A | Desplazamiento de escena | Origen/destino siguen al escenario al mover la cámara (sin drift). |
| B | Cambios de ángulo | `ÁNGULO` sigue el `player.ang` real; cuadrantes y signos correctos. |
| C | Dirección del disparo | `SIMULAR` respeta `look` (espejo 180−ang) y `body`. |
| D | Integración `SIMULAR` | Usa origen/ángulo vigentes; se actualiza sin valores viejos. |
| E | Condición de aire | `AIRE = 0` fijo; el viento leído se ignora; trayectoria balística pura. |
| F | Estabilidad/rendimiento | Sin jitter ni parpadeo; overlay fluido; estado "no disponible" correcto. |

### 11.4 Fallback de visión (opcional)
Si el hook de Pixi no fuera estable en alguna versión, se puede añadir detección
in-page sobre el canvas del juego (mismo principio que la app Java, pero sin captura
de escritorio ni DPI). Queda **fuera de la fase 1–5** y se documenta como plan B.

---

## 12. Limitaciones y riesgos

1. **Dependencia de la estructura interna** (`player.ang`, stage de Pixi) y del
   protocolo. Mitigación: hook de red como fuente primaria + marca de "no
   disponible"; tests de contrato.
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

- **(BLOQUEANTE) Verificación contra `https://dragonbound.net/`** (§16): el análisis
  proviene del *remake*, no del sitio en producción. Sin esta verificación, opcodes,
  constantes y estructura del cliente son **hipótesis**.
- Confirmar en el juego real que `window.PIXI` es capturable y que el jugador propio
  es localizable en el stage (o mantener el fallback).
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

Todo el análisis de §1–§8 se hizo sobre el repositorio `DragonBoundR/DragonBound`, que
es un **remake** ("DragonBoundRe — Remake of the original DragonBound game").
`https://dragonbound.net/` es el **juego en producción**. No hay garantía de que
compartan:

- versión/estructura del cliente (bundle, motor, nombres internos),
- opcodes del protocolo y forma de los mensajes,
- constantes físicas (`weight`, escala de potencia, divisor 485),
- transporte (puerto, `ws://` vs. `wss://`, framing, token/ruta).

Por tanto, **todo lo deducido debe confirmarse** contra el sitio real antes de
considerar el diseño válido para producción. Lo deducido del remake queda marcado
`↩ repo`; lo pendiente en producción, `⚠ producción`.

### 16.2 Estado actual (bloqueo desde este equipo)

- Desde este entorno el acceso está **bloqueado**: `https://dragonbound.net/` (y
  `www.`) responde **HTTP 403** con una página de reto de **Cloudflare**, incluso para
  `/` y para rutas de assets.
- No es un fallo de la extensión ni del código: es **protección anti-bot / restricción
  de red** del equipo.
- Consecuencia: **no se pudo verificar nada automáticamente**; las afirmaciones sobre
  el sitio real quedan marcadas como **pendientes** hasta ejecutar §16.3.

### 16.3 Qué hay que verificar (checklist)

Ejecutar desde un navegador con acceso real (sesión normal) con DevTools abierto:

1. **Cliente / motor**
   - ¿Es Pixi.js? Buscar `PIXI`, `canvas`, `getContext`, `worldTransform`, `requestAnimationFrame`.
   - ¿Un bundle único? Anotar ruta y tamaño (comparar con `1023989.js`).
   - ¿Es WebGL/canvas (HTML5) o SWF/Flash? Esto define la estrategia.
2. **WebSocket**
   - Network → WS → anotar la **URL exacta** (esquema `ws`/`wss`, host, puerto, ruta/token).
   - Capturar varios **frames** enviados y recibidos y guardarlos como evidencia.
3. **Opcodes / contratos**
   - ¿Los mensajes son `[opcode, ...args]` serializados en JSON?
   - Confirmar el opcode de disparo y sus campos (ángulo, potencia, `look`, `body`).
   - Confirmar el mensaje de turno y sus campos (posiciones, viento).
4. **Constantes de física**
   - Buscar `398`, `234`, `485` (o equivalentes) en el bundle.
   - **Validación empírica:** con un disparo conocido (origen, ángulo, potencia) y su
     impacto medido, despejar `W` y la escala de potencia y comparar con `398` / `2.34`.
5. **Estado en vivo**
   - ¿`window.PIXI` existe (aunque se borre) y es capturable?
   - ¿Se localiza el jugador propio y se lee `ang`, `body`, `look`, `x`, `y`, `is_me`?
6. **Superficie del overlay**
   - Contenedor del viewport y su rectángulo; cámara y su transformación.
7. **Restricciones**
   - CSP / `script-src` / SRI del bundle: ¿permite inyectar en MAIN world?
     (Chrome 111+ `world:"MAIN"`; si no, `<script>` con `nonce` o plan B).
   - Términos de servicio / normas del servidor.
8. **Permisos de la extensión**
   - `host_permissions` para el dominio real (y para `wss://`) según la URL capturada.

### 16.4 Cómo proceder si el equipo sigue bloqueado

- Ejecutar la verificación desde una **máquina/red con acceso** (otra conexión, VPN
  autorizada, o el propio servidor si se despliega ahí).
- Alternativa offline: exportar desde un navegador con acceso un **HAR** con los frames
  de WebSocket y una **captura** del árbol de red, y analizarlos aquí para ajustar el
  perfil (§16.5).
- Entregables de la verificación: URL del WS, lista de opcodes con ejemplos de frames,
  presencia/ausencia de Pixi, constantes físicas confirmadas o corregidas, rect del
  viewport.

### 16.5 Perfil de compatibilidad (soportar remake y producción)

La extensión se diseñará con un **perfil** conmutable (`profiles/*.json`) para que el
mismo motor funcione con el remake y con el sitio real:

```jsonc
{
  "id": "dragonbound-net",
  "match": ["https://dragonbound.net/*", "https://*.dragonbound.net/*"],
  "ws": { "urlPattern": "wss://…", "framing": "json-array" },
  "opcodes": { "game_shoot": 1, "play": 6, "room_state": 5, "game_start": 7,
               "update": 11, "started_to_shoot": 60, "look": 61 },
  "client": { "engine": "pixi", "pixiGlobal": "PIXI", "playerAngleField": "ang", "meFlag": "is_me" },
  "physics": { "gravity": 398, "powerScale": 2.34, "timeDivisor": 485, "unit": "client-0-400" },
  "calibrated": false
}
```

- El perfil `dragonbound-net` queda **sin confirmar** (`calibrated:false`) hasta §16.3.
- Si algún valor difiere, se corrige **solo en el perfil**, sin tocar el motor.
- Si la gravedad no fuese `398`, el perfil permite **calibrarla** con una observación
  real (origen + impacto) en vez de asumirla.

### 16.6 Criterios de aceptación de la verificación

- [ ] URL del WebSocket confirmada (esquema, host, puerto, ruta).
- [ ] Disparo (`game_shoot`) y mensaje de turno confirmados con frames reales.
- [ ] Motor del cliente confirmado (Pixi u otro) y método de lectura del ángulo.
- [ ] Constantes físicas confirmadas o corregidas, con evidencia.
- [ ] Restricciones (CSP/SRI/ToS) documentadas.
- [ ] Perfil `dragonbound-net` marcado como `calibrated:true` con fecha/versión.

### 16.7 Regla de honestidad

Mientras §16 no se complete: nada se declara "funciona en producción". El diseño es
**válido para el remake** (`↩ repo`) y **candidato** para el sitio real (`⚠ producción`).
La extensión no debe presentar valores estimados como confirmados.

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
