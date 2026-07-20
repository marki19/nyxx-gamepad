'use strict';
/**
 * ws.js — WebSocket connection + binary packet packing
 *
 * Shared state object (window.PAD) is read by gamepad.js and gyro.js.
 * This module is responsible for:
 *  - Connecting to the bridge server (same host, port 5001)
 *  - PING/PONG handshake
 *  - Packing and sending binary gamepad packets at 60 Hz
 *  - Relaying RUMBLE commands to the Vibration API
 *  - Auto-reconnect on disconnect
 */

window.PAD = {
  buttons: 0,       // 16-bit bitmask
  lx: 0, ly: 0,     // left stick  -32768..32767
  rx: 0, ry: 0,     // right stick -32768..32767
  lt: 0, rt: 0,     // triggers 0..255
  accelX: 0, accelY: 0, accelZ: 0,
  gyroX:  0, gyroY:  0, gyroZ:  0,
  hasMotion: false,
  seq: 0,
};

// Button bitmask constants exposed for gamepad.js
window.BTNS = {
  UP:    0x0001, DOWN:   0x0002, LEFT:  0x0004, RIGHT: 0x0008,
  START: 0x0010, BACK:   0x0020, L3:    0x0040, R3:    0x0080,
  LB:    0x0100, RB:     0x0200, GUIDE: 0x0400,
  A:     0x1000, B:      0x2000, X:     0x4000, Y:     0x8000,
};

// ── Internal state ─────────────────────────────────────────────────────────
let _ws            = null;
let _connected     = false;
let _rafHandle     = null;
let _pingTimer     = null;
let _reconnectTimer = null;

// ── Helpers ────────────────────────────────────────────────────────────────
const clamp16 = (v) => Math.max(-32768, Math.min(32767, v | 0));
const clamp8  = (v) => Math.max(0,      Math.min(255,   v | 0));

function packPacket() {
  const p = window.PAD;
  const size = p.hasMotion ? 39 : 15;
  const buf  = new ArrayBuffer(size);
  const v    = new DataView(buf);

  v.setUint8 (0,  1);
  v.setUint16(1,  p.seq++ & 0xFFFF,  false); // big-endian
  v.setUint16(3,  p.buttons,          false);
  v.setInt16 (5,  clamp16(p.lx),      false);
  v.setInt16 (7,  clamp16(-p.ly),     false); // screen Y→axis Y (invert)
  v.setInt16 (9,  clamp16(p.rx),      false);
  v.setInt16 (11, clamp16(-p.ry),     false);
  v.setUint8 (13, clamp8(p.lt));
  v.setUint8 (14, clamp8(p.rt));

  if (p.hasMotion) {
    v.setFloat32(15, p.accelX, false);
    v.setFloat32(19, p.accelY, false);
    v.setFloat32(23, p.accelZ, false);
    v.setFloat32(27, p.gyroX,  false);
    v.setFloat32(31, p.gyroY,  false);
    v.setFloat32(35, p.gyroZ,  false);
  }
  return buf;
}

// ── Send loop (60 Hz via rAF) ──────────────────────────────────────────────
function startSendLoop() {
  function loop() {
    if (_connected && _ws && _ws.readyState === WebSocket.OPEN) {
      _ws.send(packPacket());
    }
    _rafHandle = requestAnimationFrame(loop);
  }
  _rafHandle = requestAnimationFrame(loop);
}

function stopSendLoop() {
  if (_rafHandle !== null) { cancelAnimationFrame(_rafHandle); _rafHandle = null; }
}

// ── Rumble ─────────────────────────────────────────────────────────────────
function handleRumble(large, small) {
  if (!('vibrate' in navigator)) return;
  const intensity = Math.max(large, small) / 255;
  const duration  = Math.round(intensity * 120);
  if (duration > 0) navigator.vibrate(duration);
}

// ── Status events (consumed by gamepad.js UI) ──────────────────────────────
function emit(type, detail) {
  window.dispatchEvent(new CustomEvent('nyxx:' + type, { detail }));
}

// ── WebSocket lifecycle ────────────────────────────────────────────────────
function connect(ip, port) {
  clearTimeout(_reconnectTimer);
  emit('status', { state: 'connecting' });

  const url = `wss://${ip}:${port}`;
  _ws = new WebSocket(url);
  _ws.binaryType = 'arraybuffer';

  _ws.onopen = () => {
    _ws.send('PING');
    // Keep-alive ping every 2 s (server times out at 10 s)
    _pingTimer = setInterval(() => {
      if (_ws && _ws.readyState === WebSocket.OPEN) _ws.send('PING');
    }, 2000);
  };

  _ws.onmessage = ({ data }) => {
    if (typeof data !== 'string') return;

    if (data.startsWith('PONG:')) {
      const player = parseInt(data[5]) || 1;
      _connected = true;
      startSendLoop();
      emit('connected', { player });
    } else if (data === 'FULL') {
      emit('error', { msg: 'Server is full (8 players max).' });
    } else if (data === 'DISCONNECT') {
      emit('error', { msg: 'Server disconnected.' });
    } else if (data.startsWith('RUMBLE:')) {
      const [, l, s] = data.split(':');
      handleRumble(parseInt(l), parseInt(s));
    }
  };

  _ws.onclose = () => {
    _cleanup();
    emit('status', { state: 'disconnected' });
    // Auto-reconnect using last known IP/port
    const ip   = localStorage.getItem('nyxx-ip')   || '';
    const port = localStorage.getItem('nyxx-port')  || '5001';
    if (ip) _reconnectTimer = setTimeout(() => connect(ip, port), 3000);
  };

  _ws.onerror = () => {};
}

function _cleanup() {
  _connected = false;
  stopSendLoop();
  clearInterval(_pingTimer); _pingTimer = null;
}

// ── Manual connect (called by gamepad.js connect form) ─────────────────────
window.nyxxConnect = function (ip, port) {
  localStorage.setItem('nyxx-ip',   ip);
  localStorage.setItem('nyxx-port', port || '5001');
  if (_ws) { try { _ws.close(); } catch {} }
  connect(ip, port || '5001');
};

// ── Manual reconnect (retry button / change IP → go back to form) ──────────
window.nyxxReconnect = function () {
  clearTimeout(_reconnectTimer);
  if (_ws) { try { _ws.close(); } catch {} }
  emit('showConnect', {});
};

// ── Boot: do NOT auto-connect — wait for user to enter IP ─────────────────
// (gamepad.js will call window.nyxxConnect after form submit)
