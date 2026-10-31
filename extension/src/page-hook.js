/*
 * DragonShot — hook de página (mundo MAIN).
 *
 * Se ejecuta en `document_start`, antes del bundle del juego. Hace tres cosas:
 *   1. envuelve WebSocket para leer/descifrar frames sin modificarlos;
 *   2. captura la instancia de Pixi.Application y busca al jugador propio;
 *   3. alimenta la auto-calibración y publica snapshots al overlay.
 *
 * No usa APIs de chrome.*: se comunica con el content script aislado por
 * window.postMessage.
 */
(function () {
  'use strict';

  const BRIDGE = 'dragonshot-ext';
  const OVERLAY = 'dragonshot-overlay';
  const XOR_C2S = 0xa5;
  const XOR_S2C = 0xdb;

  let calibration = null;
  let pixiApp = null;
  const pixiApps = [];
  let lastUrl = location.href;
  let shotPointer = null;
  // Modo "Seleccionar destino": el overlay deja pasar el ratón al juego para
  // apuntar, pero el clic final no debe crear una muestra de disparo.
  let selectionActive = false;
  let lastShotAt = 0;
  let lastShotPower = null;
  const MIN_SHOT_POWER = 5;
  let bindCandidates = 0;
  let bindCaptured = false;
  let lastBindKeys = null;
  let restoreBindPatch = null;
  let myPlayerIndex = null;
  let turnPlayerIndex = null;
  const playersByIndex = {};
  // Comparación entre el ángulo mostrado y el que el juego envió en el último
  // disparo real; sirve para diagnosticar desvíos sin abrir DevTools.
  let lastAimCheck = null;
  // Registro de disparos con viento, para validar el modelo en vivo. Se expone
  // como `window.__DS_WIND_LOG__` para poder copiarlo desde la consola del
  // juego con `copy(JSON.stringify(window.__DS_WIND_LOG__))`. No incluye token
  // de sesión ni datos del rival.
  const windLog = [];
  try {
    Object.defineProperty(window, '__DS_WIND_LOG__', {
      get: function () { return windLog; },
      configurable: true,
    });
  } catch (error) {
    window.__DS_WIND_LOG__ = windLog;
  }
  // Registro completo de disparos propios (cada `play` emparejado), con el
  // `s=[x0,y0,ang,v0,windX,vertAccel]` real del servidor. Sirve para calibrar
  // gravedad/viento/origen/powerScale de un móvil en vivo. Se expone como
  // `window.__DS_SHOT_LOG__` (sin token de sesión ni datos del rival).
  const shotLog = [];
  try {
    Object.defineProperty(window, '__DS_SHOT_LOG__', {
      get: function () { return shotLog; },
      configurable: true,
    });
  } catch (error) {
    window.__DS_SHOT_LOG__ = shotLog;
  }

  function getApi() {
    return window.DragonShotAutoCalibration || null;
  }

  function getCalibration() {
    if (!calibration) {
      const api = getApi();
      if (api) calibration = new api.AutoCalibration();
    }
    return calibration;
  }

  function post(type, payload) {
    try {
      window.postMessage(Object.assign({
        source: BRIDGE,
        type: type,
      }, payload || {}), '*');
    } catch (error) {
      // El juego puede deshabilitar postMessage en casos raros; no romper.
    }
  }

  // ---------------------------------------------------------------------------
  // WebSocket: observación pasiva
  // ---------------------------------------------------------------------------

  function decodeBytes(bytes, xor) {
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
      out += String.fromCharCode(bytes[i] ^ xor);
    }
    return out;
  }

  function decodeText(data, xor) {
    if (typeof data === 'string') {
      let out = '';
      for (let i = 0; i < data.length; i++) {
        out += String.fromCharCode(data.charCodeAt(i) ^ xor);
      }
      return out;
    }
    if (data instanceof ArrayBuffer) {
      return decodeBytes(new Uint8Array(data), xor);
    }
    if (ArrayBuffer.isView(data)) {
      return decodeBytes(new Uint8Array(data.buffer, data.byteOffset, data.byteLength), xor);
    }
    return null;
  }

  function tryShoot(text) {
    const api = getApi();
    if (!api) return null;
    let shot;
    try {
      shot = api.parseGameShoot(text);
    } catch (error) {
      return null;
    }
    if (!shot || shot.look !== 0 && shot.look !== 1) return null;
    if (!(shot.power >= 0 && shot.power <= 400)) return null;
    return shot;
  }

  function tryPlay(text) {
    const api = getApi();
    if (!api) return null;
    let play;
    try {
      play = api.parsePlay(text);
    } catch (error) {
      return null;
    }
    if (!Array.isArray(play) || play.length < 16 || !Array.isArray(play[15])) return null;
    if (typeof play[2] !== 'number' || typeof play[3] !== 'number') return null;
    const hasPhysics = play[15].some(function (entry) {
      return entry && Array.isArray(entry.s) && entry.s.length >= 6;
    });
    return hasPhysics ? play : null;
  }

  function parseWind(play) {
    if (!Array.isArray(play)) return null;
    const power = numberOr(play[13], null);
    const angle = numberOr(play[14], null);
    if (power === null || angle === null) return null;
    return { power: Math.max(0, power), angle: angle };
  }

  function tryPlayerInfo(text) {
    const api = getApi();
    if (!api) return null;
    let value;
    try {
      value = api.parsePlay(text);
    } catch (error) {
      return null;
    }
    if (!Array.isArray(value) || value[0] !== 10) return null;
    if (typeof value[1] !== 'number' || typeof value[5] !== 'string') return null;
    return {
      index: value[1],
      team: value[3],
      userId: value[4],
      name: value[5],
    };
  }

  function resolveMyPlayerIndex() {
    if (myPlayerIndex != null) return;
    const me = getMe();
    const name = me && me.player_info && me.player_info.name;
    if (!name) return;
    for (const key in playersByIndex) {
      if (playersByIndex[key] && playersByIndex[key].name === name) {
        myPlayerIndex = Number(key);
        return;
      }
    }
  }

  function onPlayerInfo(info) {
    playersByIndex[info.index] = {
      name: info.name,
      team: info.team,
      userId: info.userId,
    };
    resolveMyPlayerIndex();
    postSnapshot();
  }

  function noteTurn(raw) {
    const api = getApi();
    if (!api) return;
    let play;
    try {
      play = api.parsePlay(raw);
    } catch (error) {
      return;
    }
    if (!Array.isArray(play) || typeof play[1] !== 'number') return;
    turnPlayerIndex = play[1];
    const me = getMe();
    if (me && play[2] === me.x && play[3] === me.y && play[4] === me.look) {
      myPlayerIndex = play[1];
    }
    resolveMyPlayerIndex();
  }

  function tryRoomState(text) {
    const api = getApi();
    if (!api) return null;
    let value;
    try {
      value = api.parsePlay(text);
    } catch (error) {
      return null;
    }
    if (!Array.isArray(value) || value.length < 8) return null;
    if (value[0] !== 1 || typeof value[1] !== 'number' || typeof value[2] !== 'string') {
      return null;
    }
    return {
      roomType: value[0],
      id: value[1],
      title: value[2],
      maxPlayers: value[4],
      gameMode: value[5],
      map: value[6],
      maxWind: value[8],
      turnTime: value[15],
    };
  }

  function compactShot(shot) {
    return {
      x: shot.x,
      y: shot.y,
      body: shot.body,
      look: shot.look,
      ang: shot.ang,
      power: shot.power,
      time: shot.time,
      selectedShot: shot.selectedShot,
    };
  }

  function shortestDelta(a, b) {
    let d = (a - b) % 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    return d;
  }

  /**
   * Guarda la diferencia entre el ángulo que mostró la extensión y el que el
   * cliente envió realmente. Solo se registra si el jugador sigue en la misma
   * posición y lado que el disparo, para no comparar estados distintos.
   */
  function noteAimCheck(shot, me) {
    if (!me || !shot || (shot.look !== 0 && shot.look !== 1)) return;
    if (numberOr(me.look, null) !== shot.look) return;
    if (Math.abs(numberOr(me.x, 0) - shot.x) > 2) return;
    if (Math.abs(numberOr(me.y, 0) - shot.y) > 2) return;
    const aim = readAim(me);
    if (aim.deg == null) return;
    const truth = normalizeDeg((shot.look === 0 ? 180 - shot.ang : shot.ang) - shot.body);
    const model = alignAimToLook(aim.deg, shot.look, aim.source);
    lastAimCheck = {
      look: shot.look,
      body: shot.body,
      truth: Math.round(truth * 10) / 10,
      raw: Math.round(aim.deg * 10) / 10,
      model: Math.round(model * 10) / 10,
      residual: Math.round(shortestDelta(model, truth) * 10) / 10,
      at: Date.now(),
    };
  }

  function onShoot(shot, raw) {
    const cal = getCalibration();
    const me = getMe();
    if (typeof raw === 'string') noteAimCheck(shot, me);
    const mobile = mobileNameOf(me) || 'unknown';
    const observed = cal ? cal.observeGameShoot(raw, {
      mobile: mobile,
      wind: cal.getWind(),
    }) : null;
    lastShotAt = Date.now();
    lastShotPower = numberOr(shot && shot.power, lastShotPower);
    post('game-shoot', {
      shot: compactShot(observed || shot),
      profile: cal ? cal.getProfile(mobile) : null,
    });
    return observed;
  }

  function onPlay(raw) {
    noteTurn(raw);
    const cal = getCalibration();
    const me = getMe();
    const result = cal ? cal.observePlay(raw) : null;
    // El `play` trae el viento del **siguiente** turno. Se aplica después de
    // emparejar el disparo (que usa el viento guardado en `onShoot`) y antes
    // de publicar el snapshot, para que el panel y la potencia ya lo usen.
    const api = getApi();
    let play = null;
    try {
      play = api ? api.parsePlay(raw) : null;
    } catch (error) {
      play = null;
    }
    const wind = parseWind(play);
    if (cal && wind) cal.setWind(wind);
    if (!result) {
      postSnapshot();
      return;
    }
    if (result.delta && result.delta.wind && result.delta.wind.power > 0) {
      // Guarda solo lo imprescindible para validar el viento en vivo: móvil,
      // viento anunciado y las aceleraciones reales `s[4]`/`s[5]`. No se guarda
      // posición del rival ni token de sesión.
      windLog.push({
        t: Date.now(),
        mobile: mobileNameOf(me) || 'unknown',
        windPower: result.delta.wind.power,
        windAngle: result.delta.wind.angle,
        s4: result.delta.windX,
        s5: result.delta.vertAccel,
        unit: result.delta.windUnit,
        unitSource: result.delta.windUnitSource,
        signX: result.delta.windSignX,
        signY: result.delta.windSignY,
      });
      if (windLog.length > 200) windLog.shift();
    }
    // Registro completo del disparo (sirve para calibrar gravedad, viento,
    // origen y powerScale de un móvil en vivo).
    if (result.matched && result.delta && result.shot) {
      shotLog.push({
        t: Date.now(),
        mobile: mobileNameOf(me) || 'unknown',
        x: result.shot.x,
        y: result.shot.y,
        body: result.shot.body,
        look: result.shot.look,
        ang: result.shot.ang,
        power: result.shot.power,
        s: result.delta.s,
        wind: result.delta.wind || null,
      });
      if (shotLog.length > 200) shotLog.shift();
    }
    post('play', {
      matched: !!result.matched,
      calibrated: !!result.calibrated,
      reason: result.reason || null,
      profile: cal ? cal.getProfile(mobileNameOf(me)) : null,
      wind: cal ? cal.getWind() : null,
      delta: result.delta ? {
        powerScale: result.delta.powerScale,
        vertAccel: result.delta.vertAccel,
        windX: result.delta.windX,
        wind: result.delta.wind,
        windUnit: result.delta.windUnit,
        windSignX: result.delta.windSignX,
        windSignY: result.delta.windSignY,
        windLearned: !!result.delta.windLearned,
        gravityUpdated: result.delta.gravityUpdated,
      } : null,
      // Diagnóstico en vivo: ángulo real del servidor (`s[2]`) junto a la
      // lectura cruda del cañón en ese instante, para verificar la conversión.
      truth: result.delta && Array.isArray(result.delta.s)
        ? numberOr(result.delta.s[2], null) : null,
      raw: me ? readAim(me).deg : null,
      aimSource: me ? readAim(me).source : null,
      look: me ? numberOr(me.look, null) : null,
    });
    postSnapshot();
  }

  function onRoomState(room) {
    const cal = getCalibration();
    if (cal) cal.resetPhysics();
    lastShotPower = null;
    lastAimCheck = null;
    post('room-state', { room: room });
    postSnapshot();
  }

  function processFrame(text, direction) {
    if (!text) return;

    if (direction === 'out') {
      const shot = tryShoot(text);
      if (shot) onShoot(shot, text);
      return;
    }

    const playerInfo = tryPlayerInfo(text);
    if (playerInfo) {
      onPlayerInfo(playerInfo);
      return;
    }

    const play = tryPlay(text);
    if (play) {
      onPlay(text);
      return;
    }

    const room = tryRoomState(text);
    if (room) onRoomState(room);
  }

  function handleIncoming(data) {
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      data.arrayBuffer().then(function (buffer) {
        processFrame(decodeBytes(new Uint8Array(buffer), XOR_S2C), 'in');
      }).catch(function () {});
      return;
    }
    const text = decodeText(data, XOR_S2C);
    if (text) processFrame(text, 'in');
  }

  function handleOutgoing(data) {
    const text = decodeText(data, XOR_C2S);
    if (text) processFrame(text, 'out');
  }

  let nativeAddEventListener = null;

  function attachSocket(ws) {
    if (!ws || ws.__dsAttached) return;
    try {
      Object.defineProperty(ws, '__dsAttached', { value: true, configurable: true });
    } catch (error) {
      try {
        ws.__dsAttached = true;
      } catch (inner) {}
    }

    if (typeof nativeAddEventListener !== 'function') return;
    try {
      nativeAddEventListener.call(ws, 'message', function (event) {
        try {
          handleIncoming(event.data);
        } catch (error) {
          // Ignorar frames que no sean del juego.
        }
      });
      nativeAddEventListener.call(ws, 'open', function () {
        post('ws-open', { url: ws.url || null });
      });
      nativeAddEventListener.call(ws, 'close', function () {
        post('ws-close', {});
      });
    } catch (error) {}
  }

  function installWebSocketHook() {
    // No se reemplaza `window.WebSocket`: algunos clientes verifican el
    // constructor nativo. Se observan `send` y los listeners del prototipo.
    const proto = window.WebSocket && window.WebSocket.prototype;
    if (!proto || proto.__dsHooked) return;

    try {
      Object.defineProperty(proto, '__dsHooked', { value: true, configurable: true });
    } catch (error) {
      try {
        proto.__dsHooked = true;
      } catch (inner) {
        return;
      }
    }

    // No se envuelve `WebSocket.prototype.send`: DragonBound lo detecta y
    // rechaza la conexión (error 401/421). Los disparos se reconstruyen desde
    // el puntero y se confirman con el `play` entrante.

    try {
      const nativeAdd = proto.addEventListener;
      if (typeof nativeAdd === 'function') {
        nativeAddEventListener = nativeAdd;
        proto.addEventListener = function (type, listener, options) {
          if (type === 'message' && typeof listener === 'function') {
            try {
              attachSocket(this);
            } catch (error) {}
          }
          return nativeAdd.apply(this, arguments);
        };
      }
    } catch (error) {}

    // Algunos clientes usan `ws.onmessage = ...` en vez de addEventListener.
    try {
      const desc = Object.getOwnPropertyDescriptor(proto, 'onmessage');
      if (desc && typeof desc.set === 'function' && desc.configurable !== false) {
        Object.defineProperty(proto, 'onmessage', {
          configurable: true,
          enumerable: desc.enumerable,
          get: desc.get,
          set: function (handler) {
            try {
              attachSocket(this);
            } catch (error) {}
            return desc.set.call(this, handler);
          },
        });
      }
    } catch (error) {}
  }

  // ---------------------------------------------------------------------------
  // Pixi: captura de la aplicación y del jugador propio
  // ---------------------------------------------------------------------------

  let pixiValue;

  function hookApplicationPrototype(proto) {
    if (!proto || proto.__dsAppRenderHooked) return;
    try {
      const render = proto.render;
      if (typeof render !== 'function') return;
      proto.render = function () {
        try {
          captureApp(this);
        } catch (error) {}
        return render.apply(this, arguments);
      };
      proto.__dsAppRenderHooked = true;
    } catch (error) {}
  }

  function hookRendererPrototype(proto) {
    if (!proto || proto.__dsRendererHooked) return;
    try {
      const render = proto.render;
      if (typeof render !== 'function') return;
      proto.render = function (stage) {
        if (!pixiApp) {
          let root = stage;
          if (root && !root.children && root.container) root = root.container;
          if (root && root.children) {
            try {
              captureApp({ stage: root, renderer: this, canvas: this.view });
            } catch (error) {}
          }
        }
        return render.apply(this, arguments);
      };
      proto.__dsRendererHooked = true;
    } catch (error) {}
  }

  function hookPixi(pixi) {
    if (!pixi || pixi.__dsHooked) return;
    try {
      Object.defineProperty(pixi, '__dsHooked', { value: true, configurable: true });
    } catch (error) {
      try {
        pixi.__dsHooked = true;
      } catch (inner) {}
    }

    try {
      const OriginalApplication = pixi.Application;
      if (typeof OriginalApplication === 'function') {
        hookApplicationPrototype(OriginalApplication.prototype);
        const WrappedApplication = new Proxy(OriginalApplication, {
          construct: function (target, args, newTarget) {
            const app = Reflect.construct(target, args, newTarget);
            try {
              captureApp(app);
            } catch (error) {}
            return app;
          },
        });
        try {
          pixi.Application = WrappedApplication;
        } catch (error) {
          try {
            Object.defineProperty(pixi, 'Application', {
              configurable: true,
              writable: true,
              value: WrappedApplication,
            });
          } catch (inner) {}
        }
      }
    } catch (error) {}

    // Fallback: si el juego ya había creado la app y solo asigna window.PIXI
    // después, el primer render permite recuperar stage/renderer.
    try {
      if (typeof pixi.Renderer === 'function') {
        hookRendererPrototype(pixi.Renderer.prototype);
      }
    } catch (error) {}
  }

  function captureApp(app) {
    if (!app || !app.stage) return;
    let known = false;
    for (let i = 0; i < pixiApps.length; i++) {
      if (pixiApps[i].stage === app.stage) {
        known = true;
        if (app.canvas && !pixiApps[i].canvas) pixiApps[i].canvas = app.canvas;
        break;
      }
    }
    if (!known) {
      pixiApps.push({ stage: app.stage, canvas: app.canvas || null });
      if (pixiApps.length > 12) pixiApps.shift();
      post('pixi-ready', { ok: true });
    }
    pixiApp = app;
  }

  /**
   * El cliente no publica `window.PIXI`: Pixi vive en un closure. Pixi enlaza
   * sus handlers de interacción con `bind`; al observar ese `bind` se puede
   * recuperar el `InteractionManager` (que expone `stage` y `renderer`)
   * sin reemplazar constructores ni WebSocket.
   */
  function installBindCapture() {
    const nativeBind = Function.prototype.bind;
    if (typeof nativeBind !== 'function' || nativeBind.__dsHooked) return;

    function restore() {
      if (Function.prototype.bind === wrappedBind) {
        try {
          Function.prototype.bind = nativeBind;
        } catch (error) {}
      }
    }
    restoreBindPatch = restore;

    function wrappedBind(thisArg) {
      const bound = nativeBind.apply(this, arguments);
      try {
        if (thisArg && typeof thisArg === 'object') {
          bindCandidates++;
          try {
            lastBindKeys = Object.keys(thisArg).slice(0, 12);
          } catch (error) {}
        }
        const stage = thisArg && thisArg.stage;
        if (stage && Array.isArray(stage.children)) {
          const canvas = document.querySelector('#Dragon2D canvas') ||
            document.querySelector('canvas');
          captureApp({ stage: stage, canvas: canvas });
          if (visibleGameCanvas() && findMeIn(stage)) {
            bindCaptured = true;
            restore();
          }
        }
      } catch (error) {}
      return bound;
    }
    try {
      Object.defineProperty(wrappedBind, '__dsHooked', { value: true });
      wrappedBind.__dsHooked = true;
    } catch (error) {}

    try {
      Function.prototype.bind = wrappedBind;
    } catch (error) {}
  }

  function installPixiAccessor() {
    try {
      if (window.PIXI) {
        pixiValue = window.PIXI;
        hookPixi(pixiValue);
      }
      Object.defineProperty(window, 'PIXI', {
        configurable: true,
        enumerable: true,
        get: function () {
          return pixiValue;
        },
        set: function (value) {
          pixiValue = value;
          try {
            hookPixi(value);
          } catch (error) {}
        },
      });
    } catch (error) {
      // Si no se puede definir el acceso, el polling de abajo puede rescatar
      // una asignación posterior antes de que el juego borre window.PIXI.
    }

    const started = Date.now();
    const timer = setInterval(function () {
      try {
        if (window.PIXI) hookPixi(window.PIXI);
      } catch (error) {}
      if (Date.now() - started > 20000) clearInterval(timer);
    }, 25);
  }

  function walk(node, visit) {
    if (!node) return;
    visit(node);
    const children = node.children;
    if (!Array.isArray(children)) return;
    for (let i = 0; i < children.length; i++) {
      walk(children[i], visit);
    }
  }

  function findMeIn(stage) {
    if (!stage) return null;
    let me = null;
    walk(stage, function (node) {
      if (me) return;
      if (node && node.player_info && node.player_info.is_me) me = node;
    });
    return me;
  }

  function appWithMe() {
    if (!visibleGameCanvas()) return null;
    for (let i = pixiApps.length - 1; i >= 0; i--) {
      if (findMeIn(pixiApps[i].stage)) return pixiApps[i];
    }
    return null;
  }

  function getMe() {
    const app = appWithMe() || pixiApp;
    if (!app || !app.stage) return null;
    return findMeIn(app.stage);
  }

  function getOpponent() {
    const app = appWithMe() || pixiApp;
    if (!app || !app.stage) return null;
    let opponent = null;
    walk(app.stage, function (node) {
      if (opponent) return;
      const info = node && node.player_info;
      if (!info || info.is_me) return;
      if (info.hp != null && Number(info.hp) <= 0) return;
      opponent = node;
    });
    return opponent;
  }

  function numberOr(value, fallback) {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
  }

  function stringOrNull(value) {
    return typeof value === 'string' && value ? value : null;
  }

  function mobileNameOf(node) {
    if (!node) return null;
    const info = node.player_info || {};
    const direct = stringOrNull(info.mobile) || stringOrNull(node.mobile) ||
      stringOrNull(info.mobile_name) || stringOrNull(node.mobile_name) ||
      stringOrNull(info.mobile_n) || stringOrNull(node.mobile_n);
    if (direct) return direct;
    if (node.mobile && typeof node.mobile === 'object') {
      return stringOrNull(node.mobile.filename) ||
        stringOrNull(node.mobile.name) ||
        stringOrNull(node.mobile.mobile_name) ||
        stringOrNull(node.mobile.mobile_n);
    }
    return null;
  }

  function normalizeDeg(value) {
    let deg = value % 360;
    if (deg < 0) deg += 360;
    return deg;
  }

  /**
   * Dirección del cañón según el nodo `angle`, en grados matemáticos
   * (0 = derecha, 90 = arriba).
   *
   * Pixi usa Y hacia abajo y rotación positiva horaria, así que el ángulo
   * matemático es `-atan2(y, x)`. Se prefiere el `worldTransform` porque
   * incluye el `body` del contenedor y los espejos de la cadena de padres: la
   * columna (a, b) es el eje local +x ya transformado.
   *
   * Ojo: esta lectura puede venir espejada respecto a la vertical (leer 60°
   * cuando el cañón apunta a 120°). El espejo del sprite no siempre está en la
   * matriz y por eso `alignAimToLook` reconcilia el resultado con `me.look`.
   */
  function readAim(me) {
    const node = me && me.angle;
    const wt = node && node.worldTransform;
    if (wt && Number.isFinite(wt.a) && Number.isFinite(wt.b) &&
        (Math.abs(wt.a) > 1e-9 || Math.abs(wt.b) > 1e-9)) {
      return {
        deg: normalizeDeg(-Math.atan2(wt.b, wt.a) * 180 / Math.PI),
        rotationDeg: normalizeDeg(Math.atan2(wt.b, wt.a) * 180 / Math.PI),
        source: 'world',
      };
    }
    const rotation = numberOr(node && node.rotation, null);
    if (rotation === null) return { deg: null, rotationDeg: null, source: null };
    // Sin worldTransform no se puede componer el padre; se usa la rotación
    // local como aproximación (misma convención de Pixi).
    return {
      deg: normalizeDeg(-rotation * 180 / Math.PI),
      rotationDeg: normalizeDeg(rotation * 180 / Math.PI),
      source: 'rotation',
    };
  }

  /**
   * Convierte la lectura del nodo del cañón al ángulo absoluto de salida.
   *
   * El nodo del cañón (`me.angle`) cuelga de un contenedor que se espeja
   * verticalmente cuando el móvil mira a la izquierda. Por eso el
   * `worldTransform` de ese lado devuelve la dirección con el eje Y invertido:
   *
   *     raw = 180 + ang + body   (look = 0)
   *
   * y el ángulo real (el que usa el servidor en `play`) es `ang + body` visto
   * desde la izquierda, es decir:
   *
   *     real = 180 - ang - body = -raw   (mod 360)
   *
   * Mirando a la derecha no hay espejo y `raw` ya es el ángulo absoluto
   * (`real = raw`). Comprobado en vivo: con look=1 y raw=24° el cañón apuntaba
   * a 28° (el rival); con look=0 y raw=235° el dial del juego marcaba ~135°.
   *
   * Solo se aplica a la lectura `world`; la de respaldo (`rotation`) es local y
   * no permite componer el espejo, así que se deja como estaba.
   */
  function alignAimToLook(deg, look, source) {
    if (deg == null) return deg;
    if (source === 'world') {
      return normalizeDeg(look === 0 ? -deg : deg);
    }
    if (look !== 0 && look !== 1) return deg;
    const cos = Math.cos(deg * Math.PI / 180);
    if (Math.abs(cos) < 0.1) return deg;
    const pointsLeft = cos < 0;
    const wantsLeft = look === 0;
    if (pointsLeft === wantsLeft) return deg;
    return normalizeDeg(180 - deg);
  }

  /**
   * Lado hacia el que mira el móvil (0 = izquierda, 1 = derecha).
   *
   * Manda `me.look`, que es el estado declarado por el juego. El signo del
   * apuntado no se usa como fuente principal: la lectura del cañón puede venir
   * espejada y usarla aquí creaba un círculo vicioso que colocaba el origen en
   * el lado contrario. Queda solo como último recurso si no hay `look` ni
   * `scale`.
   */
  function detectLook(me, aimDeg) {
    const declared = numberOr(me && me.look, null);
    if (declared === 0 || declared === 1) return { look: declared, source: 'look' };
    const scaleX = me && me.scale ? numberOr(me.scale.x, null) : null;
    if (scaleX != null) return { look: scaleX < 0 ? 0 : 1, source: 'scale' };
    if (aimDeg != null) {
      return { look: Math.cos(aimDeg * Math.PI / 180) < 0 ? 0 : 1, source: 'aim' };
    }
    return { look: 1, source: 'default' };
  }

  function readPower() {
    const bar = document.querySelector('#powerBar');
    const last = document.querySelector('#last_power_mark');
    const marks = document.querySelector('#powerMarks');
    const max = marks ? numberOr(marks.getBoundingClientRect().width, 315) : 315;
    let current = 0;
    if (bar) {
      const raw = String(bar.style.width || '0');
      current = numberOr(parseFloat(raw), 0);
      if (raw.indexOf('%') >= 0) current = max * current / 100;
    }
    const lastPower = last ? numberOr(parseFloat(last.style.left), 247) - 247 : null;
    return { current: current, last: lastPower };
  }

  function readDomTurn() {
    if (!visibleGameCanvas()) return null;
    const first = document.querySelector('#turn_line0');
    const mine = document.querySelector('.turn_line.turn_line_me');
    if (!first) return null;
    if (first.classList && first.classList.contains('turn_line_me')) return true;
    if (mine) return false;
    return null;
  }

  function isOverlayEvent(event) {
    let path = [];
    try {
      path = event.composedPath ? event.composedPath() : [];
    } catch (error) {}
    for (let i = 0; i < path.length; i++) {
      const node = path[i];
      if (!node) continue;
      if (node.id === 'dragonshot-extension-root') return true;
      if (node.classList && (node.classList.contains('ds-panel') ||
          node.classList.contains('ds-btn'))) return true;
    }
    return false;
  }

  function pointerInsideCanvas(event, snap) {
    const rect = snap && snap.canvas && snap.canvas.rect;
    if (!rect) return false;
    return event.clientX >= rect.left && event.clientX <= rect.right &&
      event.clientY >= rect.top && event.clientY <= rect.bottom;
  }

  function onPointerDown(event) {
    if (selectionActive || event.button !== 0 || event.ctrlKey || isOverlayEvent(event)) {
      shotPointer = null;
      return;
    }
    shotPointer = {
      id: event.pointerId == null ? null : event.pointerId,
      at: Date.now(),
      x: event.clientX,
      y: event.clientY,
      power: readPower().current,
    };
  }

  /**
   * DragonBound rechaza que se envuelva `WebSocket.prototype.send`, así que el
   * disparo propio se reconstruye al soltar el puntero. El `play` entrante
   * confirma después la muestra y aporta la física real.
   */
  function onPointerUp(event) {
    if (selectionActive || event.button !== 0 || event.ctrlKey || isOverlayEvent(event)) {
      shotPointer = null;
      return;
    }
    const down = shotPointer;
    shotPointer = null;
    if (!down) return;
    if (down.id != null && event.pointerId != null && down.id !== event.pointerId) return;
    if (Date.now() - down.at > 8000) return;

    const snap = snapshot();
    if (!snap || !snap.ok || !snap.me || !snap.me.isTurn) return;
    if (!pointerInsideCanvas(event, snap)) return;

    const power = numberOr(snap.power && snap.power.current, 0);
    if (!(power >= MIN_SHOT_POWER && power <= 400)) return;

    const moved = down.x != null && down.y != null &&
      (Math.abs(event.clientX - down.x) + Math.abs(event.clientY - down.y) > 3);
    // La barra conserva la última potencia. Un clic sin arrastre no es un
    // disparo y no debe crear una muestra pendiente.
    if (lastShotPower != null && Math.abs(power - lastShotPower) < 0.5 && !moved) {
      return;
    }

    // `ang` en `game_shoot` es el ángulo relativo al cuerpo que usa el
    // cliente (0..90): se reconstruye desde el absoluto, no se envía el
    // absoluto tal cual.
    const abs = snap.me.angAbs == null ? 0 : snap.me.angAbs;
    const clientAng = snap.me.look === 0
      ? 180 - abs - snap.me.body
      : abs + snap.me.body;
    const raw = {
      x: snap.me.x,
      y: snap.me.y,
      body: snap.me.body,
      look: snap.me.look,
      ang: normalizeDeg(clientAng),
      power: Math.round(power),
      time: 0,
      selectedShot: 0,
    };
    try {
      onShoot(raw, raw);
    } catch (error) {
      // Un clic del juego que no sea un disparo no debe romper nada.
    }
  }

  function visibleGameCanvas() {
    const div = document.querySelector('#Dragon2D');
    if (!div) return null;
    const style = window.getComputedStyle(div);
    if (style.display === 'none' || style.visibility === 'hidden') return null;
    const canvas = div.querySelector('canvas');
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    if (!(rect.width > 0 && rect.height > 0)) return null;
    return canvas;
  }

  function canvasInfo(app) {
    const gameCanvas = visibleGameCanvas();
    const fallbackCanvas = document.querySelector('#Dragon2D canvas') ||
      document.querySelector('canvas');
    const canvas = gameCanvas ||
      (app.canvas || (app.renderer && app.renderer.view) || app.view || fallbackCanvas);
    if (!canvas || !canvas.getBoundingClientRect) return null;
    const rect = canvas.getBoundingClientRect();
    return {
      width: numberOr(canvas.width, rect.width),
      height: numberOr(canvas.height, rect.height),
      rect: {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
        right: rect.right,
        bottom: rect.bottom,
      },
    };
  }

  function getProfile(mobile) {
    const cal = getCalibration();
    return cal ? cal.getProfile(mobile) : null;
  }

  function snapshot() {
    if (!visibleGameCanvas()) {
      return {
        ok: false,
        reason: 'sin-juego',
        hasPixi: pixiApps.length > 0 || !!pixiApp,
        url: location.href,
      };
    }
    const app = appWithMe();
    if (!app) {
      const hasPixi = pixiApps.length > 0 || !!pixiApp;
      return {
        ok: false,
        reason: hasPixi ? 'sin-jugador' : 'sin-pixi',
        hasPixi: hasPixi,
        url: location.href,
      };
    }
    pixiApp = app;

    const me = findMeIn(app.stage);
    if (!me) {
      return { ok: false, reason: 'sin-jugador', hasPixi: true, url: location.href };
    }
    if (!bindCaptured) {
      bindCaptured = true;
      if (restoreBindPatch) restoreBindPatch();
    }

    const root = app.stage && app.stage.children && app.stage.children[0];
    let zoom = root && root.scale ? numberOr(root.scale.x, 1) : 1;
    if (!zoom) zoom = 1;

    const meX = numberOr(me.x, 0);
    const meY = numberOr(me.y, 0);
    let camera = { x: 0, y: 0 };
    const wt = me.worldTransform;
    if (wt && Number.isFinite(wt.tx) && Number.isFinite(wt.ty)) {
      camera = {
        x: wt.tx / zoom - meX,
        y: wt.ty / zoom - meY,
      };
    } else if (me.parent) {
      camera = { x: numberOr(me.parent.x, 0), y: numberOr(me.parent.y, 0) };
    }

    const body = numberOr(me.body_angle, numberOr(me.body, 0));
    const aim = readAim(me);
    const lookInfo = detectLook(me, aim.deg);
    const look = lookInfo.look;
    // `angAbs` es el ángulo absoluto de salida en coordenadas matemáticas
    // (Y hacia arriba), que es el que usa la física de §7.4. `aim.deg` es la
    // lectura cruda de Pixi y `angAbs` la versión reconciliada con `look`.
    const angAbs = alignAimToLook(aim.deg, look, aim.source);
    const aimMirrored = aim.deg != null && angAbs != null &&
      Math.abs(aim.deg - angAbs) > 0.5;
    const mobile = mobileNameOf(me) || 'unknown';
    const cal = getCalibration();
    const profile = cal ? cal.getProfile(mobile) : null;
    const origin = cal ? cal.originFor({ x: meX, y: meY }, body, look, mobile) : null;
    const opponent = getOpponent();
    let isTurn = !!(me.is_turn || (me.player_info && me.player_info.is_turn));
    // La UI de turnos es la fuente live fiable: la primera línea es el jugador
    // activo y lleva `turn_line_me` cuando es el turno propio.
    const domTurn = readDomTurn();
    if (domTurn !== null) isTurn = domTurn;

    return {
      ok: true,
      url: location.href,
      ts: Date.now(),
      me: {
        x: meX,
        y: meY,
        look: look,
        lookSource: lookInfo.source,
        flip: typeof me.flip === 'boolean' ? me.flip : null,
        body: body,
        scaleX: me.scale ? numberOr(me.scale.x, null) : null,
        aimDeg: angAbs,
        aimRaw: aim.deg,
        aimMirrored: aimMirrored,
        aimRotation: aim.rotationDeg,
        aimSource: aim.source,
        angAbs: angAbs,
        mobile: mobile,
        isTurn: isTurn,
      },
      opponent: opponent ? {
        x: numberOr(opponent.x, 0),
        y: numberOr(opponent.y, 0),
        mobile: mobileNameOf(opponent) || 'unknown',
        hp: numberOr(opponent.player_info && opponent.player_info.hp, null),
      } : null,
      camera: camera,
      zoom: zoom,
      canvas: canvasInfo(pixiApp),
      profile: profile,
      wind: cal ? cal.getWind() : null,
      origin: origin,
      power: readPower(),
      aimCheck: lastAimCheck,
    };
  }

  function postSnapshot() {
    post('snapshot', { snapshot: snapshot() });
  }

  // ---------------------------------------------------------------------------
  // Puente con el overlay aislado
  // ---------------------------------------------------------------------------

  window.addEventListener('message', function (event) {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.source !== OVERLAY) return;

    if (data.type === 'get-snapshot') {
      postSnapshot();
    } else if (data.type === 'selection-mode') {
      selectionActive = !!data.active;
      shotPointer = null;
    } else if (data.type === 'reset-calibration') {
      const cal = getCalibration();
      if (cal) cal.resetPhysics();
      postSnapshot();
    }
  });

  window.addEventListener('hashchange', function () {
    if (location.href === lastUrl) return;
    lastUrl = location.href;
    lastShotPower = null;
    lastAimCheck = null;
    const cal = getCalibration();
    if (cal) cal.resetPhysics();
    postSnapshot();
  });

  window.addEventListener('pointerdown', onPointerDown, true);
  window.addEventListener('pointerup', onPointerUp, true);
  window.addEventListener('pointercancel', function () {
    shotPointer = null;
  }, true);

  function nodeDebug(node) {
    if (!node) return null;
    const wt = node.worldTransform;
    const info = node.player_info || null;
    const out = {
      x: numberOr(node.x, null),
      y: numberOr(node.y, null),
      scaleX: node.scale ? numberOr(node.scale.x, null) : null,
      scaleY: node.scale ? numberOr(node.scale.y, null) : null,
      rotation: numberOr(node.rotation, null),
      mobile: mobileNameOf(node),
      mobileType: typeof node.mobile,
      mobileKeys: node.mobile && typeof node.mobile === 'object'
        ? Object.keys(node.mobile).slice(0, 20) : null,
      ownKeys: Object.keys(node).slice(0, 30),
      parentKeys: node.parent ? Object.keys(node.parent).slice(0, 20) : null,
      parentX: node.parent ? numberOr(node.parent.x, null) : null,
      parentY: node.parent ? numberOr(node.parent.y, null) : null,
      parentScaleX: node.parent && node.parent.scale
        ? numberOr(node.parent.scale.x, null) : null,
    };
    if (wt) {
      out.worldTransform = {
        a: numberOr(wt.a, null),
        b: numberOr(wt.b, null),
        c: numberOr(wt.c, null),
        d: numberOr(wt.d, null),
        tx: numberOr(wt.tx, null),
        ty: numberOr(wt.ty, null),
      };
    }
    if (info) {
      out.info = {
        keys: Object.keys(info).slice(0, 40),
        isMe: !!info.is_me,
        isTurn: !!(info.is_turn || node.is_turn),
        mobile: stringOrNull(info.mobile),
        mobileType: typeof info.mobile,
        hp: numberOr(info.hp, null),
      };
    }
    return out;
  }

  /**
   * Árbol acotado del nodo propio para identificar qué hijo es el cañón.
   * Solo para diagnóstico manual: no se publica en snapshots ni se versiona
   * ningún frame de red.
   */
  function describeNode(node, depth) {
    if (!node) return null;
    const out = {
      cls: node.constructor && node.constructor.name ? String(node.constructor.name) : null,
      name: typeof node.name === 'string' ? node.name : null,
      label: typeof node.label === 'string' ? node.label : null,
      x: numberOr(node.x, null),
      y: numberOr(node.y, null),
      rotation: numberOr(node.rotation, null),
      angle: numberOr(node.angle, null),
      scaleX: node.scale ? numberOr(node.scale.x, null) : null,
      visible: node.visible !== false,
      flip: typeof node.flip === 'boolean' ? node.flip
        : (numberOr(node.flip, null)),
      look: numberOr(node.look, null),
      lookBool: typeof node.look === 'boolean' ? node.look : null,
      bodyAngle: numberOr(node.body_angle, null),
      isMe: !!(node.player_info && node.player_info.is_me),
      keys: Object.keys(node).slice(0, 40),
    };
    const wt = node.worldTransform;
    if (wt) {
      out.wt = {
        a: numberOr(wt.a, null),
        b: numberOr(wt.b, null),
        deg: Math.round(normalizeDeg(-Math.atan2(wt.b, wt.a) * 180 / Math.PI) * 10) / 10,
      };
    }
    if (depth > 0 && node.children) {
      out.children = [];
      for (let i = 0; i < node.children.length && i < 40; i++) {
        out.children.push(describeNode(node.children[i], depth - 1));
      }
    }
    return out;
  }

  // Debug acotado para pruebas manuales. No incluye frames ni tokens.
  window.__DS_EXT_HOOK__ = {
    snapshot: snapshot,
    profile: function (mobile) {
      const cal = getCalibration();
      if (!cal) return null;
      if (mobile) return cal.getProfile(mobile);
      const me = getMe();
      return cal.getProfile(me ? mobileNameOf(me) : undefined);
    },
    reset: function () {
      const cal = getCalibration();
      if (cal) cal.resetPhysics();
    },
    debug: function () {
      return {
        hasPixi: !!pixiApp,
        bindCaptured: bindCaptured,
        bindCandidates: bindCandidates,
        lastBindKeys: lastBindKeys,
        myPlayerIndex: myPlayerIndex,
        turnPlayerIndex: turnPlayerIndex,
        players: Object.keys(playersByIndex).length,
        gameActive: !!visibleGameCanvas(),
        canvas: !!(document.querySelector('#Dragon2D canvas') ||
          document.querySelector('canvas')),
        url: location.href,
      };
    },
    debugStage: function () {
      const app = appWithMe();
      const me = app ? findMeIn(app.stage) : null;
      return {
        stages: pixiApps.length,
        hasMe: !!me,
        me: nodeDebug(me),
        parent: nodeDebug(me && me.parent),
        root: nodeDebug(app && app.stage.children && app.stage.children[0]),
        stage: nodeDebug(app && app.stage),
        canvas: app ? canvasInfo(app) : null,
      };
    },
    tree: function () {
      const app = appWithMe() || pixiApp;
      const me = app && app.stage ? findMeIn(app.stage) : null;
      return describeNode(me, 3);
    },
  };

  installWebSocketHook();
  installBindCapture();
  installPixiAccessor();
})();
