/*
 * DragonShot — overlay (mundo ISOLATED).
 *
 * Recibe snapshots del page-hook por window.postMessage y dibuja:
 *   - origen en el cañón,
 *   - destino elegido con Ctrl + clic derecho,
 *   - trayectoria solución,
 *   - marca de potencia necesaria sobre la barra.
 *
 * No modifica el DOM del juego ni automatiza el disparo.
 */
(function () {
  'use strict';

  const BRIDGE = 'dragonshot-ext';
  const OVERLAY = 'dragonshot-overlay';
  const ROOT_ID = 'dragonshot-extension-root';
  const FALLBACK = { powerScale: 2.269, gravity: 390, origin: { a: 20.45, c: 25.83 } };

  let host = null;
  let shadow = null;
  let canvas = null;
  let ctx = null;
  let panel = null;
  let els = {};
  let dpr = 1;
  let snapshot = null;
  let lastSnapshotAt = 0;
  let destination = null;
  let step = 'origin';
  let originConfirmed = false;
  let selectionMode = false;
  let selectionLayer = null;
  let selectionFrame = null;
  let selectionBadge = null;
  let selectionNoticeTimer = null;
  let suppressMouseUntil = 0;
  let suppressClickUntil = 0;
  let lastLayoutKey = '';
  let lastMarker = null;
  let lastTrajectory = null;
  const testMode = !!(typeof window !== 'undefined' && window.__DS_TEST_MODE__);

  function init() {
    if (document.getElementById(ROOT_ID)) return;
    if (!document.documentElement) {
      setTimeout(init, 0);
      return;
    }

    createUi();
    bindEvents();
    try {
      window.postMessage({ source: OVERLAY, type: 'selection-mode', active: false }, '*');
    } catch (error) {}
    resizeCanvas();
    layoutOverlay();
    requestSnapshot();
    setInterval(requestSnapshot, 100);
    requestAnimationFrame(loop);
  }

  function createUi() {
    host = document.createElement('div');
    host.id = ROOT_ID;
    host.style.cssText = 'position:fixed;inset:0;z-index:2147483647;pointer-events:none;';
    document.documentElement.appendChild(host);

    shadow = host.attachShadow({ mode: 'open' });

    const style = document.createElement('style');
    style.textContent = [
      ':host{all:initial}',
      '*{box-sizing:border-box}',
      '.ds-canvas{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;display:block;z-index:0}',
      // La capa es solo visual: `pointer-events:none` deja pasar los mousemove
      // al juego para que el cañón apunte al destino. El clic se intercepta en
      // window (fase de captura), antes de que el canvas lo reciba.
      '.ds-select-layer{position:absolute;inset:0;display:none;pointer-events:none;cursor:crosshair;',
      'background:rgba(34,211,238,.035);z-index:1}',
      '.ds-select-layer.on{display:block;pointer-events:none}',
      '.ds-select-frame{position:absolute;display:none;border:2px dashed rgba(34,211,238,.85);',
      'border-radius:6px;pointer-events:none;box-shadow:0 0 0 1px rgba(6,20,28,.65) inset}',
      '.ds-select-layer.on .ds-select-frame{display:block}',
      '.ds-select-badge{position:absolute;left:50%;top:10px;transform:translateX(-50%);',
      'padding:5px 9px;border-radius:999px;background:rgba(6,20,28,.92);color:#dffaff;',
      'border:1px solid rgba(34,211,238,.55);font:700 10px/1.2 ui-sans-serif,system-ui,sans-serif;',
      'white-space:nowrap;pointer-events:none;box-shadow:0 6px 20px rgba(0,0,0,.35)}',
      '.ds-panel{position:absolute;top:8px;left:auto;right:8px;width:196px;padding:8px 9px 9px;',
      'border-radius:12px;background:rgba(6,20,28,.94);color:#eafcff;',
      'font:11px/1.3 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;',
      'border:1px solid rgba(34,211,238,.38);box-shadow:0 10px 30px rgba(0,0,0,.35);',
      'pointer-events:auto;backdrop-filter:blur(8px);z-index:2;overflow:hidden;overflow-y:auto;',
      'max-height:calc(100vh - 16px)}',
      '.ds-panel.hidden{display:none}',
      '.ds-panel.collapsed .ds-body{display:none}',
      '.ds-panel[data-dock="overlay"]{border-color:rgba(251,191,36,.42)}',
      '.ds-head{display:flex;align-items:center;gap:5px;margin-bottom:7px;font-weight:700;letter-spacing:.1px}',
      '.ds-dot{width:8px;height:8px;border-radius:50%;background:#22d3ee;box-shadow:0 0 10px #22d3ee;flex:none}',
      '.ds-title{font-size:11px;white-space:nowrap}',
      '.ds-state{margin-left:auto;font-size:8.5px;font-weight:700;color:#fbbf24;',
      'background:rgba(251,191,36,.10);border:1px solid rgba(251,191,36,.30);',
      'padding:2px 5px;border-radius:999px;white-space:nowrap;max-width:78px;',
      'overflow:hidden;text-overflow:ellipsis}',
      '.ds-state.ok{color:#34d399;background:rgba(52,211,153,.10);border-color:rgba(52,211,153,.35)}',
      '.ds-state.bad{color:#fb7185;background:rgba(251,113,133,.10);border-color:rgba(251,113,133,.35)}',
      '.ds-collapse{flex:none;width:18px;height:18px;padding:0;margin-left:2px;',
      'border:1px solid rgba(255,255,255,.16);border-radius:5px;background:rgba(255,255,255,.06);',
      'color:#dffaff;font:700 12px/16px ui-sans-serif,sans-serif;cursor:pointer}',
      '.ds-row{display:flex;justify-content:space-between;gap:6px;padding:2px 0;',
      'border-bottom:1px solid rgba(255,255,255,.06)}',
      '.ds-row span{color:#9fc3d2;font-size:9px;text-transform:uppercase;letter-spacing:.35px}',
      '.ds-row b{font-size:10.5px;color:#eafcff;font-weight:650;text-align:right}',
      '.ds-diag{margin:3px 0 1px;font:9px/1.25 ui-monospace,SFMono-Regular,Menlo,monospace;',
      'color:#6f93a3;letter-spacing:.2px;white-space:nowrap;overflow:hidden;',
      'text-overflow:ellipsis;cursor:help}',
      '.ds-req{margin:6px 0 5px;padding:6px 7px;border-radius:8px;',
      'background:rgba(52,211,153,.10);border:1px solid rgba(52,211,153,.35);',
      'display:flex;align-items:baseline;justify-content:space-between;gap:6px}',
      '.ds-req span{font-size:8.5px;color:#bbf7d0;text-transform:uppercase;letter-spacing:.35px}',
      '.ds-req b{font-size:18px;line-height:1;color:#a7f3d0;font-weight:800}',
      '.ds-hint{margin-top:6px;padding:5px 6px;border-radius:7px;font-size:9px;color:#f6d38a;',
      'background:rgba(251,191,36,.09);border:1px solid rgba(251,191,36,.20)}',
      '.ds-btns{display:flex;gap:5px;margin-top:6px}',
      '.ds-btn{flex:1;border:1px solid rgba(34,211,238,.35);background:rgba(34,211,238,.10);',
      'color:#dffaff;border-radius:7px;padding:5px 6px;font:inherit;font-size:10px;cursor:pointer}',
      '.ds-btn:hover{background:rgba(34,211,238,.20)}',
      '.ds-btn.primary{flex:2;white-space:nowrap;background:rgba(52,211,153,.14);',
      'border-color:rgba(52,211,153,.45);color:#d1fae5}',
      '.ds-btn.on{background:rgba(251,191,36,.16);border-color:rgba(251,191,36,.50);color:#fde68a}',
    ].join('');

    canvas = document.createElement('canvas');
    canvas.className = 'ds-canvas';
    shadow.appendChild(style);
    shadow.appendChild(canvas);

    // Capa transparente para el modo "Seleccionar destino". Es puramente
    // visual: no captura el ratón, así el juego sigue recibiendo los
    // `mousemove` y puede girar el cañón. El clic se intercepta en `window`
    // (captura) para fijar el destino sin que el canvas lo procese.
    selectionLayer = document.createElement('div');
    selectionLayer.className = 'ds-select-layer';
    selectionFrame = document.createElement('div');
    selectionFrame.className = 'ds-select-frame';
    selectionBadge = document.createElement('div');
    selectionBadge.className = 'ds-select-badge';
    selectionBadge.textContent = 'Apunta y haz clic en el destino';
    selectionLayer.appendChild(selectionFrame);
    selectionLayer.appendChild(selectionBadge);
    shadow.appendChild(selectionLayer);

    panel = document.createElement('div');
    panel.className = 'ds-panel';
    panel.innerHTML = [
      '<div class="ds-head"><span class="ds-dot"></span><span class="ds-title">DragonShot</span>',
      '<span class="ds-state" id="ds-state">SIN JUEGO</span>',
      '<button class="ds-collapse" id="ds-collapse" title="Contraer panel" ',
      'aria-label="Contraer panel" aria-expanded="true">−</button></div>',
      '<div class="ds-body">',
      '<div class="ds-row"><span>Origen</span><b id="ds-origin">CAÑÓN</b></div>',
      '<div class="ds-row"><span>Destino</span><b id="ds-dest">—</b></div>',
      '<div class="ds-req"><span>Potencia necesaria</span><b id="ds-power">—</b></div>',
      '<div class="ds-row"><span>Ángulo</span><b id="ds-angle">—</b></div>',
      '<div class="ds-row"><span>Calibración</span><b id="ds-cal">—</b></div>',
      '<div class="ds-diag" id="ds-diag">—</div>',
      '<div class="ds-hint" id="ds-hint">Haz clic en el campo. Alt: Ctrl + clic derecho. ',
      '<b>Esc</b> cancela o limpia.</div>',
      '<div class="ds-btns"><button class="ds-btn primary" id="ds-select">',
      'Seleccionar destino</button><button class="ds-btn" id="ds-clear">Limpiar</button></div>',
      '</div>',
    ].join('');
    shadow.appendChild(panel);

    els = {
      state: shadow.getElementById('ds-state'),
      origin: shadow.getElementById('ds-origin'),
      dest: shadow.getElementById('ds-dest'),
      power: shadow.getElementById('ds-power'),
      angle: shadow.getElementById('ds-angle'),
      cal: shadow.getElementById('ds-cal'),
      diag: shadow.getElementById('ds-diag'),
      hint: shadow.getElementById('ds-hint'),
      select: shadow.getElementById('ds-select'),
      clear: shadow.getElementById('ds-clear'),
      collapse: shadow.getElementById('ds-collapse'),
    };
  }

  function bindEvents() {
    window.addEventListener('message', onMessage, false);
    window.addEventListener('pointerdown', onPointerDown, true);
    window.addEventListener('pointerup', onSelectionMouseSuppress, true);
    window.addEventListener('mousedown', onSelectionMouseSuppress, true);
    window.addEventListener('mouseup', onSelectionMouseSuppress, true);
    window.addEventListener('click', onSelectionClickSuppress, true);
    window.addEventListener('auxclick', onSelectionClickSuppress, true);
    window.addEventListener('contextmenu', onContextMenu, true);
    window.addEventListener('keydown', onKeyDown, true);
    window.addEventListener('resize', resizeCanvas, { passive: true });
    window.addEventListener('hashchange', onRoomChange, false);
    if (els.clear) {
      els.clear.addEventListener('click', function (event) {
        event.preventDefault();
        clearDestination();
      });
    }
    if (els.select) {
      els.select.addEventListener('click', function (event) {
        event.preventDefault();
        if (!selectionMode && (!snapshot || !snapshot.ok)) {
          requestSnapshot();
          setState('SIN JUEGO', 'bad');
          return;
        }
        setSelectionMode(!selectionMode);
      });
    }
    if (els.collapse) {
      els.collapse.addEventListener('click', function (event) {
        event.preventDefault();
        togglePanelCollapsed();
      });
    }
    bindRuntimeMessages();
  }

  function bindRuntimeMessages() {
    if (typeof chrome === 'undefined' || !chrome.runtime || !chrome.runtime.onMessage) return;
    chrome.runtime.onMessage.addListener(function (message, sender, sendResponse) {
      if (!message || message.type !== 'dragonshot-toggle-panel') return;
      togglePanel();
      if (sendResponse) sendResponse({ ok: true, hidden: panel.classList.contains('hidden') });
    });
  }

  function togglePanel() {
    if (!panel) return;
    if (panel.classList.contains('hidden')) {
      panel.classList.remove('hidden');
      layoutOverlay();
    } else {
      panel.classList.add('hidden');
      setSelectionMode(false);
    }
  }

  function togglePanelCollapsed() {
    if (!panel) return;
    panel.classList.toggle('collapsed');
    const collapsed = panel.classList.contains('collapsed');
    if (els.collapse) {
      els.collapse.textContent = collapsed ? '+' : '−';
      els.collapse.title = collapsed ? 'Expandir panel' : 'Contraer panel';
      els.collapse.setAttribute('aria-label', els.collapse.title);
      els.collapse.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    }
    layoutOverlay();
  }

  function requestSnapshot() {
    try {
      window.postMessage({ source: OVERLAY, type: 'get-snapshot' }, '*');
    } catch (error) {}
  }

  function onMessage(event) {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.source !== BRIDGE) return;

    lastSnapshotAt = Date.now();

    if (data.type === 'snapshot') {
      snapshot = data.snapshot || null;
      layoutOverlay();
    } else if (data.type === 'room-state') {
      onRoomChange();
    } else if (data.type === 'play' && data.calibrated) {
      requestSnapshot();
    } else if (data.type === 'game-shoot') {
      requestSnapshot();
    }
  }

  function onRoomChange() {
    destination = null;
    step = 'origin';
    originConfirmed = false;
    setSelectionMode(false);
    requestSnapshot();
  }

  function clearDestination() {
    destination = null;
    step = 'origin';
    originConfirmed = false;
    setSelectionMode(false);
  }

  function setSelectionMode(active) {
    selectionMode = !!active;
    try {
      window.postMessage({ source: OVERLAY, type: 'selection-mode', active: selectionMode }, '*');
    } catch (error) {}
    if (selectionLayer) selectionLayer.classList.toggle('on', selectionMode);
    if (els.select) {
      els.select.textContent = selectionMode ? 'Cancelar selección' : 'Seleccionar destino';
      els.select.classList.toggle('on', selectionMode);
    }
    if (selectionMode) {
      originConfirmed = true;
      step = 'destination';
      setSelectionNotice('Haz clic en el destino');
      requestSnapshot();
    } else {
      clearSelectionNotice();
    }
    updateSelectionUi();
    layoutOverlay();
  }

  function updateSelectionUi() {
    if (!els.hint) return;
    if (selectionMode) {
      els.hint.innerHTML = 'Mueve el ratón para apuntar y haz clic en el destino. ' +
        '<b>Esc</b> o clic fuera cancela.';
    } else if (destination) {
      els.hint.innerHTML = 'Destino fijado. <b>Seleccionar destino</b> lo mueve; ' +
        '<b>Esc</b> lo limpia.';
    } else {
      els.hint.innerHTML = 'Haz clic en el campo. Alt: Ctrl + clic derecho. ' +
        '<b>Esc</b> cancela o limpia.';
    }
  }

  function setSelectionNotice(text) {
    if (!selectionBadge) return;
    selectionBadge.textContent = text;
    if (selectionNoticeTimer) clearTimeout(selectionNoticeTimer);
    selectionNoticeTimer = setTimeout(function () {
      selectionNoticeTimer = null;
      if (selectionMode && selectionBadge) selectionBadge.textContent = 'Haz clic en el destino';
    }, 1600);
  }

  function clearSelectionNotice() {
    if (selectionNoticeTimer) clearTimeout(selectionNoticeTimer);
    selectionNoticeTimer = null;
    if (selectionBadge) selectionBadge.textContent = 'Haz clic en el destino';
  }

  function selectDestinationAt(clientX, clientY) {
    if (!snapshot || !snapshot.ok) {
      setSelectionNotice('SIN JUEGO');
      return false;
    }
    if (!insideCanvas(clientX, clientY)) {
      setSelectionNotice('Haz clic dentro del campo');
      return false;
    }
    const world = clientToWorld(clientX, clientY, snapshot);
    if (!world) return false;
    destination = { x: world.x, y: world.y };
    step = 'destination';
    originConfirmed = true;
    setSelectionMode(false);
    // El mousemove previo ya giró el cañón en el juego. Pedir un snapshot
    // inmediato evita dibujar un frame con el ángulo anterior (especialmente
    // al cambiar de lado izquierdo/derecho).
    requestSnapshot();
    return true;
  }

  function layoutOverlay() {
    positionPanel();
    updateSelectionFrame();
  }

  function positionPanel() {
    if (!panel || panel.classList.contains('hidden')) return;
    const width = panel.offsetWidth || 196;
    const height = panel.offsetHeight || 0;
    const edge = 8;
    const gap = 6;
    const rect = snapshot && snapshot.canvas && snapshot.canvas.rect;
    let left = window.innerWidth - width - edge;
    let top = edge;
    let dock = 'overlay';

    if (rect && rect.width > 0) {
      const rightSpace = window.innerWidth - rect.right;
      const leftSpace = rect.left;
      const bottomSpace = window.innerHeight - rect.bottom;
      if (rightSpace >= width + gap + edge) {
        left = rect.right + gap;
        dock = 'right';
      } else if (leftSpace >= width + gap + edge) {
        left = rect.left - width - gap;
        dock = 'left';
      } else if (bottomSpace >= height + gap + edge) {
        left = clamp(rect.left, edge, window.innerWidth - width - edge);
        top = rect.bottom + gap;
        dock = 'bottom';
      } else if (rect.top >= height + gap + edge) {
        left = clamp(rect.left, edge, window.innerWidth - width - edge);
        top = rect.top - height - gap;
        dock = 'top';
      }
    }

    left = clamp(left, edge, Math.max(edge, window.innerWidth - width - edge));
    top = clamp(top, edge, Math.max(edge, window.innerHeight - height - edge));
    const key = [Math.round(left), Math.round(top), width, height, dock,
      panel.classList.contains('collapsed') ? 1 : 0].join(':');
    if (key === lastLayoutKey) return;
    lastLayoutKey = key;
    panel.style.left = Math.round(left) + 'px';
    panel.style.right = 'auto';
    panel.style.top = Math.round(top) + 'px';
    panel.dataset.dock = dock;
  }

  function updateSelectionFrame() {
    if (!selectionFrame || !selectionLayer) return;
    const rect = snapshot && snapshot.canvas && snapshot.canvas.rect;
    if (!selectionMode || !rect || !rect.width) {
      selectionFrame.style.display = 'none';
      return;
    }
    selectionFrame.style.display = 'block';
    selectionFrame.style.left = rect.left + 'px';
    selectionFrame.style.top = rect.top + 'px';
    selectionFrame.style.width = rect.width + 'px';
    selectionFrame.style.height = rect.height + 'px';
  }

  function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
  }

  function resizeCanvas() {
    if (!canvas) return;
    dpr = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.floor(window.innerWidth * dpr));
    const height = Math.max(1, Math.floor(window.innerHeight * dpr));
    canvas.width = width;
    canvas.height = height;
    canvas.style.width = window.innerWidth + 'px';
    canvas.style.height = window.innerHeight + 'px';
    ctx = canvas.getContext('2d');
    if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    layoutOverlay();
  }

  function loop() {
    draw();
    requestAnimationFrame(loop);
  }

  function draw() {
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
    lastMarker = null;
    lastTrajectory = null;

    if (!snapshot) {
      setState('SIN JUEGO', 'bad');
      setText(els.cal, '—');
      publishDebug(null);
      return;
    }
    if (!lastSnapshotAt || Date.now() - lastSnapshotAt > 2000) {
      setState('SIN CONEXIÓN', 'bad');
      setText(els.cal, '—');
      publishDebug(null);
      return;
    }
    if (!snapshot.ok) {
      const label = snapshot.reason === 'sin-juego'
        ? 'SIN JUEGO'
        : (snapshot.reason === 'sin-jugador' ? 'SIN JUGADOR' : 'SIN PIXI');
      setState(label, 'bad');
      setText(els.cal, '—');
      publishDebug(null);
      return;
    }

    const snap = snapshot;
    const me = snap.me;
    const profile = snap.profile || FALLBACK;
    const origin = snap.origin || computeOrigin(me, profile);
    const angAbs = effectiveAngle(me);
    const power = snap.power || { current: 0, last: null };

    setText(els.origin, 'CAÑÓN');
    setText(els.angle, angAbs == null ? '—' : Math.round(angAbs) + '°');
    const samples = profile.samples && profile.samples.power;
    const calSource = profile.gravitySource || 'default';
    const originSource = profile.originSource || 'default';
    const originSamples = profile.originMeasured && profile.originMeasured.samples;
    const originLabel = originSource === 'measured'
      ? 'origen medido (' + (originSamples || 0) + ')'
      : (originSource === 'tuned' ? 'origen fijo'
        : (originSource === 'predicted' ? 'origen predicho' : 'origen por defecto'));
    setText(els.cal, samples ? 'CALIBRADO (' + samples + ')'
      : (calSource === 'predicted' ? 'PREDICHO' : 'FALLBACK'));
    if (els.cal) {
      els.cal.title = 'móvil ' + (profile.mobile || '?') +
        ' · g ' + (Math.round(profile.gravity * 10) / 10) +
        ' · ' + calSource + ' · ' + originLabel;
    }
    updateDiag();

    const originClient = worldToClient(origin, snap);
    if (originClient && inViewport(originClient, 120)) {
      drawMarker(originClient, '#22d3ee', 'ORIGEN');
    }

    if (!destination) {
      setText(els.dest, '—');
      setText(els.power, '—');
      if (selectionMode) {
        setState('ELIGE DESTINO', me.isTurn ? 'ok' : '');
      } else if (originConfirmed) {
        setState('ORIGEN FIJADO', me.isTurn ? 'ok' : '');
      } else {
        setState(me.isTurn ? 'SIN DESTINO' : 'ESPERANDO TURNO', me.isTurn ? '' : 'bad');
      }
      if (power.current > 0) {
        lastTrajectory = drawTrajectory(origin, angAbs, power.current, profile,
          'rgba(34,211,238,.75)', null, snap);
      }
      setPanelDebug(origin, angAbs, null, null);
      return;
    }

    setText(els.dest, Math.round(destination.x) + ', ' + Math.round(destination.y));

    const destClient = worldToClient(destination, snap);
    if (destClient && inViewport(destClient, 140)) {
      drawMarker(destClient, '#34d399', 'DESTINO');
    }

    const solution = solvePower(origin, destination, angAbs, profile);
    if (solution.ok) {
      setText(els.power, Math.round(solution.power) + ' / 400');
      setState(me.isTurn ? 'LISTO' : 'ESPERANDO TURNO', me.isTurn ? 'ok' : '');
      lastTrajectory = drawTrajectory(origin, angAbs, solution.power, profile,
        '#34d399', solution.tau, snap);
      lastMarker = drawPowerMarker(solution.power, '#34d399', 'necesaria');
    } else {
      setText(els.power, '—');
      setState(solution.reason || 'SIN SOLUCIÓN', 'bad');
    }

    if (power.current > 0) {
      drawTrajectory(origin, angAbs, power.current, profile, 'rgba(34,211,238,.80)', null, snap);
      if (!lastMarker) lastMarker = drawPowerMarker(power.current, '#22d3ee', 'actual');
    }
    if (selectionMode) setState('ELIGE DESTINO', me.isTurn ? 'ok' : '');
    setPanelDebug(origin, angAbs, solution, destClient);
  }

  /**
   * Línea de diagnóstico compacta: lado (`L`), cuerpo (`B`), rotación cruda de
   * Pixi (`R`), ángulo leído sin reconciliar (`θ`) y error del último disparo
   * real (`e`). Sirve para validar en vivo sin abrir DevTools.
   */
  function updateDiag() {
    if (!els.diag) return;
    const me = snapshot && snapshot.me;
    if (!me) {
      setText(els.diag, '—');
      els.diag.title = '';
      return;
    }
    const parts = ['L' + (me.look == null ? '—' : me.look)];
    parts.push('B' + Math.round(me.body || 0) + '°');
    if (me.aimRotation != null) parts.push('R' + Math.round(me.aimRotation) + '°');
    if (me.aimRaw != null) parts.push('θ' + Math.round(me.aimRaw) + '°');
    const check = snapshot.aimCheck;
    if (check && check.residual != null) parts.push('e' + Math.round(check.residual) + '°');
    setText(els.diag, parts.join(' · '));
    els.diag.title = 'Lado · cuerpo · rotación Pixi · ángulo leído' +
      (me.aimMirrored ? ' (espejado, corregido con el lado)' : '') +
      (check && check.residual != null
        ? ' · último disparo: mostrado ' + check.model + '°, juego ' + check.truth + '°'
        : '');
  }

  function setPanelDebug(origin, angAbs, solution, destClient) {
    if (!panel) return;
    panel.dataset.origin = origin ? Math.round(origin.x) + ',' + Math.round(origin.y) : '';
    panel.dataset.angle = angAbs == null ? '' : String(Math.round(angAbs));
    panel.dataset.dest = destClient ? Math.round(destClient.x) + ',' + Math.round(destClient.y) : '';
    panel.dataset.solution = solution && solution.ok ? 'ok' : (solution ? solution.reason || 'none' : '');
    panel.dataset.power = solution && solution.ok ? String(Math.round(solution.power)) : '';
    panel.dataset.marker = lastMarker ? Math.round(lastMarker.x) + ',' + Math.round(lastMarker.y) : '';
    if (snapshot && snapshot.profile) {
      panel.dataset.mobile = snapshot.profile.mobile || '';
      panel.dataset.gravity = String(Math.round((snapshot.profile.gravity || 0) * 10) / 10);
      panel.dataset.gravitySource = snapshot.profile.gravitySource || '';
      panel.dataset.originSource = snapshot.profile.originSource || '';
    }
    if (snapshot && snapshot.me) {
      panel.dataset.look = String(snapshot.me.look);
      panel.dataset.lookSource = snapshot.me.lookSource || '';
      panel.dataset.aimSource = snapshot.me.aimSource || '';
      panel.dataset.aimRaw = snapshot.me.aimRaw == null ? '' : String(Math.round(snapshot.me.aimRaw));
      panel.dataset.aimMirrored = snapshot.me.aimMirrored ? '1' : '';
      panel.dataset.aimCheck = snapshot.aimCheck && snapshot.aimCheck.residual != null
        ? String(snapshot.aimCheck.residual) : '';
      panel.dataset.body = String(Math.round(snapshot.me.body || 0));
      panel.dataset.scaleX = snapshot.me.scaleX == null ? '' : String(snapshot.me.scaleX);
    }
    publishDebug({
      origin: origin,
      originClient: worldToClient(origin, snapshot),
      angle: angAbs,
      destination: destination,
      destClient: destClient,
      solution: solution,
      trajectory: lastTrajectory,
      marker: lastMarker,
      look: snapshot && snapshot.me ? snapshot.me.look : null,
      lookSource: snapshot && snapshot.me ? snapshot.me.lookSource : null,
      aimSource: snapshot && snapshot.me ? snapshot.me.aimSource : null,
      aimRaw: snapshot && snapshot.me ? snapshot.me.aimRaw : null,
      aimMirrored: snapshot && snapshot.me ? snapshot.me.aimMirrored : null,
      aimCheck: snapshot ? snapshot.aimCheck : null,
      body: snapshot && snapshot.me ? snapshot.me.body : null,
    });
  }

  function publishDebug(value) {
    if (!testMode) return;
    window.__DS_CONTENT_DEBUG__ = value;
  }

  function setText(el, text) {
    if (el) el.textContent = text;
  }

  function setState(text, kind) {
    if (!els.state) return;
    els.state.textContent = text;
    els.state.className = 'ds-state' + (kind ? ' ' + kind : '');
  }

  /**
   * Respaldo del origen cuando el snapshot no lo trae. Debe coincidir con
   * `AutoCalibration.originFor`: el espejo por `look` se aplica al offset
   * local (a, −c) antes de rotar por `body`, no al `dx` ya rotado.
   */
  function computeOrigin(me, profile) {
    const a = profile.origin ? profile.origin.a : FALLBACK.origin.a;
    const c = profile.origin ? profile.origin.c : FALLBACK.origin.c;
    const theta = (me.body || 0) * Math.PI / 180;
    const cos = Math.cos(theta);
    const sin = Math.sin(theta);
    const sx = me.look === 0 ? -1 : 1;
    const dx = sx * a * cos + c * sin;
    const dy = sx * a * sin - c * cos;
    return { x: me.x + dx, y: me.y + dy };
  }

  /**
   * `angAbs` ya viene en grados matemáticos (Y hacia arriba) desde el hook.
   * `aimDeg` es el mismo valor; se conserva como respaldo por compatibilidad.
   */
  function effectiveAngle(me) {
    if (me.angAbs != null) return normalizeDeg(me.angAbs);
    if (me.aimDeg == null) return null;
    return normalizeDeg(me.aimDeg);
  }

  function normalizeDeg(value) {
    let deg = value % 360;
    if (deg < 0) deg += 360;
    return deg;
  }

  function worldToClient(world, snap) {
    if (!snap || !snap.canvas || !snap.canvas.rect || !snap.canvas.width) return null;
    const c = snap.canvas;
    const sx = snap.zoom * (world.x + snap.camera.x);
    const sy = snap.zoom * (world.y + snap.camera.y);
    return {
      x: c.rect.left + sx * (c.rect.width / c.width),
      y: c.rect.top + sy * (c.rect.height / c.height),
    };
  }

  function clientToWorld(clientX, clientY, snap) {
    if (!snap || !snap.canvas || !snap.canvas.rect || !snap.canvas.rect.width) return null;
    const c = snap.canvas;
    const sx = (clientX - c.rect.left) * (c.width / c.rect.width);
    const sy = (clientY - c.rect.top) * (c.height / c.rect.height);
    return {
      x: sx / snap.zoom - snap.camera.x,
      y: sy / snap.zoom - snap.camera.y,
    };
  }

  function inViewport(point, margin) {
    const m = margin == null ? 0 : margin;
    return point.x >= -m && point.y >= -m &&
      point.x <= window.innerWidth + m && point.y <= window.innerHeight + m;
  }

  function solvePower(origin, target, angAbs, profile) {
    if (angAbs == null) return { ok: false, reason: 'SIN ÁNGULO' };
    const gravity = Number(profile.gravity) || FALLBACK.gravity;
    const powerScale = Number(profile.powerScale) || FALLBACK.powerScale;
    const theta = angAbs * Math.PI / 180;
    const cos = Math.cos(theta);
    const sin = Math.sin(theta);
    if (Math.abs(cos) < 1e-5) return { ok: false, reason: 'ÁNGULO VERTICAL' };

    const dx = target.x - origin.x;
    const dy = target.y - origin.y;
    const K = dx / cos;
    if (!(K > 0)) return { ok: false, reason: 'OBJETIVO DETRÁS' };

    const inside = 2 * (dy + sin * K) / gravity;
    if (!(inside > 0)) return { ok: false, reason: 'SIN SOLUCIÓN' };

    const tau = Math.sqrt(inside);
    const v0 = K / tau;
    const power = v0 / powerScale;
    if (!(power >= 0 && power <= 400)) {
      return { ok: false, reason: 'INALCANZABLE', power: power };
    }
    return { ok: true, power: power, tau: tau, v0: v0 };
  }

  function drawTrajectory(origin, angAbs, power, profile, color, maxTime, snap) {
    if (angAbs == null || !(power > 0)) return null;
    const gravity = Number(profile.gravity) || FALLBACK.gravity;
    const powerScale = Number(profile.powerScale) || FALLBACK.powerScale;
    const theta = angAbs * Math.PI / 180;
    const v0 = power * powerScale;
    const vx = Math.cos(theta) * v0;
    const vy = -Math.sin(theta) * v0;
    const tMax = maxTime && maxTime > 0 ? maxTime : 8;
    const steps = 110;

    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.setLineDash(maxTime ? [] : [6, 5]);
    ctx.beginPath();

    let started = false;
    const points = [];
    for (let i = 0; i <= steps; i++) {
      const t = tMax * i / steps;
      const world = {
        x: origin.x + vx * t,
        y: origin.y + vy * t + 0.5 * gravity * t * t,
      };
      const point = worldToClient(world, snap);
      if (!point) break;
      points.push({ x: point.x, y: point.y, t: t });
      if (!started) {
        ctx.moveTo(point.x, point.y);
        started = true;
      } else {
        ctx.lineTo(point.x, point.y);
      }
      if (point.x < -500 || point.x > window.innerWidth + 500 ||
          point.y > window.innerHeight + 400) break;
    }
    ctx.stroke();
    ctx.restore();
    return {
      color: color,
      points: points,
      start: points[0] || null,
      end: points[points.length - 1] || null,
      vx: vx,
      vy: vy,
      tMax: tMax,
    };
  }

  function drawMarker(point, color, label) {
    ctx.save();
    ctx.strokeStyle = color;
    ctx.fillStyle = 'rgba(6,20,28,.75)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(point.x, point.y, 10, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(point.x - 16, point.y);
    ctx.lineTo(point.x + 16, point.y);
    ctx.moveTo(point.x, point.y - 16);
    ctx.lineTo(point.x, point.y + 16);
    ctx.stroke();
    drawLabel(label, point.x + 15, point.y - 18, color);
    ctx.restore();
  }

  function drawLabel(text, x, y, color) {
    ctx.save();
    ctx.font = '700 10px ui-sans-serif,system-ui,sans-serif';
    const width = ctx.measureText(text).width + 10;
    const height = 16;
    ctx.fillStyle = 'rgba(6,20,28,.86)';
    ctx.strokeStyle = color;
    ctx.lineWidth = 1;
    roundRect(x, y - height + 3, width, height, 5);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = color;
    ctx.textBaseline = 'middle';
    ctx.fillText(text, x + 5, y - height / 2 + 3);
    ctx.restore();
  }

  function roundRect(x, y, width, height, radius) {
    const r = Math.min(radius, width / 2, height / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + width, y, x + width, y + height, r);
    ctx.arcTo(x + width, y + height, x, y + height, r);
    ctx.arcTo(x, y + height, x, y, r);
    ctx.arcTo(x, y, x + width, y, r);
    ctx.closePath();
  }

  function powerBarRect() {
    // `#powerMarkArea` y `#powerMarks` representan 0..400 (ancho completo de
    // la barra, ya escalado por el zoom del juego). `#powerBar` solo mide la
    // potencia actual, por eso es el último recurso.
    const selectors = ['#powerMarkArea', '#powerMarks', '#powerBar'];
    let fallback = null;
    for (let i = 0; i < selectors.length; i++) {
      const el = document.querySelector(selectors[i]);
      if (!el) continue;
      const rect = el.getBoundingClientRect();
      if (!(rect.width > 0 && rect.height >= 0)) continue;
      if (i < selectors.length - 1) return { el: el, rect: rect };
      fallback = { el: el, rect: rect };
    }
    return fallback;
  }

  function drawPowerMarker(power, color, label) {
    if (!(power >= 0 && power <= 400)) return null;
    const bar = powerBarRect();
    if (!bar) return null;
    const rect = bar.rect;

    const x = rect.left + rect.width * (power / 400);
    const top = rect.top - 10;
    const bottom = rect.bottom + 10;
    ctx.save();

    // Contorno oscuro para que la marca se vea sobre cualquier fondo.
    ctx.strokeStyle = 'rgba(6,20,28,.92)';
    ctx.lineWidth = 5;
    ctx.beginPath();
    ctx.moveTo(x, top);
    ctx.lineTo(x, bottom);
    ctx.stroke();

    ctx.strokeStyle = color;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.moveTo(x, top);
    ctx.lineTo(x, bottom);
    ctx.stroke();

    // Punta superior tipo aguja.
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(x, top - 1);
    ctx.lineTo(x - 5, top - 9);
    ctx.lineTo(x + 5, top - 9);
    ctx.closePath();
    ctx.fill();

    const text = label + ' ' + Math.round(power);
    ctx.font = '700 10px ui-sans-serif,system-ui,sans-serif';
    const labelWidth = ctx.measureText(text).width + 10;
    const labelX = clamp(x + 7, 2, Math.max(2, window.innerWidth - labelWidth - 2));
    drawLabel(text, labelX, top - 10, color);
    ctx.restore();

    return {
      x: x,
      top: top,
      bottom: bottom,
      barLeft: rect.left,
      barWidth: rect.width,
      power: power,
      label: text,
    };
  }

  function insideCanvas(clientX, clientY) {
    if (!snapshot || !snapshot.ok || !snapshot.canvas || !snapshot.canvas.rect) return false;
    const rect = snapshot.canvas.rect;
    return clientX >= rect.left && clientX <= rect.left + rect.width &&
      clientY >= rect.top && clientY <= rect.top + rect.height;
  }

  function nearOwnPlayer(clientX, clientY) {
    if (!snapshot || !snapshot.ok) return false;
    const point = worldToClient({ x: snapshot.me.x, y: snapshot.me.y }, snapshot);
    if (!point) return false;
    const dx = clientX - point.x;
    const dy = clientY - point.y;
    return Math.sqrt(dx * dx + dy * dy) <= 70;
  }

  function eventFromOverlay(event) {
    const target = event.target;
    if (!target || target === window || target === document) return true;
    return !!(host && target.nodeType && host.contains(target));
  }

  function onPointerDown(event) {
    if (eventFromOverlay(event)) return;

    // Modo "Seleccionar destino": la capa visual deja pasar el ratón, por lo
    // que el juego ya recibió los `mousemove` y apuntó el cañón. Aquí se
    // intercepta solo el clic final para no disparar ni mover al jugador.
    if (selectionMode) {
      if (event.button === 2) {
        event.preventDefault();
        event.stopImmediatePropagation();
        suppressMouseUntil = Date.now() + 500;
        suppressClickUntil = Date.now() + 500;
        setSelectionMode(false);
        return;
      }
      if (event.button !== 0) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      suppressMouseUntil = Date.now() + 500;
      suppressClickUntil = Date.now() + 500;
      if (!insideCanvas(event.clientX, event.clientY)) {
        setSelectionMode(false);
        return;
      }
      selectDestinationAt(event.clientX, event.clientY);
      return;
    }

    // Ctrl + clic izquierdo/derecho sigue disponible como atajo.
    if (!event.ctrlKey || (event.button !== 0 && event.button !== 2)) return;
    if (!insideCanvas(event.clientX, event.clientY)) return;

    event.preventDefault();
    event.stopImmediatePropagation();
    suppressMouseUntil = Date.now() + 500;
    if (event.button === 0) suppressClickUntil = Date.now() + 500;

    const world = clientToWorld(event.clientX, event.clientY, snapshot);
    if (!world) return;

    const forceDestination = event.shiftKey;
    if (!forceDestination && (step === 'origin' || nearOwnPlayer(event.clientX, event.clientY))) {
      step = 'destination';
      originConfirmed = true;
      // El origen no se guarda como punto de pantalla: siempre se recalcula
      // desde el cañón del jugador propio en el snapshot.
      return;
    }

    destination = { x: world.x, y: world.y };
    step = 'destination';
    originConfirmed = true;
    requestSnapshot();
  }

  function onSelectionMouseSuppress(event) {
    if (Date.now() > suppressMouseUntil) return;
    if (eventFromOverlay(event)) return;
    if (!insideCanvas(event.clientX, event.clientY)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (event.type === 'mouseup') suppressMouseUntil = 0;
  }

  function onSelectionClickSuppress(event) {
    if (Date.now() > suppressClickUntil) return;
    if (eventFromOverlay(event)) return;
    if (!insideCanvas(event.clientX, event.clientY)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    suppressClickUntil = 0;
  }

  function onContextMenu(event) {
    if (eventFromOverlay(event)) return;
    if (selectionMode) {
      event.preventDefault();
      event.stopImmediatePropagation();
      suppressMouseUntil = Date.now() + 500;
      setSelectionMode(false);
      return;
    }
    if (!event.ctrlKey) return;
    if (!insideCanvas(event.clientX, event.clientY)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  function onKeyDown(event) {
    if (event.key !== 'Escape') return;
    if (selectionMode) {
      setSelectionMode(false);
      return;
    }
    clearDestination();
  }

  // Arrancar cuando el documento exista. En document_start suele existir
  // documentElement aunque body todavía no.
  init();
})();
