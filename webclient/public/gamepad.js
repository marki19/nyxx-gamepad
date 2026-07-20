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

  // ── Gyro button ──────────────────────────────────────────────────────────
  function initGyroButton() {
    const btnGyro = document.getElementById('btn-gyro');
    const btnCal  = document.getElementById('btn-calibrate');

    btnGyro.addEventListener('click', async () => {
      const on = await window.gyroToggle();
      btnGyro.innerHTML  = on ? 'DANCE MODE: ON<br><br><span style="font-size: 16px; font-weight: normal; color: #88ff88;">Streaming Motion...</span>'  : 'DANCE MODE: OFF<br><br><span style="font-size: 16px; font-weight: normal; color: #888;">Tap to Start</span>';
      btnGyro.style.borderColor = on ? '#00ff88' : '#333';
      btnGyro.style.boxShadow = on ? '0 0 40px rgba(0, 255, 136, 0.4)' : '0 10px 30px rgba(0,0,0,0.5)';
      btnCal.classList.toggle('hidden', !on);
    });

    btnCal.addEventListener('click', () => {
      window.gyroCalibrate();
      
      window.PAD.lx = 0; window.PAD.ly = 0;
      window.PAD.rx = 0; window.PAD.ry = 0;

      // Brief flash feedback
      btnCal.textContent = 'ZEROED!';
      setTimeout(() => { btnCal.textContent = 'CALIBRATE'; }, 800);
    });
  }

  // ── Connection status UI ───────────────────────────────────────────────
  const overlay       = document.getElementById('overlay');
  const screenConnect = document.getElementById('screen-connect');
  const screenStatus  = document.getElementById('screen-status');
  const gamepadEl     = document.getElementById('gamepad');
  const overlayStatus = document.getElementById('overlay-status');
  const overlayDetail = document.getElementById('overlay-detail');
  const retryBtn      = document.getElementById('btn-retry');
  const badge         = document.getElementById('conn-badge');

  // ── Connect form ──
  const inpIp    = document.getElementById('inp-ip');
  const inpPort  = document.getElementById('inp-port');
  const btnConn  = document.getElementById('btn-connect');
  const certLink = document.getElementById('cert-link');

  // Pre-fill from localStorage
  let defaultIp   = localStorage.getItem('nyxx-ip')   || '';
  let defaultPort = localStorage.getItem('nyxx-port')  || '5001';

  // If loaded directly from a local IP (e.g. scanned QR code to local bridge)
  if (window.location.hostname !== 'localhost' && window.location.hostname.match(/^[0-9\.]+$/)) {
    defaultIp = window.location.hostname;
    defaultPort = window.location.port || '5001';
    
    // Auto-connect seamlessly
    setTimeout(() => {
      if (inpIp.value === defaultIp) {
        window.nyxxConnect(defaultIp, defaultPort);
      }
    }, 50);
  }

  inpIp.value   = defaultIp;
  inpPort.value = defaultPort;

  function updateCertLink() {
    const ip   = inpIp.value.trim();
    const port = inpPort.value.trim() || '5001';
    certLink.textContent = ip ? `https://${ip}:${port}` : `https://<ip>:${port}`;
  }
  inpIp.addEventListener('input',   updateCertLink);
  inpPort.addEventListener('input',  updateCertLink);
  updateCertLink();

  function showConnectScreen() {
    overlay.classList.remove('hidden');
    gamepadEl.classList.add('hidden');
    screenConnect.classList.remove('hidden');
    screenStatus.classList.add('hidden');
  }

  function showStatusScreen(msg, detail, showRetry, showTrust) {
    overlay.classList.remove('hidden');
    gamepadEl.classList.add('hidden');
    screenConnect.classList.add('hidden');
    screenStatus.classList.remove('hidden');
    overlayStatus.textContent = msg;
    overlayDetail.textContent = detail || '';
    retryBtn.classList.toggle('hidden', !showRetry);
    
    const trustBtn = document.getElementById('btn-trust-cert');
    if (trustBtn) {
      trustBtn.classList.toggle('hidden', !showTrust);
      if (showTrust) {
        const ip = inpIp.value.trim() || window.location.hostname;
        const port = inpPort.value.trim() || '5001';
        trustBtn.href = `https://${ip}:${port}/auth`;
      }
    }
  }

  btnConn.addEventListener('click', async (e) => {
    const ip   = inpIp.value.trim();
    const port = inpPort.value.trim() || '5001';
    if (!ip) { inpIp.focus(); return; }

    // If it's a manual user click, try enabling gyro immediately
    if (e && e.isTrusted && !window.gyroEnabled()) {
      const on = await window.gyroToggle();
      const btnGyro = document.getElementById('btn-gyro');
      const btnCal  = document.getElementById('btn-calibrate');
      if (btnGyro && btnCal) {
        btnGyro.innerHTML  = on ? 'DANCE MODE: ON<br><br><span style="font-size: 16px; font-weight: normal; color: #88ff88;">Streaming Motion...</span>'  : 'DANCE MODE: OFF<br><br><span style="font-size: 16px; font-weight: normal; color: #888;">Tap to Start</span>';
        btnGyro.style.borderColor = on ? '#00ff88' : '#333';
        btnGyro.style.boxShadow = on ? '0 0 40px rgba(0, 255, 136, 0.4)' : '0 10px 30px rgba(0,0,0,0.5)';
        btnCal.classList.toggle('hidden', !on);
      }
    }

    window.nyxxConnect(ip, port);
  });

  // Seamless auto-gyro on first touch if they bypassed the connect screen
  let autoGyroAttempted = false;
  document.addEventListener('pointerdown', async (e) => {
    if (!autoGyroAttempted && !window.gyroEnabled() && !screenConnect.classList.contains('hidden') === false) {
      autoGyroAttempted = true;
      try {
        const on = await window.gyroToggle();
        const btnGyro = document.getElementById('btn-gyro');
        const btnCal  = document.getElementById('btn-calibrate');
        if (btnGyro && btnCal) {
          btnGyro.innerHTML  = on ? 'DANCE MODE: ON<br><br><span style="font-size: 16px; font-weight: normal; color: #88ff88;">Streaming Motion...</span>'  : 'DANCE MODE: OFF<br><br><span style="font-size: 16px; font-weight: normal; color: #888;">Tap to Start</span>';
          btnGyro.style.borderColor = on ? '#00ff88' : '#333';
          btnGyro.style.boxShadow = on ? '0 0 40px rgba(0, 255, 136, 0.4)' : '0 10px 30px rgba(0,0,0,0.5)';
          btnCal.classList.toggle('hidden', !on);
        }
      } catch (err) {}
    }
  }, { capture: true, passive: true });

  // Allow Enter key in IP field
  inpIp.addEventListener('keydown', (e) => { if (e.key === 'Enter') btnConn.click(); });

  retryBtn.addEventListener('click', () => window.nyxxReconnect());

  window.addEventListener('nyxx:status', ({ detail }) => {
    if (detail.state === 'connecting') {
      showStatusScreen('Connecting to server…', '', false, false);
      badge.className  = 'conn-badge conn-badge--connecting';
      badge.textContent = '● …';
    } else if (detail.state === 'disconnected') {
      showStatusScreen('Disconnected. Retrying…', '', true, false);
    }
  });

  window.addEventListener('nyxx:connected', ({ detail }) => {
    overlay.classList.add('hidden');
    gamepadEl.classList.remove('hidden');
    badge.className  = 'conn-badge conn-badge--ok';
    badge.textContent = `● P${detail.player}`;
  });

  window.addEventListener('nyxx:error', ({ detail }) => {
    showStatusScreen('Could not connect.', detail.msg, true, true);
  });

  // "Change IP" retry goes back to connect form
  window.addEventListener('nyxx:showConnect', () => showConnectScreen());

  // ── Init ──────────────────────────────────────────────────────────────────
  initGyroButton();

  // Lock to landscape on mobile browsers that support it
  try {
    screen.orientation.lock('landscape').catch(() => {});
  } catch {}

  // Prevent context menus on long-press
  window.addEventListener('contextmenu', (e) => e.preventDefault());

})();
