'use strict';
/**
 * gyro.js — Gyroscope & accelerometer input
 *
 * Uses DeviceMotionEvent for both accel and gyro rates.
 * Applies:
 *  1. Calibration offset (current position = zero)
 *  2. Deadzone (ignore tiny noise)
 *  3. Low-pass filter (smooth out jitter)
 *
 * Writes directly into window.PAD.{gyroX,Y,Z,accelX,Y,Z,hasMotion}.
 */

(function () {
  // ── Tunables ──────────────────────────────────────────────────────────
  const ACCEL_DEADZONE = 0.05;   // m/s² noise floor
  const GYRO_DEADZONE  = 0.8;    // deg/s noise floor
  const LP_ALPHA       = 0.15;   // 0=frozen 1=no filter (lower = smoother)
  const DEG2RAD        = Math.PI / 180;
  const ACCEL_SCALE    = 9.8;    // normalise accel to ±1 g
  const GYRO_SCALE     = 360;    // normalise gyro to ±1 full-turn/sec

  // ── State ─────────────────────────────────────────────────────────────
  let enabled    = false;
  let calibrated = { ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0 };
  let smoothed   = { ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0 };
  let rawBuf     = { ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0 };

  // ── Low-pass helper ───────────────────────────────────────────────────
  function lp(prev, raw, deadzone, scale) {
    if (Math.abs(raw) < deadzone) raw = 0;
    const next = prev + LP_ALPHA * (raw - prev);
    return Math.abs(next) < 0.001 ? 0 : next / scale;
  }

  // ── DeviceMotionEvent handler ─────────────────────────────────────────
  function onMotion(e) {
    if (!enabled) return;

    const a = e.accelerationIncludingGravity || {};
    const g = e.rotationRate || {};

    // Raw readings
    rawBuf.ax = (a.x || 0) - calibrated.ax;
    rawBuf.ay = (a.y || 0) - calibrated.ay;
    rawBuf.az = (a.z || 0) - calibrated.az;
    rawBuf.gx = ((g.beta  || 0) * DEG2RAD) - calibrated.gx;
    rawBuf.gy = ((g.gamma || 0) * DEG2RAD) - calibrated.gy;
    rawBuf.gz = ((g.alpha || 0) * DEG2RAD) - calibrated.gz;

    // Filter & normalise
    smoothed.ax = lp(smoothed.ax, rawBuf.ax, ACCEL_DEADZONE, ACCEL_SCALE);
    smoothed.ay = lp(smoothed.ay, rawBuf.ay, ACCEL_DEADZONE, ACCEL_SCALE);
    smoothed.az = lp(smoothed.az, rawBuf.az, ACCEL_DEADZONE, ACCEL_SCALE);
    smoothed.gx = lp(smoothed.gx, rawBuf.gx, GYRO_DEADZONE * DEG2RAD, GYRO_SCALE * DEG2RAD);
    smoothed.gy = lp(smoothed.gy, rawBuf.gy, GYRO_DEADZONE * DEG2RAD, GYRO_SCALE * DEG2RAD);
    smoothed.gz = lp(smoothed.gz, rawBuf.gz, GYRO_DEADZONE * DEG2RAD, GYRO_SCALE * DEG2RAD);

    // Write into shared PAD state
    const p = window.PAD;
    p.accelX = smoothed.ax; p.accelY = smoothed.ay; p.accelZ = smoothed.az;
    p.gyroX  = smoothed.gx; p.gyroY  = smoothed.gy; p.gyroZ  = smoothed.gz;
    p.hasMotion = true;
  }

  // ── Calibration ───────────────────────────────────────────────────────
  function calibrate() {
    // Snapshot current raw reading as the zero point
    const a = rawBuf;
    calibrated.ax = a.ax + calibrated.ax;
    calibrated.ay = a.ay + calibrated.ay;
    calibrated.az = a.az + calibrated.az;
    calibrated.gx = a.gx + calibrated.gx;
    calibrated.gy = a.gy + calibrated.gy;
    calibrated.gz = a.gz + calibrated.gz;
    // Reset smoothed state
    smoothed = { ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0 };
  }

  // ── Permission request (required on iOS, optional on Android) ─────────
  async function requestPermission() {
    // iOS 13+ requires explicit permission
    if (typeof DeviceMotionEvent !== 'undefined' &&
        typeof DeviceMotionEvent.requestPermission === 'function') {
      const res = await DeviceMotionEvent.requestPermission();
      if (res !== 'granted') throw new Error('Permission denied by user.');
    }
    // All other browsers: just add the listener
  }

  // ── Toggle (called by gamepad.js gyro button) ─────────────────────────
  window.gyroToggle = async function () {
    if (!enabled) {
      try {
        await requestPermission();
        window.addEventListener('devicemotion', onMotion, { passive: true });
        enabled = true;
        return true;
      } catch (err) {
        console.warn('Gyro permission denied:', err.message);
        return false;
      }
    } else {
      window.removeEventListener('devicemotion', onMotion);
      enabled = false;
      window.PAD.hasMotion = false;
      return false;
    }
  };

  window.gyroCalibrate = calibrate;
  window.gyroEnabled   = () => enabled;
})();
