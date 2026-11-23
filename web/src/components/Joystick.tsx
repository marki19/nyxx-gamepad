import React, { useRef, useEffect, useState, useCallback } from 'react';
import { ble } from '../services/bleService';

interface JoystickProps {
  activeMode: string | null;
}

export const Joystick: React.FC<JoystickProps> = ({ activeMode }) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  const [angleReadout, setAngleReadout] = useState<string>('Touch to steer');

  // Internal mutable state for high-performance animation/touch loops
  const state = useRef({
    centerX: 120,
    centerY: 120,
    baseRadius: 85,
    knobRadius: 32,
    knobX: 120,
    knobY: 120,
    isDragging: false,
    activeTouchId: null as number | null,
    lastSentDegree: -999,
    lastSendTimestamp: 0,
    keepAliveTimer: null as any,
  });

  const drawJoystick = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const { centerX, centerY, baseRadius, knobRadius, knobX, knobY, isDragging } = state.current;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Outer Glow Ring
    ctx.beginPath();
    ctx.arc(centerX, centerY, baseRadius, 0, Math.PI * 2);
    ctx.fillStyle = '#161B22';
    ctx.fill();
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = isDragging ? '#66FCF1' : '#1F2833';
    ctx.stroke();

    // Deadzone Circle (20%)
    ctx.beginPath();
    ctx.arc(centerX, centerY, baseRadius * 0.2, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255, 255, 255, 0.03)';
    ctx.fill();
    ctx.lineWidth = 1;
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.08)';
    ctx.setLineDash([4, 4]);
    ctx.stroke();
    ctx.setLineDash([]);

    // Crosshair Lines
    ctx.strokeStyle = '#2A3240';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(centerX - baseRadius + 10, centerY);
    ctx.lineTo(centerX + baseRadius - 10, centerY);
    ctx.moveTo(centerX, centerY - baseRadius + 10);
    ctx.lineTo(centerX, centerY + baseRadius - 10);
    ctx.stroke();

    // Connector Line
    if (isDragging) {
      ctx.beginPath();
      ctx.moveTo(centerX, centerY);
      ctx.lineTo(knobX, knobY);
      ctx.strokeStyle = 'rgba(102, 252, 241, 0.35)';
      ctx.lineWidth = 3;
      ctx.stroke();
    }

    // Knob Outer Shadow
    ctx.save();
    ctx.shadowColor = isDragging ? 'rgba(102, 252, 241, 0.6)' : 'rgba(0, 0, 0, 0.4)';
    ctx.shadowBlur = isDragging ? 16 : 8;

    // Knob Body
    ctx.beginPath();
    ctx.arc(knobX, knobY, knobRadius, 0, Math.PI * 2);
    ctx.fillStyle = isDragging ? '#45A29E' : '#2A3240';
    ctx.fill();
    ctx.lineWidth = 2;
    ctx.strokeStyle = isDragging ? '#66FCF1' : '#8B949E';
    ctx.stroke();
    ctx.restore();

    // Knob Center Dot
    ctx.beginPath();
    ctx.arc(knobX, knobY, 6, 0, Math.PI * 2);
    ctx.fillStyle = isDragging ? '#0B0C10' : '#8B949E';
    ctx.fill();
  }, []);

  const resizeCanvas = useCallback(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container) return;

    const rect = container.getBoundingClientRect();
    const size = Math.min(rect.width, rect.height) || 220;
    const dpr = window.devicePixelRatio || 1;

    canvas.width = size * dpr;
    canvas.height = size * dpr;
    canvas.style.width = `${size}px`;
    canvas.style.height = `${size}px`;

    const ctx = canvas.getContext('2d');
    if (ctx) ctx.scale(dpr, dpr);

    state.current.centerX = size / 2;
    state.current.centerY = size / 2;
    state.current.baseRadius = size * 0.38;
    state.current.knobRadius = size * 0.15;
    state.current.knobX = size / 2;
    state.current.knobY = size / 2;

    drawJoystick();
  }, [drawJoystick]);

  useEffect(() => {
    resizeCanvas();
    window.addEventListener('resize', resizeCanvas);
    return () => window.removeEventListener('resize', resizeCanvas);
  }, [resizeCanvas]);

  const startKeepAlive = () => {
    stopKeepAlive();
    state.current.keepAliveTimer = setInterval(() => {
      const s = state.current;
      if (s.isDragging && s.lastSentDegree !== -999) {
        ble.queueJoystick(s.lastSentDegree);
      }
    }, 100);
  };

  const stopKeepAlive = () => {
    if (state.current.keepAliveTimer) {
      clearInterval(state.current.keepAliveTimer);
      state.current.keepAliveTimer = null;
    }
  };

  const processCoords = (clientX: number, clientY: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const touchX = clientX - rect.left;
    const touchY = clientY - rect.top;

    const s = state.current;
    const dx = touchX - s.centerX;
    const dy = touchY - s.centerY;
    const dist = Math.hypot(dx, dy);

    if (dist <= s.baseRadius) {
      s.knobX = touchX;
      s.knobY = touchY;
    } else {
      const angle = Math.atan2(dy, dx);
      s.knobX = s.centerX + Math.cos(angle) * s.baseRadius;
      s.knobY = s.centerY + Math.sin(angle) * s.baseRadius;
    }

    drawJoystick();

    // Deadzone Check (20%)
    if (dist > s.baseRadius * 0.2) {
      const rad = Math.atan2(-dy, dx);
      const rawDeg = Math.round(rad * (180 / Math.PI));
      const quantized = Math.min(180, Math.max(-180, Math.round(rawDeg / 15) * 15));

      setAngleReadout(`Steering: ${quantized}°`);

      const now = performance.now();
      if (quantized !== s.lastSentDegree && (now - s.lastSendTimestamp >= 50)) {
        s.lastSendTimestamp = now;
        s.lastSentDegree = quantized;
        ble.queueJoystick(quantized);
      }
    } else {
      if (s.lastSentDegree !== -999) {
        s.lastSentDegree = -999;
        setAngleReadout('Touch to steer');
        ble.queueStop();
      }
    }
  };

  const releaseJoystick = () => {
    const s = state.current;
    s.isDragging = false;
    s.activeTouchId = null;
    s.lastSentDegree = -999;
    stopKeepAlive();
    setAngleReadout(activeMode ? 'Auto Active' : 'Touch to steer');
    ble.queueStop();

    // Spring-back animation with overshoot ease
    const startX = s.knobX;
    const startY = s.knobY;
    const startTime = performance.now();
    const animDuration = 180;

    const animStep = (now: number) => {
      const progress = Math.min((now - startTime) / animDuration, 1);
      const ease = 1 + 2.70158 * Math.pow(progress - 1, 3) + 1.70158 * Math.pow(progress - 1, 2);
      s.knobX = startX + (s.centerX - startX) * ease;
      s.knobY = startY + (s.centerY - startY) * ease;
      drawJoystick();
      if (progress < 1) {
        requestAnimationFrame(animStep);
      } else {
        s.knobX = s.centerX;
        s.knobY = s.centerY;
        drawJoystick();
      }
    };
    requestAnimationFrame(animStep);
  };

  // Touch Event Listeners
  const handleTouchStart = (e: React.TouchEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const s = state.current;
    if (s.isDragging) return;
    const t = e.changedTouches[0];
    s.activeTouchId = t.identifier;
    s.isDragging = true;
    processCoords(t.clientX, t.clientY);
    startKeepAlive();
  };

  const handleTouchMove = (e: React.TouchEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const s = state.current;
    if (!s.isDragging) return;
    for (let i = 0; i < e.changedTouches.length; i++) {
      if (e.changedTouches[i].identifier === s.activeTouchId) {
        processCoords(e.changedTouches[i].clientX, e.changedTouches[i].clientY);
        break;
      }
    }
  };

  const handleTouchEnd = (e: React.TouchEvent<HTMLCanvasElement>) => {
    const s = state.current;
    if (!s.isDragging) return;
    for (let i = 0; i < e.changedTouches.length; i++) {
      if (e.changedTouches[i].identifier === s.activeTouchId) {
        releaseJoystick();
        break;
      }
    }
  };

  // Mouse fallback
  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    state.current.isDragging = true;
    processCoords(e.clientX, e.clientY);
    startKeepAlive();
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (state.current.isDragging) processCoords(e.clientX, e.clientY);
    };
    const handleMouseUp = () => {
      if (state.current.isDragging) releaseJoystick();
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  return (
    <div className="joystick-wrapper">
      <div className="card-header">
        <span className="card-title">DIRECTION &amp; DRIVE</span>
        <span className="live-readout">{angleReadout}</span>
      </div>
      <div className="joystick-container" ref={containerRef}>
        <canvas
          ref={canvasRef}
          onTouchStart={handleTouchStart}
          onTouchMove={handleTouchMove}
          onTouchEnd={handleTouchEnd}
          onTouchCancel={handleTouchEnd}
          onMouseDown={handleMouseDown}
        />
      </div>
    </div>
  );
};
