# DragonShot — extensión web

Extensión MV3 para Chrome/Brave. Dibuja un overlay sobre DragonBound con:

- origen en el cañón del jugador propio,
- destino elegido con el botón **Seleccionar destino** o con **Ctrl + clic**,
- trayectoria solución con el ángulo actual,
- potencia necesaria marcada sobre la barra de poder,
- auto-calibración de `powerScale`/`gravity` a partir de disparos propios.

La extensión **no dispara**, no modifica `#powerBar` y no envía paquetes. Solo
observa la red y Pixi para dibujar.

## Instalar (Chrome/Brave)

1. Abrir `chrome://extensions` (o `brave://extensions`).
2. Activar **Modo de desarrollador**.
3. Pulsar **Cargar descomprimida**.
4. Elegir esta carpeta: `extension/` (la que contiene `manifest.json`).
5. Abrir o recargar `https://dragonbound.net/`.

Requiere Chrome 111 o superior porque el hook de página usa
`content_scripts[].world = "MAIN"`.

Si ya tenías la extensión cargada, pulsa **Volver a cargar** en
`chrome://extensions` y después recarga la pestaña del juego. El service worker
se encarga de mostrar/ocultar el panel desde el icono; **no hay popup del
navegador**.

## Uso

1. Entrar a una sala y esperar a que aparezca tu móvil.
2. Pulsar **Seleccionar destino** en la ventanita de DragonShot.
3. Mover el ratón hasta el punto del campo: el juego recibe el movimiento y gira
   el cañón. Hacer **un clic normal** para fijar el destino. La capa de selección
   es solo visual y no bloquea el apuntado.
4. Repetir el botón para mover el destino. **Limpiar** o `Esc` lo borra.

> Si el panel muestra `OBJETIVO DETRÁS`, mueve el ratón hacia el lado del destino
> para que el cañón apunte en esa dirección. La trayectoria usa el **ángulo real**
> del juego, no un ángulo inventado.

Alternativa con teclado: **Ctrl + clic izquierdo o derecho** una vez fija el
origen en el cañón; el segundo clic fija el destino. Los siguientes clics
mueven el destino. Un clic cerca del móvil propio vuelve a fijar el origen.

El panel muestra:

- `POTENCIA NECESARIA`: potencia `0..400` para el ángulo actual.
- `ÁNGULO`: ángulo absoluto de salida (0° = derecha, 90° = arriba, 135° =
  arriba-izquierda), leído de `angle.worldTransform` y convertido según el lado
  real del móvil (`look=0` invierte el eje vertical).
- `CALIBRACIÓN`: `FALLBACK` o `CALIBRADO (n)` con el número de muestras.
- Línea de diagnóstico pequeña (`L0 · B-17° · R72° · θ72° · e0°`): lado, cuerpo,
  rotación cruda de Pixi, ángulo leído y error del último disparo real. Sirve
  para reportar desvíos sin abrir DevTools.

La ventanita se acopla automáticamente al lado del canvas (`data-dock=right` o
`left`) y, si no hay sitio, debajo/encima. En pantalla completa, donde no existe
margen exterior, usa el borde superior derecho con borde ámbar; el botón `−` la
contrae y el icono de la extensión la oculta/muestra.

La línea verde es la solución; la línea cian punteada es la trayectoria con la
potencia actual. La marca verde sobre la barra es visual: no cambia la barra.

## Arquitectura

```text
src/auto-calibration.js   módulo puro de física y calibración
src/page-hook.js          mundo MAIN: WebSocket + Pixi + snapshots
src/content.js            mundo ISOLATED: overlay, panel, selección y dibujo
src/background.js         service worker: icono -> mostrar/ocultar panel
```

El page-hook se ejecuta en `document_start`, envuelve `WebSocket` sin alterar
los frames y captura el `stage` de Pixi observando `Function.prototype.bind`
(el cliente real no expone `window.PIXI`).

El ángulo de salida se obtiene de `player.angle.worldTransform`: la columna
`(a, b)` es el eje `+x` ya transformado (incluye `body` y los espejos de la
cadena de padres), y el ángulo matemático es `-atan2(b, a)`. No se resta `body`
ni se vuelve a espejar por `look`.

Con `look=0` el nodo del cañón cuelga de un contenedor espejado en vertical: el
`worldTransform` devuelve la dirección con el eje Y invertido
(`raw = 180 + ang + body`). Por eso `alignAimToLook` aplica `real = −raw` a la
izquierda y `real = raw` a la derecha; la inversión es sistemática, no
condicional. Comprobado en vivo contra el dial del juego y contra el ángulo que
envía el servidor en `play` (`s[2]`). El origen del cañón espeja el offset local
`(a, −c)` **antes** de rotar por `body` (`originFor`). Para considerar que hay partida,
exige que `#Dragon2D` esté visible y que el `stage` contenga un jugador con
`player_info.is_me`. El turno propio se lee de `#turn_line0`: la primera línea
es el jugador activo y lleva `turn_line_me` cuando es tu turno. El overlay pide
snapshots por `window.postMessage` a 10 Hz.

La selección por botón usa una capa transparente dentro del Shadow DOM, pero con
`pointer-events:none`: los `mousemove` llegan al canvas y el cañón apunta al
cursor. El clic final se intercepta en `window` (fase de captura) antes de que el
canvas lo reciba; así no dispara ni mueve al jugador. El page-hook recibe un aviso
`selection-mode` para no reconstruir una muestra de disparo con ese clic.
`Ctrl + clic` sigue disponible como atajo.

## Auto-calibración

Archivo: `src/auto-calibration.js`.

El módulo es puro (sin DOM, sin WebSocket) y trabaja con payloads ya
descifrados:

- `game_shoot` → `observeGameShoot(raw, { mobile })`
- `play` → `observePlay(raw)`
- perfil vigente → `getProfile(mobile)` (la clave del móvil se normaliza con
  `mobileKey`: `/static/images/mobiles/Boomer2D.png` → `boomer`)
- origen del cañón → `originFor(player, body, look, mobile)`
- cambio de sala/reconexión → `resetPhysics()`
- reset total (incluidos orígenes por móvil) → `reset()`

Reglas:

- Empareja el `play` con un disparo propio pendiente por `x`, `y` y `look`.
- `powerScale = play[15][0].s[3] / game_shoot.power`.
- `gravity = play[15][0].s[5]` solo si `s[4] === 0` (sin viento horizontal).
  `s[5]` es la aceleración vertical efectiva; si hubiera viento vertical, se
  acepta como gravedad efectiva de la sala.
- Usa la mediana de las últimas 8 muestras **por móvil**: la gravedad y el
  origen cambian con el móvil, así que no se mezclan entre sí.
- `gravity` de un móvil sin muestras se **predice** con la tabla de
  coeficientes `a` del cliente y la unidad observada
  (`unidad = s[5] / a`; respaldo `390 / 75 = 5,2`). Ejemplo: si Armor observa
  `402`, la unidad es `5,36` y Boomer (`a = 62,5`) predice `335`.
- La tabla cubre los **21 móviles** conocidos (Armor, Boomer, Dragon2, Aduka,
  Ice, Fox, NAK, Kalsiddon, etc.). La gravedad, la trayectoria y la potencia se
  recalculan al cambiar de móvil; si el móvil no está en la tabla se usa 390 y
  se corrige con el primer disparo.
- La escala de potencia sí se comparte entre móviles como respaldo: si el móvil
  activo aún no tiene muestras, se usa la mediana global; si tampoco hay,
  `DEFAULT_PROFILE`.
- Fallback inicial: `powerScale = 2.269`, `gravity = 390`, origen Armor
  `(a=20.45, c=25.83)`.
- No actualiza `gravity` con `play` de otros jugadores.
- **Origen por móvil**: cada `play` propio resuelve el offset local `(a, c)` del
  cañón y lo guarda en el cubo del móvil. En modo `auto` (por defecto) se
  aplica la mediana cuando hay **3 muestras estables** (MAD ≤ 2 unidades); los
  móviles con origen ya ajustado (`TUNED_ORIGINS`, hoy Armor) conservan su
  valor y solo se miden. `calibrateOrigin: true` fuerza la medición desde la
  primera muestra; `false` solo mide.
- El perfil publica `mobile`, `gravitySource` (`observed` / `predicted` /
  `default`), `originSource` (`measured` / `tuned` / `default`), `samples` del
  cubo activo, `originMeasured` (mediana, MAD, muestras y estabilidad) y el
  `origin` aplicado. El panel muestra `CALIBRADO (n)`, `PREDICHO` o `FALLBACK`
  según la gravedad, y el origen en el tooltip (`origen medido (n)` /
  `origen fijo` / `origen por defecto`) y en `data-origin-source`.
- Los primeros disparos de un móvil sin origen ajustado usan el de Armor; a
  partir del tercer disparo estable se aplica el suyo.

## Pruebas

Abrir `test/auto-calibration.test.html` en un navegador. También se pueden
inyectar los tres archivos en la consola:

```text
src/auto-calibration.js
test/fixtures/captured-shots.js
test/auto-calibration.test.js
```

El resultado queda en `globalThis.__DS_TEST_RESULTS`. Estado actual: **27/27**.

La integración hook → overlay se prueba en `test/integration.test.html` y está
en **54/54**. Incluye selección por botón/capa sin bloquear el ratón, `Ctrl +
clic`, trayectoria a izquierda y derecha, `look=0/1`, apuntado espejado que se
alinea con el lado real (izquierda y derecha), marcador de potencia visible,
reset por sala, acoplamiento fuera del canvas y ocultar/mostrar el panel desde
el icono. Las fixtures usan payloads reales de la captura del 2026-10-20 y
no contienen el token de sesión.

## Limitaciones conocidas

- El hook de WebSocket es page-level: no cubre WebSockets creados dentro de
  Workers o iframes.
- El origen se auto-calibra con datos de `play`; los **tres primeros disparos**
  de un móvil sin origen ajustado usan el de Armor. Armor conserva su valor
  ajustado (`TUNED_ORIGINS`) y no se sustituye automáticamente.
- La calibración de potencia por **tipo de bala** sigue pendiente (§16.16 del
  diseño): los cubos son por móvil, no por `S1/S2/SS`. Si se cambia de bala, la
  escala puede variar ~3–4 %.
- El **origen** medido depende de que `play.s[0]/s[1]` llegue redondeado a
  píxel; la MAD descarta secuencias incoherentes, pero conviene validar cada
  móvil en vivo. Armor no se auto-sustituye para no degradar el ajuste actual.
- La colisión con terreno no se simula: la trayectoria se corta por viewport.
- La marca de potencia es visual; no se automatiza el disparo. Se dibuja sobre
  `#powerMarkArea`/`#powerMarks` (ancho 0..400) con contorno y aguja para que se
  vea sobre el fondo del juego.
- La trayectoria usa el ángulo real del cañón. Si el destino está en el lado
  contrario al apuntado, el panel muestra `OBJETIVO DETRÁS` hasta que el cañón
  gire; no se fuerza un ángulo distinto al del juego.
- El ciclo de turno/observador/reconexión está implementado de forma básica
  (`room_state`, `hashchange`, `Esc`); el turno se lee de la UI DOM. Faltan
  pruebas live G–O, resize/fullscreen y reconexión.
- La calibración live con un `pointerup` de potencia > 0 aún no se pudo
  confirmar en una partida completa; la física y el emparejamiento están
  cubiertos por las pruebas sintéticas.
- Falta auditar CSP/SRI y las condiciones de uso del servidor.
