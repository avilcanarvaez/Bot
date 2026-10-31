/*
 * Pruebas de auto-calibración con payloads reales.
 *
 * Se pueden ejecutar en cualquier navegador abriendo
 * `extension/test/auto-calibration.test.html`, o inyectando este archivo junto
 * con `../src/auto-calibration.js` y `fixtures/captured-shots.js`.
 */
(function (root) {
  'use strict';

  const api = root.DragonShotAutoCalibration;
  const fixtures = root.DragonShotFixtures;
  const results = [];

  function test(name, fn) {
    try {
      fn();
      results.push({ name: name, ok: true });
    } catch (error) {
      results.push({ name: name, ok: false, error: String(error) });
    }
  }

  function assert(condition, message) {
    if (!condition) throw new Error(message || 'assert falló');
  }

  function approx(actual, expected, epsilon, message) {
    const eps = epsilon == null ? 1e-6 : epsilon;
    if (!(Math.abs(actual - expected) <= eps)) {
      throw new Error((message || 'approx') + ': esperado ' + expected + ', obtenido ' + actual);
    }
  }

  function playFor(fixture, overrides) {
    const play = new Array(16).fill(null);
    play[0] = 0;
    play[2] = overrides && overrides.x != null ? overrides.x : fixture.x;
    play[3] = overrides && overrides.y != null ? overrides.y : fixture.y;
    play[4] = overrides && overrides.look != null ? overrides.look : fixture.look;
    play[15] = [{
      s: (overrides && overrides.s) || fixture.s,
      time: overrides && overrides.time != null ? overrides.time : fixture.time,
    }];
    return play;
  }

  test('parsea game_shoot de server 5 (opcode ":")', function () {
    const shot = api.parseGameShoot(':931,13,0,0,20,223,6803,0,[0,10.4,2321]');
    assert(shot.x === 931 && shot.y === 13, 'posición');
    assert(shot.look === 0, 'look');
    assert(shot.ang === 20, 'ang');
    assert(shot.power === 223, 'power');
  });

  test('parsea game_shoot de server 6 (opcode 0x07)', function () {
    const shot = api.parseGameShoot('\x07931,13,0,0,20,223,6803,0,[0,10.4,2321]');
    assert(shot.power === 223, 'power');
    assert(shot.selectedShot === 0, 'selectedShot');
  });

  test('parsea play con metadatos después del array', function () {
    const raw = '\x06[8,0,931,13,0,900,1,[],0,0,0,0,0,0,0,' +
      '[{"s":[911,-13,153,461,0,402],"time":1175}],0],137330';
    const play = api.parsePlay(raw);
    assert(play[2] === 931 && play[3] === 13 && play[4] === 0, 'posición/look');
    assert(play[15][0].s[3] === 461, 'v0');
  });

  test('perfil de respaldo antes de cualquier muestra', function () {
    const cal = new api.AutoCalibration();
    const profile = cal.getProfile('Armor2D.png');
    approx(profile.powerScale, 2.269, 1e-12, 'powerScale');
    approx(profile.gravity, 390, 1e-12, 'gravity');
    approx(profile.origin.a, 20.45, 1e-12, 'origin.a');
    approx(profile.origin.c, 25.83, 1e-12, 'origin.c');
    assert(profile.source === 'fallback', 'source');
    assert(profile.samples.power === 0, 'samples.power');
  });

  test('un disparo look=0 actualiza powerScale y gravity', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    const result = cal.observePlay(playFor(f));
    assert(result.matched && result.calibrated, 'matched/calibrated');
    const profile = cal.getProfile(f.mobile);
    approx(profile.powerScale, 461 / 195, 1e-12, 'powerScale');
    approx(profile.gravity, 402, 1e-12, 'gravity');
    assert(profile.source === 'observed', 'source');
    assert(profile.samples.power === 1 && profile.samples.gravity === 1, 'samples');
  });

  test('cuatro disparos look=0 usan mediana robusta', function () {
    const cal = new api.AutoCalibration();
    for (const f of fixtures.look0) {
      cal.observeGameShoot(f.shoot, { mobile: f.mobile });
      const result = cal.observePlay(playFor(f));
      assert(result.matched && result.calibrated, f.name);
    }
    const profile = cal.getProfile(fixtures.look0[0].mobile);
    // 2.3625, 2.3632287, 2.364, 2.3641026 -> mediana
    approx(profile.powerScale, 2.3636143497757846, 1e-12, 'powerScale');
    approx(profile.gravity, 402, 1e-12, 'gravity');
    assert(profile.samples.power === 4, 'power samples');
    assert(profile.samples.gravity === 4, 'gravity samples');
    assert(profile.pending === 0, 'pending');
  });

  test('la muestra look=1 conserva la escala 2.269', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look1[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    const result = cal.observePlay(playFor(f));
    assert(result.matched && result.calibrated, 'matched/calibrated');
    const profile = cal.getProfile(f.mobile);
    approx(profile.powerScale, 397 / 175, 1e-12, 'powerScale');
    approx(profile.gravity, 390, 1e-12, 'gravity');
  });

  test('un play de otro jugador no contamina la calibración', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    const result = cal.observePlay(playFor(f, { x: 170, y: 55, look: 1 }));
    assert(result.matched === false, 'no debe emparejar');
    const profile = cal.getProfile(f.mobile);
    assert(profile.samples.power === 0, 'sin muestras');
    approx(profile.powerScale, 2.269, 1e-12, 'fallback');
  });

  test('viento horizontal distinto de 0 no actualiza gravity', function () {
    const cal = new api.AutoCalibration();
    const shot = { x: 100, y: 100, body: 0, look: 1, ang: 45, power: 100, time: 1000 };
    cal.observeGameShoot(shot, { mobile: 'Armor2D.png' });
    const f = {
      x: 100, y: 100, look: 1, time: 1000,
      s: [120, 80, 45, 236, 50, 386],
    };
    const result = cal.observePlay(playFor(f));
    assert(result.matched && result.calibrated, 'matched/calibrated');
    const profile = cal.getProfile('Armor2D.png');
    approx(profile.powerScale, 2.36, 1e-12, 'powerScale');
    approx(profile.gravity, 390, 1e-12, 'gravity de respaldo');
    assert(profile.samples.gravity === 0, 'gravity no debe actualizarse');
  });

  test('resetPhysics vuelve al perfil de respaldo', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    cal.observePlay(playFor(f));
    cal.resetPhysics();
    const profile = cal.getProfile(f.mobile);
    approx(profile.powerScale, 2.269, 1e-12, 'powerScale');
    approx(profile.gravity, 390, 1e-12, 'gravity');
    assert(profile.samples.power === 0, 'samples.power');
  });

  test('originFor respeta look=0 y look=1 en body=0', function () {
    const cal = new api.AutoCalibration();
    const left = cal.originFor({ x: 931, y: 13 }, 0, 0, 'Armor2D.png');
    const right = cal.originFor({ x: 931, y: 13 }, 0, 1, 'Armor2D.png');
    approx(left.x, 910.55, 1e-9, 'look=0 x');
    approx(left.y, -12.83, 1e-9, 'look=0 y');
    approx(right.x, 951.45, 1e-9, 'look=1 x');
    approx(right.y, -12.83, 1e-9, 'look=1 y');
  });

  test('originFor espeja el offset local antes de rotar por body', function () {
    const cal = new api.AutoCalibration();
    const left = cal.originFor({ x: 100, y: 100 }, 26, 0, 'Armor2D.png');
    const right = cal.originFor({ x: 100, y: 100 }, 26, 1, 'Armor2D.png');
    // look=1: (a·cos + c·sin, a·sin − c·cos)
    approx(right.x, 100 + 29.703464968379834, 1e-9, 'look=1 x');
    approx(right.y, 100 - 14.251160264070851, 1e-9, 'look=1 y');
    // look=0: espejo en X local => (−a·cos + c·sin, −a·sin − c·cos)
    approx(left.x, 100 - 7.057211525256099, 1e-9, 'look=0 x');
    approx(left.y, 100 - 32.18054016774411, 1e-9, 'look=0 y');
    assert(left.x < 100, 'look=0 debe quedar a la izquierda');
    assert(left.y < right.y, 'el espejo local cambia la altura con body≠0');
    // Con body=0 el espejo es (−a, −c), como el `s` real de la sala 44.
    const flatLeft = cal.originFor({ x: 931, y: 13 }, 0, 0, 'Armor2D.png');
    approx(flatLeft.x, 910.55, 1e-9, 'body=0 look=0 x');
    approx(flatLeft.y, -12.83, 1e-9, 'body=0 look=0 y');
  });

  test('calibrateOrigin opcional resuelve (a,c) desde play.s', function () {
    const cal = new api.AutoCalibration({ calibrateOrigin: true });
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    const result = cal.observePlay(playFor(f));
    assert(result.matched && result.calibrated, 'matched/calibrated');
    const profile = cal.getProfile(f.mobile);
    approx(profile.origin.a, 20, 1e-9, 'origin.a');
    approx(profile.origin.c, 26, 1e-9, 'origin.c');
    assert(profile.originCalibrated === true, 'originCalibrated');
  });

  test('con calibrateOrigin desactivado se mide el origen pero no se aplica', function () {
    const cal = new api.AutoCalibration({ calibrateOrigin: false });
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    cal.observePlay(playFor(f));
    const profile = cal.getProfile(f.mobile);
    assert(profile.originCalibrated === false, 'no aplicado');
    approx(profile.origin.a, 20.45, 1e-12, 'origen por defecto');
    approx(profile.origin.c, 25.83, 1e-12, 'origen por defecto');
    assert(profile.originMeasured && profile.originMeasured.samples === 1, 'medido');
    approx(profile.originMeasured.a, 20, 1e-9, 'a medido');
    approx(profile.originMeasured.c, 26, 1e-9, 'c medido');
  });

  test('onChange recibe el perfil actualizado', function () {
    let seen = null;
    const cal = new api.AutoCalibration({
      onChange: function (profile) { seen = profile; },
    });
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    cal.observePlay(playFor(f));
    assert(seen && seen.samples.power === 1, 'callback');
    approx(seen.powerScale, 461 / 195, 1e-12, 'callback powerScale');
  });


  test('mobileKey normaliza ruta, extensión y sufijo 2D', function () {
    assert(api.mobileKey('/static/images/mobiles/Boomer2D.png') === 'boomer', 'boomer');
    assert(api.mobileKey('Armor2D.png') === 'armor', 'armor');
    assert(api.mobileKey('Dragon22D.png') === 'dragon2', 'dragon2');
    assert(api.mobileKey('') === 'unknown', 'vacío');
  });

  test('Boomer predice su gravedad desde la unidad observada en Armor', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    cal.observePlay(playFor(f));
    const armor = cal.getProfile('Armor2D.png');
    approx(armor.gravity, 402, 1e-12, 'Armor observada');
    const boomer = cal.getProfile('/static/images/mobiles/Boomer2D.png');
    approx(boomer.gravity, 402 / 75 * 62.5, 1e-9, 'Boomer predicha');
    assert(boomer.gravitySource === 'predicted', 'gravitySource');
    assert(boomer.samples.gravity === 0, 'sin muestras propias');
    // La potencia es del disparo, no del móvil: se hereda la global.
    approx(boomer.powerScale, 461 / 195, 1e-12, 'potencia global');
    assert(boomer.samples.power === 0, 'sin muestras propias de potencia');
  });

  test('Boomer sin ninguna observación usa la unidad de respaldo', function () {
    const cal = new api.AutoCalibration();
    const boomer = cal.getProfile('/static/images/mobiles/Boomer2D.png');
    approx(boomer.gravity, 390 / 75 * 62.5, 1e-12, 'gravedad de respaldo');
    assert(boomer.gravitySource === 'predicted', 'gravitySource');
    approx(boomer.powerScale, 2.269, 1e-12, 'potencia de respaldo');
  });

  test('las muestras de Boomer y Armor no se mezclan', function () {
    const cal = new api.AutoCalibration();
    const armor = fixtures.look0[0];
    cal.observeGameShoot(armor.shoot, { mobile: armor.mobile });
    cal.observePlay(playFor(armor));

    const boomerShot = { x: 100, y: 100, body: 0, look: 1, ang: 45, power: 100, time: 1000 };
    const boomerPlay = {
      x: 100, y: 100, look: 1, time: 1000,
      s: [110, 90, 45, 229, 0, 335],
    };
    cal.observeGameShoot(boomerShot, { mobile: '/static/images/mobiles/Boomer2D.png' });
    const result = cal.observePlay(playFor(boomerPlay));
    assert(result.matched && result.calibrated, 'boomer matched');

    const a = cal.getProfile('Armor2D.png');
    const b = cal.getProfile('Boomer2D.png');
    approx(a.gravity, 402, 1e-12, 'Armor intacta');
    approx(a.powerScale, 461 / 195, 1e-12, 'Armor powerScale');
    approx(b.gravity, 335, 1e-12, 'Boomer observada');
    approx(b.powerScale, 2.29, 1e-12, 'Boomer powerScale');
    assert(b.gravitySource === 'observed', 'Boomer observed');
    assert(a.samples.power === 1 && b.samples.power === 1, 'un cubo por móvil');
  });

  test('un móvil sin tabla cae al perfil por defecto', function () {
    const cal = new api.AutoCalibration();
    const profile = cal.getProfile('MysteryMobile2D.png');
    approx(profile.gravity, 390, 1e-12, 'gravity');
    assert(profile.gravitySource === 'default', 'gravitySource');
    approx(profile.powerScale, 2.269, 1e-12, 'powerScale');
  });


  test('mobileKey tolera acentos y nombres con ruta', function () {
    assert(api.mobileKey('Eléctrico2D.png') === 'electrico', 'acento');
    assert(api.mobileKey('/static/images/mobiles/Kalsiddon2D.png') === 'kalsiddon', 'ruta');
    assert(api.mobileKey('Dragon2_2D.png') === 'dragon2', 'guion bajo');
  });

  test('todos los móviles de la tabla predicen su gravedad', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    cal.observeGameShoot(f.shoot, { mobile: f.mobile });
    cal.observePlay(playFor(f));
    const unit = 402 / 75;
    Object.keys(api.MOBILE_A).forEach(function (name) {
      const profile = cal.getProfile(name + '2D.png');
      approx(profile.gravity, unit * api.MOBILE_A[name], 1e-9, name);
      assert(profile.mobile === name, name + ': clave');
      if (name !== 'armor') {
        assert(profile.gravitySource === 'predicted', name + ': fuente');
      }
    });
  });

  test('Dragon2 aprende su gravedad sin tocar la de Armor', function () {
    const cal = new api.AutoCalibration();
    const armor = fixtures.look0[0];
    cal.observeGameShoot(armor.shoot, { mobile: armor.mobile });
    cal.observePlay(playFor(armor));

    const d2Shot = { x: 100, y: 100, body: 0, look: 1, ang: 45, power: 100, time: 1000 };
    const d2Play = {
      x: 100, y: 100, look: 1, time: 1000,
      s: [110, 90, 45, 236, 0, 643.2],
    };
    cal.observeGameShoot(d2Shot, { mobile: '/static/images/mobiles/Dragon22D.png' });
    const result = cal.observePlay(playFor(d2Play));
    assert(result.matched && result.calibrated, 'dragon2 matched');

    const a = cal.getProfile('Armor2D.png');
    const d = cal.getProfile('Dragon22D.png');
    approx(a.gravity, 402, 1e-12, 'Armor intacta');
    approx(d.gravity, 643.2, 1e-12, 'Dragon2 observada');
    approx(d.powerScale, 2.36, 1e-12, 'Dragon2 powerScale');
    assert(d.gravitySource === 'observed', 'Dragon2 observada');
    assert(a.samples.gravity === 1 && d.samples.gravity === 1, 'cubos separados');
  });


  function feedOrigin(cal, fixture, mobile, count, s) {
    for (let i = 0; i < count; i++) {
      cal.observeGameShoot(fixture.shoot, { mobile: mobile });
      cal.observePlay(playFor(fixture, s ? { s: s } : null));
    }
  }

  test('la tabla aim siembra el origen de cada móvil', function () {
    const cal = new api.AutoCalibration();
    const armor = cal.getProfile('Armor2D.png');
    assert(armor.originSource === 'tuned', 'Armor conserva el ajuste live');
    approx(armor.origin.a, 20.45, 1e-12, 'Armor a');
    const boomer = cal.getProfile('Boomer2D.png');
    assert(boomer.originSource === 'predicted', 'Boomer predicho');
    approx(boomer.origin.a, 33 * Math.cos(52 * Math.PI / 180), 1e-9, 'Boomer a');
    approx(boomer.origin.c, 33 * Math.sin(52 * Math.PI / 180), 1e-9, 'Boomer c');
    const fox = cal.getProfile('Fox2D.png');
    assert(fox.originSource === 'predicted', 'Fox predicho');
    approx(fox.origin.a, 44 * Math.cos(30 * Math.PI / 180), 1e-9, 'Fox a');
    approx(fox.origin.c, 44 * Math.sin(30 * Math.PI / 180), 1e-9, 'Fox c');
    const keys = Object.keys(api.MOBILE_AIM);
    assert(keys.length === 21, '21 móviles con aim');
    keys.forEach(function (key) {
      const o = api.aimOrigin(key);
      assert(o && Number.isFinite(o.a) && Number.isFinite(o.c), 'aim ' + key);
    });
    // El vector aim de Armor debe coincidir con el valor medido en vivo.
    const aimArmor = api.aimOrigin('Armor2D.png');
    assert(Math.hypot(aimArmor.a - 20.45, aimArmor.c - 25.83) < 0.5,
      'aim de Armor ≈ origen live');
  });

  test('auto-calibra el origen de un móvil tras 3 muestras estables', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    // Boca de Boomer delante, cerca de su vector aim (52°, 33): el ajuste
    // medido debe refinar la semilla, no saltar a un punto arbitrario.
    const s = [911, -13, 153, 461, 0, 335];
    feedOrigin(cal, f, '/static/images/mobiles/Boomer2D.png', 3, s);
    const profile = cal.getProfile('Boomer2D.png');
    assert(profile.originCalibrated === true, 'calibrado');
    assert(profile.originSource === 'measured', 'fuente');
    approx(profile.origin.a, 20, 1e-9, 'a');
    approx(profile.origin.c, 26, 1e-9, 'c');
    assert(profile.originMeasured.samples === 3, 'muestras');
    assert(profile.originMeasured.stable === true, 'estable');
  });

  test('con menos de 3 muestras se usa el vector aim del móvil', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    feedOrigin(cal, f, '/static/images/mobiles/Boomer2D.png', 2,
      [911, -13, 153, 461, 0, 335]);
    const profile = cal.getProfile('Boomer2D.png');
    assert(profile.originCalibrated === false, 'no aplicado');
    assert(profile.originSource === 'predicted', 'fuente');
    approx(profile.origin.a, 33 * Math.cos(52 * Math.PI / 180), 1e-9, 'a aim');
    approx(profile.origin.c, 33 * Math.sin(52 * Math.PI / 180), 1e-9, 'c aim');
    assert(profile.originMeasured.samples === 2, 'medido');
  });

  test('una medición lejos del aim no descalibra el móvil', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    // (-26, 31) está a ~46 unidades del aim de Boomer (20,32, 26,00): la
    // medición es estable, pero no debe sustituir el origen del cliente.
    feedOrigin(cal, f, '/static/images/mobiles/Boomer2D.png', 3,
      [957, -18, 153, 461, 0, 335]);
    const profile = cal.getProfile('Boomer2D.png');
    assert(profile.originCalibrated === false, 'no aplicado');
    assert(profile.originSource === 'predicted', 'conserva el aim');
    approx(profile.origin.a, 33 * Math.cos(52 * Math.PI / 180), 1e-9, 'a aim');
    approx(profile.origin.c, 33 * Math.sin(52 * Math.PI / 180), 1e-9, 'c aim');
    assert(profile.originMeasured.samples === 3, 'medido');
    assert(profile.originMeasured.stable === true, 'estable');
  });

  test('calibrateOrigin true fuerza la medición aunque esté lejos del aim', function () {
    const cal = new api.AutoCalibration({ calibrateOrigin: true });
    const f = fixtures.look0[0];
    feedOrigin(cal, f, '/static/images/mobiles/Boomer2D.png', 1,
      [957, -18, 153, 461, 0, 335]);
    const profile = cal.getProfile('Boomer2D.png');
    assert(profile.originCalibrated === true, 'forzado');
    assert(profile.originSource === 'measured', 'medido');
    approx(profile.origin.a, -26, 1e-9, 'a');
    approx(profile.origin.c, 31, 1e-9, 'c');
  });

  test('Armor conserva su origen ajustado aunque haya muestras', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    feedOrigin(cal, f, '/static/images/mobiles/Armor2D.png', 3);
    const profile = cal.getProfile('Armor2D.png');
    assert(profile.originCalibrated === false, 'no se sustituye');
    assert(profile.originSource === 'tuned', 'fuente');
    approx(profile.origin.a, 20.45, 1e-12, 'a ajustada');
    approx(profile.origin.c, 25.83, 1e-12, 'c ajustada');
    assert(profile.originMeasured.samples === 3, 'medido');
  });

  test('un origen inestable no se aplica', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.look0[0];
    const muzzle = [911, 891, 871];
    for (let i = 0; i < 3; i++) {
      cal.observeGameShoot(f.shoot, { mobile: '/static/images/mobiles/Boomer2D.png' });
      cal.observePlay(playFor(f, { s: [muzzle[i], -13, 153, 461, 0, 335] }));
    }
    const profile = cal.getProfile('Boomer2D.png');
    assert(profile.originCalibrated === false, 'no aplicado');
    assert(profile.originMeasured.stable === false, 'inestable');
    assert(profile.originSource === 'predicted', 'fuente');
    approx(profile.origin.a, 33 * Math.cos(52 * Math.PI / 180), 1e-9, 'a aim');
    approx(profile.origin.c, 33 * Math.sin(52 * Math.PI / 180), 1e-9, 'c aim');
  });

  test('tabla b de viento cubre los 21 móviles', function () {
    const keys = Object.keys(api.MOBILE_A);
    assert(keys.length === 21, '21 móviles');
    keys.forEach(function (key) {
      assert(typeof api.MOBILE_B[key] === 'number', 'b de ' + key);
    });
    approx(api.MOBILE_B.armor, 0.76, 1e-12, 'Armor b');
    approx(api.MOBILE_B.boomer, 1.39, 1e-12, 'Boomer b');
    approx(api.MOBILE_B.fox, 0.61, 1e-12, 'Fox b');
    approx(api.MOBILE_B.dragon2, 0.74, 1e-12, 'Dragon2 b');
  });

  test('viento 0 no altera la gravedad', function () {
    const cal = new api.AutoCalibration();
    cal.setWind({ power: 0, angle: 0 });
    const profile = cal.getProfile('Armor2D.png');
    assert(profile.wind.active === false, 'inactivo');
    approx(profile.wind.windX, 0, 1e-12, 'windX');
    approx(profile.wind.vertAccel, profile.gravity, 1e-12, 'vertAccel');
    assert(profile.wind.unitSource === 'default', 'unidad por defecto');
  });

  test('viento horizontal izquierdo con Armor (escala por defecto)', function () {
    const cal = new api.AutoCalibration();
    cal.setWind({ power: 49, angle: 3.4 });
    const profile = cal.getProfile('Armor2D.png');
    const scale = 0.76 * api.DEFAULT_WIND_UNIT;
    assert(profile.wind.active === true, 'activo');
    approx(profile.wind.b, 0.76, 1e-12, 'b');
    approx(profile.wind.unit, scale, 1e-9, 'escala');
    approx(profile.wind.windX,
      -Math.cos(3.4 * Math.PI / 180) * 49 * scale, 1e-9, 'windX');
    approx(profile.wind.vertAccel,
      390 - Math.sin(3.4 * Math.PI / 180) * 49 * scale, 1e-9, 'vertAccel');
    assert(profile.wind.windX < 0, 'viento a la izquierda');
    assert(profile.wind.vertAccel < profile.gravity, 'viento hacia arriba');
  });

  test('el viento escala con b de cada móvil', function () {
    const cal = new api.AutoCalibration();
    cal.setWind({ power: 49, angle: 3.4 });
    const armor = cal.getProfile('Armor2D.png').wind;
    const boomer = cal.getProfile('Boomer2D.png').wind;
    const fox = cal.getProfile('Fox2D.png').wind;
    approx(boomer.windX / armor.windX, 1.39 / 0.76, 1e-9, 'Boomer/Armor');
    approx(fox.windX / armor.windX, 0.61 / 0.76, 1e-9, 'Fox/Armor');
    assert(Math.abs(boomer.windX) > Math.abs(armor.windX), 'Boomer sufre más');
    assert(Math.abs(fox.windX) < Math.abs(armor.windX), 'Fox sufre menos');
  });

  test('aprende escala y signo de viento de un disparo live', function () {
    const cal = new api.AutoCalibration();
    const f = fixtures.wind[0];
    cal.setWind(f.wind);
    cal.observeGameShoot(f.shoot, { mobile: f.mobile, wind: f.wind });
    const result = cal.observePlay(playFor(f));
    assert(result.matched && result.calibrated, result.reason || 'sin calibrar');
    assert(result.delta.windLearned === true, 'aprendió viento');
    approx(result.delta.windUnit,
      Math.abs(-201) / (Math.cos(3.4 * Math.PI / 180) * 49), 0.01, 'escala aprendida');
    const profile = cal.getProfile('Armor2D.png');
    approx(profile.wind.unit,
      Math.abs(-201) / (Math.cos(3.4 * Math.PI / 180) * 49), 0.01, 'escala del perfil');
    approx(profile.wind.windX, -201, 1, 'windX live');
    approx(profile.wind.vertAccel, 378, 1, 'vertAccel live');
    assert(profile.wind.unitSource === 'measured', 'escala medida');
    assert(profile.wind.signX === -1, 'signo X');
    assert(profile.wind.signY === 1, 'signo Y');
    assert(profile.samples.wind === 1, 'muestra de viento');
    assert(profile.originMeasured && profile.originMeasured.samples === 1,
      'origen medido del fixture');
  });

  test('aprende la escala de los 3 disparos live con viento (49/46/42)', function () {
    const cal = new api.AutoCalibration();
    fixtures.wind.forEach(function (f) {
      cal.setWind(f.wind);
      cal.observeGameShoot(f.shoot, { mobile: f.mobile, wind: f.wind });
      const result = cal.observePlay(playFor(f));
      assert(result.matched && result.calibrated,
        f.name + ': ' + (result.reason || 'sin calibrar'));
    });
    const profile = cal.getProfile('Armor2D.png');
    approx(profile.wind.unit, 4.11, 0.06, 'escala live');
    approx(profile.wind.windX, -173, 4, 'último windX');
    approx(profile.wind.vertAccel, 382, 3, 'último vertAccel');
    assert(profile.samples.wind === 3, '3 muestras');
    assert(profile.powerScale > 2.26 && profile.powerScale < 2.28,
      'powerScale live ' + profile.powerScale);
  });

  test('un viento casi vertical aprende la escala de la componente Y', function () {
    const cal = new api.AutoCalibration();
    const wind = { power: 30, angle: 89 };
    cal.setWind(wind);
    cal.observeGameShoot(':931,13,0,0,78,181,3000,0,[]', {
      mobile: '/static/images/mobiles/Armor2D.png',
      wind: wind,
    });
    const result = cal.observePlay(playFor({
      x: 931, y: 13, look: 0,
      s: [911, -13, 78, 427, 0, 360],
      time: 3000,
    }));
    assert(result.matched && result.calibrated, result.reason || 'sin calibrar');
    const profile = cal.getProfile('Armor2D.png');
    assert(profile.wind.unitSource === 'measured', 'escala medida');
    approx(profile.wind.unit,
      (390 - 360) / (Math.sin(89 * Math.PI / 180) * 30), 1e-9, 'escala Y');
  });

  root.__DS_TEST_RESULTS = results;

  if (typeof document !== 'undefined') {
    const pre = document.getElementById('results');
    if (pre) {
      const failed = results.filter(function (r) { return !r.ok; });
      pre.textContent = JSON.stringify({
        ok: failed.length === 0,
        total: results.length,
        failed: failed.length,
        results: results,
      }, null, 2);
    }
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
