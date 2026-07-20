'use strict';
/**
 * gamepad.js — Touch input handling and UI feedback
 *
 * Reads from window.PAD (written by ws.js / gyro.js).
 * Handles:
 *  - Analog joysticks (Pointer Events, multi-touch)
 *  - D-pad, face buttons, shoulders, triggers, meta buttons
 *  - Connection status UI (overlay, badge)
 *  - Gyro toggle button
 */

(function () {

  // ── Stick state ──────────────────────────────────────────────────────────
  const sticks = {
    left:  { el: null, knob: null, active: false, pointerId: -1, cx: 0, cy: 0, r: 0 },
    right: { el: null, knob: null, active: false, pointerId: -1, cx: 0, cy: 0, r: 0 },
  };

  const MAX_AXIS = 32767;
  const DEADZONE = 0.08; // fractional dead zone

  function initStick(side, zoneId, knobId) {
    const s    = sticks[side];
    const ring = document.querySelector(`#${zoneId} .stick-ring`);
    s.el   = ring;
    s.knob = document.getElementById(knobId);

    ring.addEventListener('pointerdown',  (e) => stickDown(s, e), { passive: false });
    ring.addEventListener('pointermove',  (e) => stickMove(s, e), { passive: false });
    ring.addEventListener('pointerup',    (e) => stickUp(s, e, side));
    ring.addEventListener('pointercancel',(e) => stickUp(s, e, side));
  }

  function stickDown(s, e) {
    if (s.active) return;
    e.preventDefault();
    ring(s).setPointerCapture(e.pointerId);
    s.active = true; s.pointerId = e.pointerId;
    const rect = ring(s).getBoundingClientRect();
    s.cx = rect.left + rect.width  / 2;
    s.cy = rect.top  + rect.height / 2;
    s.r  = rect.width / 2;
    ring(s).classList.add('active');
    updateStick(s, e.clientX, e.clientY, s === sticks.left);
  }

  function stickMove(s, e) {
    if (!s.active || e.pointerId !== s.pointerId) return;
    e.preventDefault();
    updateStick(s, e.clientX, e.clientY, s === sticks.left);
  }

  function stickUp(s, e, side) {
    if (!s.active || e.pointerId !== s.pointerId) return;
    s.active = false;
    ring(s).classList.remove('active');
    s.knob.style.transform = 'translate(-50%, -50%)';
    if (side === 'left') { window.PAD.lx = 0; window.PAD.ly = 0; }
    else                 { window.PAD.rx = 0; window.PAD.ry = 0; }
  }

  function ring(s) { return s.el; }

  function updateStick(s, px, py, isLeft) {
    let dx = px - s.cx;
    let dy = py - s.cy;
    const dist = Math.sqrt(dx * dx + dy * dy);
    const maxR = s.r - 4; // knob edge
    if (dist > maxR) {
      const scale = maxR / dist;
      dx *= scale; dy *= scale;
    }
    // Position knob visually
    s.knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;

    // Map to -1..1 with deadzone
    let nx = dx / maxR;
    let ny = dy / maxR;
    const m = Math.sqrt(nx * nx + ny * ny);
    if (m < DEADZONE) { nx = 0; ny = 0; }
    else {
      const scaled = (m - DEADZONE) / (1 - DEADZONE);
      nx = (nx / m) * scaled;
      ny = (ny / m) * scaled;
    }

    const ax = Math.round(nx * MAX_AXIS);
    const ay = Math.round(ny * MAX_AXIS);
    if (isLeft) { window.PAD.lx = ax; window.PAD.ly = ay; }
    else        { window.PAD.rx = ax; window.PAD.ry = ay; }
  }

  // ── Buttons ──────────────────────────────────────────────────────────────
  function initButtons() {
    // Every element with data-bit is a binary button
    document.querySelectorAll('[data-bit]').forEach((el) => {
      const bit = parseInt(el.dataset.bit, 16);
      el.addEventListener('pointerdown',  (e) => { e.preventDefault(); setBtn(bit, true,  el); }, { passive: false });
      el.addEventListener('pointerup',    ()  => setBtn(bit, false, el));
      el.addEventListener('pointercancel',()  => setBtn(bit, false, el));
    });

    // Triggers (LT / RT) — binary press
    document.getElementById('btn-lt').addEventListener('pointerdown',  (e) => { e.preventDefault(); setTrigger('lt', 255); }, { passive: false });
    document.getElementById('btn-lt').addEventListener('pointerup',    () => setTrigger('lt', 0));
    document.getElementById('btn-lt').addEventListener('pointercancel',() => setTrigger('lt', 0));

    document.getElementById('btn-rt').addEventListener('pointerdown',  (e) => { e.preventDefault(); setTrigger('rt', 255); }, { passive: false });
    document.getElementById('btn-rt').addEventListener('pointerup',    () => setTrigger('rt', 0));
    document.getElementById('btn-rt').addEventListener('pointercancel',() => setTrigger('rt', 0));
  }

  function setBtn(bit, pressed, el) {
    if (pressed) window.PAD.buttons |= bit;
    else         window.PAD.buttons &= ~bit;
    el.classList.toggle('pressed', pressed);
  }

  function setTrigger(which, val) {
    window.PAD[which] = val;
    const el = document.getElementById('btn-' + which);
    el.classList.toggle('pressed', val > 0);
  }

  // ── Gyro button ──────────────────────────────────────────────────────────
  function initGyroButton() {
    const btnGyro = document.getElementById('btn-gyro');
    const btnCal  = document.getElementById('btn-calibrate');

    btnGyro.addEventListener('click', async () => {
      const on = await window.gyroToggle();
      btnGyro.textContent  = on ? 'GYRO ON'  : 'GYRO OFF';
      btnGyro.classList.toggle('active', on);
      btnCal.classList.toggle('hidden', !on);
    });

    btnCal.addEventListener('click', () => {
      window.gyroCalibrate();
      // Brief flash feedback
      btnCal.textContent = 'ZEROED!';
      setTimeout(() => { btnCal.textContent = 'CALIBRATE'; }, 800);
    });
  }

  // ── Connection status UI ──────────────────────────────────────────────────
  const overlay     = document.getElementById('overlay');
  const gamepadEl   = document.getElementById('gamepad');
  const overlayStatus = document.getElementById('overlay-status');
  const overlayDetail = document.getElementById('overlay-detail');
  const retryBtn    = document.getElementById('btn-retry');
  const badge       = document.getElementById('conn-badge');

  retryBtn.addEventListener('click', () => window.nyxxReconnect());

  window.addEventListener('nyxx:status', ({ detail }) => {
    if (detail.state === 'connecting') {
      overlay.classList.remove('hidden');
      gamepadEl.classList.add('hidden');
      overlayStatus.textContent = 'Connecting to server…';
      overlayDetail.textContent = '';
      retryBtn.classList.add('hidden');
      badge.className = 'conn-badge conn-badge--connecting';
      badge.textContent = '● …';
    } else if (detail.state === 'disconnected') {
      overlay.classList.remove('hidden');
      gamepadEl.classList.add('hidden');
      overlayStatus.textContent = 'Disconnected. Retrying…';
      overlayDetail.textContent = '';
      retryBtn.classList.remove('hidden');
    }
  });

  window.addEventListener('nyxx:connected', ({ detail }) => {
    overlay.classList.add('hidden');
    gamepadEl.classList.remove('hidden');
    badge.className = 'conn-badge conn-badge--ok';
    badge.textContent = `● P${detail.player}`;
  });

  window.addEventListener('nyxx:error', ({ detail }) => {
    overlay.classList.remove('hidden');
    gamepadEl.classList.add('hidden');
    overlayStatus.textContent = 'Could not connect.';
    overlayDetail.textContent = detail.msg;
    retryBtn.classList.remove('hidden');
  });

  // ── Init ──────────────────────────────────────────────────────────────────
  initStick('left',  'zone-left',  'knob-left');
  initStick('right', 'zone-right', 'knob-right');
  initButtons();
  initGyroButton();

  // Lock to landscape on mobile browsers that support it
  try {
    screen.orientation.lock('landscape').catch(() => {});
  } catch {}

  // Prevent context menus on long-press
  window.addEventListener('contextmenu', (e) => e.preventDefault());

})();
