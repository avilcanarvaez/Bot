/*
 * DragonShot — auto-calibración de física live.
 *
 * Entrada:
 *   - payload descifrado de `game_shoot` (cliente -> servidor)
 *   - payload descifrado de `play` (servidor -> cliente), índice 15
 *
 * El módulo no conoce DOM, WebSocket ni Pixi. Es intencionalmente puro para
 * poder probarlo con los payloads capturados y para reutilizarlo desde el
 * content script de la extensión.
 *
 * Perfil de respaldo:
 *   v0 = power * 2.269
 *   gravedad = 390
 *   origen Armor = (a=20.45, c=25.83)  -> offset en body=0: (20.45, -25.83)
 *
 * La auto-calibración reemplaza `powerScale` y `gravity` con la mediana de las
 * muestras observadas. El origen queda desactivado por defecto porque
 * `play.s[0]/s[1]` llega redondeado a píxel y podría empeorar la calibración
 * fina ya medida; se expone `calibrateOrigin` para diagnóstico.
 */
(function (root) {
  'use strict';

  const DEFAULT_PROFILE = Object.freeze({
    powerScale: 2.269,
    gravity: 390,
    // Parámetros locales (a, c) de la fórmula:
    //   dx = a*cos(theta) + c*sin(theta)
    //   dy = a*sin(theta) - c*cos(theta)
    // En body=0 el offset world es (a, -c) = (20.45, -25.83).
    origin: Object.freeze({ a: 20.45, c: 25.83 }),
  });

  // Orígenes ya ajustados a mano/en vivo. Para estos móviles la
  // auto-calibración mide pero no sustituye el valor: así no se degrada un
  // ajuste que ya funciona (Armor). El resto arranca con el vector `aim` de su
  // móvil y se afina tras `minOriginSamples` disparos estables.
  const TUNED_ORIGINS = Object.freeze({
    armor: DEFAULT_PROFILE.origin,
  });

  // Boca del cañón por móvil en el marco local del cuerpo: `[ángulo, radio]`
  // con 0° = horizontal hacia delante y positivo hacia arriba. Es la tabla
  // `aim` del cliente (helper.js:690-699) y coincide con el origen live de
  // Armor: 33·(cos 52°, sin 52°) = (20,32, 26,00) frente a (20,45, 25,83).
  // Así el disparo sale del mismo punto que el inicio del ángulo de cada móvil
  // desde el primer tiro, sin esperar a la auto-calibración.
  const MOBILE_AIM = Object.freeze({
    armor: Object.freeze([52, 33]),
    ice: Object.freeze([58, 50]),
    aduka: Object.freeze([130, 40]),
    lightning: Object.freeze([58, 44]),
    bigfoot: Object.freeze([58, 50]),
    jd: Object.freeze([68, 45]),
    asate: Object.freeze([40, 30]),
    random: Object.freeze([51, 51]),
    knight: Object.freeze([51, 51]),
    fox: Object.freeze([30, 44]),
    dragon: Object.freeze([58, 50]),
    dragon2: Object.freeze([58, 50]),
    nak: Object.freeze([130, 40]),
    trico: Object.freeze([51, 51]),
    mage: Object.freeze([51, 51]),
    turtle: Object.freeze([51, 51]),
    boomer: Object.freeze([130, 40]),
    electrico: Object.freeze([52, 33]),
    frog: Object.freeze([52, 33]),
    kalsiddon: Object.freeze([52, 33]),
    grub: Object.freeze([52, 33]),
  });

  const DEFAULTS = Object.freeze({
    maxShotAgeMs: 15000,
    windowSize: 8,
    minPower: 1,
    maxPower: 400,
    minPowerScale: 1.5,
    maxPowerScale: 3.5,
    gravityRequiresZeroWind: true,
    // 'auto': aplica el origen medido solo tras `minOriginSamples` muestras
    // estables y solo a móviles sin origen ajustado. `true` fuerza la
    // medición; `false` solo mide (no aplica).
    calibrateOrigin: 'auto',
    minOriginSamples: 3,
    // Mediana de las desviaciones (MAD) máxima, en unidades de mundo.
    maxOriginMad: 2,
    // Distancia máxima entre la medición y el vector `aim` para aceptarla.
    // Evita que una medición sesgada descalibre el origen del cliente.
    maxOriginSeedDelta: 8,
  });

  function asNumber(value) {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }

  function median(values) {
    if (!values || values.length === 0) return null;
    const sorted = values.slice().sort((a, b) => a - b);
    const mid = sorted.length >> 1;
    return sorted.length % 2
      ? sorted[mid]
      : (sorted[mid - 1] + sorted[mid]) / 2;
  }

  // Coeficiente `a` (gravedad relativa) de cada móvil, según la tabla del
  // cliente. La gravedad del mundo es `a · unidad`; la unidad se aprende de
  // `play.s[5]` y la del respaldo sale de Armor (390 / 75 = 5,2).
  const MOBILE_A = Object.freeze({
    armor: 75,
    ice: 63.5,
    aduka: 60.5,
    lightning: 65,
    bigfoot: 90,
    jd: 63.5,
    asate: 75.5,
    random: 81,
    knight: 65.5,
    fox: 61,
    dragon: 95,
    dragon2: 120,
    nak: 79.5,
    trico: 84,
    mage: 71.5,
    turtle: 74.5,
    boomer: 62.5,
    electrico: 73.5,
    frog: 65.5,
    kalsiddon: 65.5,
    grub: 63.5,
  });
  const ARMOR_A = MOBILE_A.armor;
  const DEFAULT_GRAVITY_UNIT = DEFAULT_PROFILE.gravity / ARMOR_A;

  // Factor de viento `b` por móvil (tabla del cliente, `script.js:22-110`).
  // El servidor aplica `windX = componente · b · unidad`; la unidad se aprende
  // de los disparos con viento y, mientras no haya muestras, se usa la escala
  // observada en vivo (≈5,36). No confundir con `a` (gravedad).
  const MOBILE_B = Object.freeze({
    armor: 0.76,
    ice: 0.625,
    aduka: 0.7,
    lightning: 0.72,
    bigfoot: 0.74,
    jd: 0.625,
    asate: 0.765,
    random: 0.827,
    knight: 0.695,
    fox: 0.61,
    dragon: 0.74,
    dragon2: 0.74,
    nak: 0.875,
    trico: 0.87,
    mage: 0.78,
    turtle: 0.75,
    boomer: 1.39,
    electrico: 0.74,
    frog: 0.74,
    kalsiddon: 0.74,
    grub: 0.69,
  });
  const DEFAULT_WIND_UNIT = 5.36;
  const MIN_WIND_UNIT = 1;
  const MAX_WIND_UNIT = 20;

  /**
   * Clave estable por móvil: quita ruta y extensión y normaliza el sufijo
   * `2D` del cliente (`/static/images/mobiles/Armor2D.png` -> `armor`).
   * Si el sufijo forma parte del nombre (`Dragon22D.png` -> `dragon2`) se
   * conserva porque la clave existe en la tabla.
   */
  function mobileKey(mobile) {
    const raw = mobile ? String(mobile) : '';
    const base = raw.split(/[\\/]/).pop()
      .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/\.(png|jpe?g|webp|gif)$/i, '')
      .toLowerCase()
      .replace(/[^a-z0-9]/g, '');
    if (!base) return 'unknown';
    if (MOBILE_A[base]) return base;
    const alt = base.replace(/2d$/, '');
    if (MOBILE_A[alt]) return alt;
    return base;
  }

  function normalizeMobile(mobile) {
    return mobileKey(mobile);
  }

  /**
   * Origen predicho por la tabla `aim` del móvil. Es el punto donde nace el
   * ángulo de disparo (boca del cañón) en el marco local del cuerpo.
   */
  function aimOrigin(mobile) {
    const aim = MOBILE_AIM[normalizeMobile(mobile)];
    if (!aim) return null;
    const rad = aim[0] * Math.PI / 180;
    return {
      a: aim[1] * Math.cos(rad),
      c: aim[1] * Math.sin(rad),
    };
  }

  /**
   * Separa por `sep` ignorando comas dentro de arrays, objetos y strings.
   */
  function splitTopLevel(text, sep) {
    const out = [];
    let start = 0;
    let depth = 0;
    let inString = false;
    let escaped = false;

    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (ch === '\\') {
          escaped = true;
        } else if (ch === '"') {
          inString = false;
        }
        continue;
      }
      if (ch === '"') {
        inString = true;
      } else if (ch === '[' || ch === '{') {
        depth++;
      } else if (ch === ']' || ch === '}') {
        depth--;
      } else if (ch === sep && depth === 0) {
        out.push(text.slice(start, i));
        start = i + 1;
      }
    }
    out.push(text.slice(start));
    return out;
  }

  /**
   * Quita el byte de opcode del payload descifrado. El live usa `:` (0x3A) en
   * server 5 y `\x07` en server 6. También acepta payloads que ya empiezan
   * por el dato (por ejemplo, un array JSON).
   */
  function stripOpcode(text) {
    const s = String(text == null ? '' : text).trim();
    if (!s) return s;
    const first = s[0];
    if (first === '[' || first === '{' || first === '-' || (first >= '0' && first <= '9')) {
      return s;
    }
    return s.slice(1).trim();
  }

  function parseGameShoot(raw) {
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
      const shot = {
        x: asNumber(raw.x),
        y: asNumber(raw.y),
        body: asNumber(raw.body),
        look: asNumber(raw.look),
        ang: asNumber(raw.ang),
        power: asNumber(raw.power),
        time: asNumber(raw.time),
        selectedShot: asNumber(raw.selectedShot),
        extra: raw.extra,
      };
      validateShot(shot);
      return shot;
    }

    const text = stripOpcode(raw);
    const fields = splitTopLevel(text, ',');
    if (fields.length < 8) {
      throw new Error('game_shoot: se esperaban >= 8 campos, llegaron ' + fields.length);
    }
    const shot = {
      x: asNumber(fields[0]),
      y: asNumber(fields[1]),
      body: asNumber(fields[2]),
      look: asNumber(fields[3]),
      ang: asNumber(fields[4]),
      power: asNumber(fields[5]),
      time: asNumber(fields[6]),
      selectedShot: asNumber(fields[7]),
      extra: fields.slice(8).join(',').trim() || null,
      raw: text,
    };
    validateShot(shot);
    return shot;
  }

  function validateShot(shot) {
    for (const key of ['x', 'y', 'body', 'look', 'ang', 'power', 'time']) {
      if (shot[key] === null) {
        throw new Error('game_shoot: campo inválido ' + key);
      }
    }
    if (shot.look !== 0 && shot.look !== 1) {
      throw new Error('game_shoot: look inválido ' + shot.look);
    }
  }

  /**
   * Extrae el primer array JSON balanceado. El live añade metadatos después del
   * array (por ejemplo `...,137330`), por eso no se puede usar JSON.parse
   * directo sobre todo el payload.
   */
  function extractJsonArray(text) {
    const s = String(text == null ? '' : text);
    const start = s.indexOf('[');
    if (start < 0) throw new Error('play: no contiene un array JSON');

    let depth = 0;
    let inString = false;
    let escaped = false;
    for (let i = start; i < s.length; i++) {
      const ch = s[i];
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (ch === '\\') {
          escaped = true;
        } else if (ch === '"') {
          inString = false;
        }
        continue;
      }
      if (ch === '"') {
        inString = true;
      } else if (ch === '[' || ch === '{') {
        depth++;
      } else if (ch === ']' || ch === '}') {
        depth--;
        if (depth === 0) return s.slice(start, i + 1);
        if (depth < 0) break;
      }
    }
    throw new Error('play: array JSON incompleto');
  }

  function parsePlay(raw) {
    if (Array.isArray(raw)) {
      if (raw[0] === 6 && Array.isArray(raw[1])) return raw[1];
      return raw;
    }
    const text = stripOpcode(raw);
    const json = extractJsonArray(text);
    const parsed = JSON.parse(json);
    if (parsed && parsed[0] === 6 && Array.isArray(parsed[1])) return parsed[1];
    return parsed;
  }

  /**
   * Resuelve (a, c) a partir de la posición del jugador y del origen que envió
   * el servidor. Solo se usa si `calibrateOrigin` está activo.
   */
  function solveOrigin(shot, s) {
    let dx = asNumber(s[0]) - shot.x;
    const dy = asNumber(s[1]) - shot.y;
    if (dx === null || dy === null) return null;
    if (shot.look === 0) dx = -dx;

    const theta = (shot.body || 0) * Math.PI / 180;
    const cos = Math.cos(theta);
    const sin = Math.sin(theta);
    const a = dx * cos + dy * sin;
    const c = dx * sin - dy * cos;

    if (!Number.isFinite(a) || !Number.isFinite(c)) return null;
    // Límite amplio: descarta datos corruptos, no pretende validar móviles.
    if (Math.abs(a) > 100 || Math.abs(c) > 100) return null;
    return { a: a, c: c };
  }

  class AutoCalibration {
    constructor(options) {
      this.options = Object.assign({}, DEFAULTS, options || {});
      this.now = this.options.now || function () { return Date.now(); };
      this.onChange = this.options.onChange || null;

      this.pendingShots = [];
      // Muestras por móvil: la gravedad y el origen cambian con el móvil, así
      // que no se pueden mezclar en un array global. La potencia se separa por
      // móvil y se comparte como respaldo (no parece depender del móvil).
      this.buckets = new Map();
      // Potencia global (todos los móviles): sirve de respaldo cuando el móvil
      // todavía no tiene muestras propias.
      this.powerSamples = [];
      // Unidades de gravedad observadas (`s[5] / a` del móvil). Sirven para
      // predecir la gravedad de un móvil que aún no ha disparado.
      this.gravityUnits = [];
      // Viento del turno que se está jugando (lo publica el `play` anterior).
      this.wind = null;
      // Unidad de viento aprendida de `s[4]` (magnitud) y signo por eje. El
      // signo absorbe la convención del ángulo que envía el servidor.
      this.windUnits = [];
      this.windSamplesX = [];
      this.windSamplesY = [];
      this.origins = new Map();
      this.last = null;
    }

    /**
     * Registra un disparo propio. `meta.mobile` es opcional y solo se usa para
     * la calibración opcional del origen.
     */
    observeGameShoot(raw, meta) {
      const shot = parseGameShoot(raw);
      if (shot.power < this.options.minPower || shot.power > this.options.maxPower) {
        return null;
      }
      shot.mobile = normalizeMobile((meta && meta.mobile) || shot.mobile);
      shot.at = this.now();
      // Viento que estaba activo cuando se disparó. Se guarda con el disparo
      // pendiente porque el `play` que lo confirma trae el viento del
      // **siguiente** turno, no el que actuó sobre este tiro.
      const metaWind = meta && meta.wind;
      const windPower = metaWind ? asNumber(metaWind.power) : null;
      const windAngle = metaWind ? asNumber(metaWind.angle) : null;
      shot.wind = windPower !== null && windAngle !== null
        ? { power: Math.max(0, windPower), angle: windAngle }
        : null;
      this.pendingShots.push(shot);
      if (this.pendingShots.length > this.options.windowSize) {
        this.pendingShots.shift();
      }
      return shot;
    }

    /**
     * Procesa un `play` y, si corresponde a un disparo propio pendiente,
     * actualiza la calibración. Devuelve un objeto con el resultado.
     */
    observePlay(raw) {
      const play = parsePlay(raw);
      if (!Array.isArray(play)) {
        return { matched: false, calibrated: false, reason: 'play-no-array' };
      }

      const x = asNumber(play[2]);
      const y = asNumber(play[3]);
      const look = asNumber(play[4]);
      const now = this.now();

      let matchIndex = -1;
      for (let i = this.pendingShots.length - 1; i >= 0; i--) {
        const shot = this.pendingShots[i];
        if (shot.x === x && shot.y === y && shot.look === look &&
            (now - shot.at) <= this.options.maxShotAgeMs) {
          matchIndex = i;
          break;
        }
      }
      if (matchIndex < 0) {
        return { matched: false, calibrated: false, reason: 'sin-disparo-propio-pendiente' };
      }

      const shot = this.pendingShots.splice(matchIndex, 1)[0];
      const data = Array.isArray(play[15]) ? play[15] : [];
      const entry = data.find(function (item) {
        return item && Array.isArray(item.s) && item.s.length >= 6 &&
          !item.thor && (item.time == null || item.time > 0);
      }) || data.find(function (item) {
        return item && Array.isArray(item.s) && item.s.length >= 6;
      });

      if (!entry) {
        return { matched: true, calibrated: false, shot: shot, reason: 'play-sin-datos-fisicos' };
      }

      const s = entry.s;
      const v0 = asNumber(s[3]);
      const windX = asNumber(s[4]);
      const vertAccel = asNumber(s[5]);
      const powerScale = v0 === null ? null : v0 / shot.power;

      if (powerScale === null || !Number.isFinite(powerScale) ||
          powerScale < this.options.minPowerScale ||
          powerScale > this.options.maxPowerScale) {
        return { matched: true, calibrated: false, shot: shot, reason: 'power-scale-fuera-de-rango', s: s };
      }

      const bucket = this.bucketFor(shot.mobile);
      bucket.power.push(powerScale);
      if (bucket.power.length > this.options.windowSize) bucket.power.shift();
      this.powerSamples.push(powerScale);
      if (this.powerSamples.length > this.options.windowSize) this.powerSamples.shift();

      let gravityUpdated = false;
      const hasWind = !!(shot.wind && shot.wind.power > 0);
      if (vertAccel !== null && vertAccel > 0 &&
          (!this.options.gravityRequiresZeroWind || (windX === 0 && !hasWind))) {
        bucket.gravity.push(vertAccel);
        if (bucket.gravity.length > this.options.windowSize) bucket.gravity.shift();
        const a = MOBILE_A[mobileKey(shot.mobile)];
        if (a) {
          this.gravityUnits.push(vertAccel / a);
          if (this.gravityUnits.length > this.options.windowSize) {
            this.gravityUnits.shift();
          }
        }
        gravityUpdated = true;
      }

      // El origen se mide siempre (es barato y no cambia el resultado), pero
      // solo se aplica si `calibrateOrigin` está activo. Así se puede leer el
      // valor real de un móvil nuevo (p. ej. Boomer) sin arriesgar el ajuste
      // fino que ya funciona.
      let origin = solveOrigin(shot, s);
      let originUpdated = false;
      if (origin) {
        const mobile = normalizeMobile(shot.mobile);
        const bucket = this.origins.get(mobile) || { a: [], c: [] };
        bucket.a.push(origin.a);
        bucket.c.push(origin.c);
        if (bucket.a.length > this.options.windowSize) bucket.a.shift();
        if (bucket.c.length > this.options.windowSize) bucket.c.shift();
        this.origins.set(mobile, bucket);
        originUpdated = true;
      }

      const baseGravityObserved = median(bucket.gravity);
      const baseGravityPredicted = this.predictGravity(shot.mobile);
      const baseGravity = baseGravityObserved !== null ? baseGravityObserved
        : (baseGravityPredicted !== null ? baseGravityPredicted : DEFAULT_PROFILE.gravity);
      const windLearned = this.learnWind(shot, windX, vertAccel, baseGravity);

      const delta = {
        powerScale: powerScale,
        v0: v0,
        windX: windX,
        vertAccel: vertAccel,
        wind: shot.wind || null,
        windLearned: !!windLearned,
        windSample: windLearned,
        windUnit: median(this.windUnits),
        windSignX: this.windSignX(),
        windSignY: this.windSignY(),
        gravityUpdated: gravityUpdated,
        origin: origin,
        originUpdated: originUpdated,
        s: s,
      };
      this.last = { shot: shot, entry: entry, delta: delta, at: now };

      const profile = this.getProfile(shot.mobile);
      if (this.onChange) this.onChange(profile, delta);
      return { matched: true, calibrated: true, shot: shot, entry: entry, delta: delta, profile: profile };
    }

    bucketFor(mobile) {
      const key = normalizeMobile(mobile);
      let bucket = this.buckets.get(key);
      if (!bucket) {
        bucket = { power: [], gravity: [] };
        this.buckets.set(key, bucket);
      }
      return bucket;
    }

    /**
     * Gravedad esperada de un móvil sin muestras propias, a partir de la
     * unidad observada (`s[5] / a`) o de la de respaldo. Devuelve null solo si
     * el móvil no está en la tabla.
     */
    predictGravity(mobile) {
      const a = MOBILE_A[mobileKey(mobile)];
      if (!a) return null;
      // Sin observaciones se usa la unidad de respaldo (Armor 390 / a=75), que
      // ya aproxima la gravedad real mejor que dejar la de Armor para todos.
      const unit = median(this.gravityUnits);
      return (unit === null ? DEFAULT_GRAVITY_UNIT : unit) * a;
    }

    /**
     * Origen medido de un móvil: mediana de `(a, c)` y MAD (mediana de las
     * distancias a la mediana). `stable` descarta secuencias incoherentes
     * (p. ej. un `body` mal resuelto o un `play` de otro jugador).
     */
    getOrigin(mobile) {
      const bucket = this.origins.get(normalizeMobile(mobile));
      if (!bucket || bucket.a.length === 0) return null;
      const samples = bucket.a.length;
      const a = median(bucket.a);
      const c = median(bucket.c);
      const deviations = [];
      for (let i = 0; i < samples; i++) {
        deviations.push(Math.hypot(bucket.a[i] - a, bucket.c[i] - c));
      }
      const mad = median(deviations);
      return {
        a: a,
        c: c,
        samples: samples,
        mad: mad,
        stable: mad <= this.options.maxOriginMad,
      };
    }

    /**
     * Viento del turno actual. El `play` publica `wind_power` y
     * `wind_angle + 180`; el page-hook llama a `setWind` con esos valores.
     */
    setWind(wind) {
      if (!wind) {
        this.wind = null;
        return null;
      }
      const power = asNumber(wind.power);
      const angle = asNumber(wind.angle);
      if (power === null || angle === null) {
        this.wind = null;
        return null;
      }
      this.wind = {
        power: Math.max(0, power),
        angle: ((angle % 360) + 360) % 360,
      };
      return this.getWind();
    }

    getWind() {
      return this.wind
        ? { power: this.wind.power, angle: this.wind.angle }
        : null;
    }

    /**
     * Signo de la componente X del viento. El servidor envía `wind_angle+180`;
     * el signo real se aprende del primer disparo con viento (`s[4]`) para no
     * depender de la convención de la versión del cliente. El valor por
     * defecto es el observado en vivo: viento a la izquierda con ángulo ~2°.
     */
    windSignX() {
      const sample = median(this.windSamplesX);
      if (sample === null || sample === 0) return -1;
      return sample < 0 ? -1 : 1;
    }

    windSignY() {
      const sample = median(this.windSamplesY);
      if (sample === null || sample === 0) return 1;
      return sample < 0 ? -1 : 1;
    }

    /**
     * Convierte el vector `(wind_power, wind_angle)` en aceleraciones del
     * mundo. El cliente usa `cos/sin · wind_power · b`; el servidor multiplica
     * por una unidad de viento que se aprende de `s[4]` (≈5,36 por defecto).
     *
     * Coordenadas de pantalla (Y hacia abajo):
     *   windX     = aceleración horizontal (+derecha)
     *   windY     = aceleración vertical del viento (+abajo)
     *   vertAccel = gravedad + windY
     *
     * El ángulo del `play` es `wind_angle + 180`; la convención observada en
     * vivo es `windX ∝ -cos(ángulo)` y `windY ∝ -sin(ángulo)` (el viento de
     * ángulo 2° sopla a la izquierda y ligeramente hacia arriba).
     */
    windEffect(mobile, wind, gravity) {
      const key = normalizeMobile(mobile);
      const b = MOBILE_B[key] === undefined ? 1 : MOBILE_B[key];
      const g = Number(gravity) || DEFAULT_PROFILE.gravity;
      const active = !!(wind && wind.power > 0);
      const unit = median(this.windUnits);
      const windUnit = unit === null ? DEFAULT_WIND_UNIT : unit;
      const signX = this.windSignX();
      const signY = this.windSignY();
      const power = active ? wind.power : 0;
      const angle = wind ? wind.angle : 0;
      const rad = angle * Math.PI / 180;
      const cosPart = active ? Math.round(Math.cos(rad) * power) : 0;
      const sinPart = active ? Math.round(Math.sin(rad) * power) : 0;
      const windX = active ? signX * cosPart * b * windUnit : 0;
      const windYUp = active ? signY * sinPart * b * windUnit : 0;
      const windY = -windYUp;
      return {
        active: active,
        power: power,
        angle: angle,
        b: b,
        unit: windUnit,
        unitSource: unit === null ? 'default' : 'measured',
        signX: signX,
        signY: signY,
        cosPart: cosPart,
        sinPart: sinPart,
        windX: windX,
        windY: windY,
        vertAccel: g + windY,
        gravity: g,
      };
    }

    /**
     * Aprende la unidad de viento y su signo de un disparo con viento.
     * `s[4]` es la aceleración horizontal y `s[5]` la vertical efectiva; la
     * gravedad base sale de las muestras sin viento o de la predicción del
     * móvil (nunca de `s[5]` de un tiro con viento).
     */
    learnWind(shot, windX, vertAccel, baseGravity) {
      const wind = shot && shot.wind;
      if (!wind || !(wind.power > 0) || windX === null || !Number.isFinite(windX)) {
        return null;
      }
      const b = MOBILE_B[mobileKey(shot.mobile)];
      if (b === undefined || !(b > 0)) return null;
      const rad = wind.angle * Math.PI / 180;
      const cosPart = Math.round(Math.cos(rad) * wind.power);
      const sinPart = Math.round(Math.sin(rad) * wind.power);
      const sample = { unit: null, signX: null, signY: null };

      if (Math.abs(cosPart) >= 1) {
        const raw = windX / (cosPart * b);
        if (Number.isFinite(raw) &&
            Math.abs(raw) >= MIN_WIND_UNIT && Math.abs(raw) <= MAX_WIND_UNIT) {
          sample.unit = Math.abs(raw);
          sample.signX = raw < 0 ? -1 : 1;
          this.windSamplesX.push(raw);
          if (this.windSamplesX.length > this.options.windowSize) {
            this.windSamplesX.shift();
          }
        }
      }
      if (vertAccel !== null && Number.isFinite(vertAccel) &&
          baseGravity > 0 && Math.abs(sinPart) >= 1) {
        const windYUp = baseGravity - vertAccel;
        const raw = windYUp / (sinPart * b);
        if (Number.isFinite(raw) &&
            Math.abs(raw) >= MIN_WIND_UNIT && Math.abs(raw) <= MAX_WIND_UNIT) {
          sample.signY = raw < 0 ? -1 : 1;
          this.windSamplesY.push(raw);
          if (this.windSamplesY.length > this.options.windowSize) {
            this.windSamplesY.shift();
          }
        }
      }
      if (sample.unit === null && sample.signY !== null &&
          vertAccel !== null && Number.isFinite(vertAccel) &&
          baseGravity > 0 && Math.abs(sinPart) >= 1) {
        // Sin componente horizontal útil (viento casi vertical): la unidad
        // sale de la componente vertical.
        const raw = (baseGravity - vertAccel) / (sinPart * b);
        if (Number.isFinite(raw) &&
            Math.abs(raw) >= MIN_WIND_UNIT && Math.abs(raw) <= MAX_WIND_UNIT) {
          sample.unit = Math.abs(raw);
        }
      }
      if (sample.unit !== null) {
        this.windUnits.push(sample.unit);
        if (this.windUnits.length > this.options.windowSize) {
          this.windUnits.shift();
        }
      }
      return sample.unit === null && sample.signX === null && sample.signY === null
        ? null : sample;
    }

    getProfile(mobile) {
      const key = normalizeMobile(mobile || (this.last && this.last.shot.mobile) || 'Armor2D.png');
      const bucket = this.buckets.get(key) || { power: [], gravity: [] };
      const ownPower = median(bucket.power);
      const powerScale = ownPower === null ? median(this.powerSamples) : ownPower;
      const observedGravity = median(bucket.gravity);
      const predictedGravity = observedGravity === null ? this.predictGravity(key) : null;
      const gravity = observedGravity !== null ? observedGravity
        : (predictedGravity !== null ? predictedGravity : DEFAULT_PROFILE.gravity);
      const gravitySource = observedGravity !== null ? 'observed'
        : (predictedGravity !== null ? 'predicted' : 'default');
      const measuredOrigin = this.getOrigin(key);
      const tunedOrigin = TUNED_ORIGINS[key] || null;
      const predictedOrigin = aimOrigin(key);
      let appliedOrigin = null;
      if (this.options.calibrateOrigin === true) {
        // Modo forzado (pruebas/ajuste manual): aplica desde la primera muestra.
        appliedOrigin = measuredOrigin;
      } else if (this.options.calibrateOrigin === 'auto' && !tunedOrigin &&
          measuredOrigin &&
          measuredOrigin.samples >= this.options.minOriginSamples &&
          measuredOrigin.stable) {
        // Solo refina si la medición está cerca del vector `aim` del móvil;
        // una muestra sesgada no debe mover el cañón fuera de su sitio.
        const seedDelta = predictedOrigin
          ? Math.hypot(measuredOrigin.a - predictedOrigin.a,
            measuredOrigin.c - predictedOrigin.c)
          : 0;
        if (seedDelta <= this.options.maxOriginSeedDelta) {
          appliedOrigin = measuredOrigin;
        }
      }
      const originSource = appliedOrigin ? 'measured'
        : (tunedOrigin ? 'tuned' : (predictedOrigin ? 'predicted' : 'default'));
      const origin = appliedOrigin || tunedOrigin || predictedOrigin ||
        DEFAULT_PROFILE.origin;
      const wind = this.windEffect(key, this.wind, gravity);

      return {
        mobile: key,
        powerScale: powerScale === null ? DEFAULT_PROFILE.powerScale : powerScale,
        gravity: gravity,
        gravitySource: gravitySource,
        origin: { a: origin.a, c: origin.c },
        offsetAtBody0: { x: origin.a, y: -origin.c },
        wind: wind,
        source: ownPower === null ? 'fallback' : 'observed',
        originSource: originSource,
        originCalibrated: originSource === 'measured',
        originMeasured: measuredOrigin ? {
          a: measuredOrigin.a,
          c: measuredOrigin.c,
          samples: measuredOrigin.samples,
          mad: measuredOrigin.mad,
          stable: measuredOrigin.stable,
        } : null,
        samples: {
          power: bucket.power.length,
          gravity: bucket.gravity.length,
          origin: measuredOrigin ? measuredOrigin.samples : 0,
          wind: this.windUnits.length,
        },
        pending: this.pendingShots.length,
      };
    }

    /**
     * Origen del cañón para el jugador y cuerpo actuales.
     * `player` usa coordenadas de mundo; `body` está en grados; `look` es 0/1.
     *
     * El offset local en `body=0` es `(a, -c)`. Al mirar a la izquierda el
     * sprite se espeja en su eje local X **antes** de rotar por `body`, igual
     * que `game.js` (`{x: x + dis, y: y - dy}` rotado por `body`):
     *
     *   dx = sx·a·cos(θ) + c·sin(θ)
     *   dy = sx·a·sin(θ) − c·cos(θ)
     *
     * Espejar solo `dx` después de rotar dejaba el origen del lado equivocado
     * con `body ≠ 0`.
     */
    originFor(player, body, look, mobile) {
      const profile = this.getProfile(mobile);
      const theta = (body || 0) * Math.PI / 180;
      const cos = Math.cos(theta);
      const sin = Math.sin(theta);
      const a = profile.origin.a;
      const c = profile.origin.c;
      const sx = look === 0 ? -1 : 1;
      const dx = sx * a * cos + c * sin;
      const dy = sx * a * sin - c * cos;
      return { x: player.x + dx, y: player.y + dy };
    }

    /**
     * Se llama al cambiar de sala o al reconectar. Mantiene los orígenes por
     * móvil porque no dependen de la sala.
     */
    resetPhysics() {
      this.pendingShots = [];
      this.buckets.clear();
      this.powerSamples = [];
      this.gravityUnits = [];
      this.wind = null;
      this.windUnits = [];
      this.windSamplesX = [];
      this.windSamplesY = [];
      this.last = null;
    }

    reset() {
      this.resetPhysics();
      this.origins.clear();
    }
  }

  const api = {
    AutoCalibration: AutoCalibration,
    DEFAULT_PROFILE: DEFAULT_PROFILE,
    DEFAULTS: DEFAULTS,
    parseGameShoot: parseGameShoot,
    parsePlay: parsePlay,
    median: median,
    splitTopLevel: splitTopLevel,
    MOBILE_A: MOBILE_A,
    MOBILE_B: MOBILE_B,
    MOBILE_AIM: MOBILE_AIM,
    DEFAULT_WIND_UNIT: DEFAULT_WIND_UNIT,
    aimOrigin: aimOrigin,
    mobileKey: mobileKey,
  };

  root.DragonShotAutoCalibration = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
