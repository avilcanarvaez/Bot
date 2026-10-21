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
