'use strict';

const https  = require('https');
const fs     = require('fs');
const path   = require('path');
const dgram  = require('dgram');
const os     = require('os');
const { WebSocketServer } = require('ws');

const cmdPort       = parseInt(process.argv[2]);
const GAMEPAD_PORT  = cmdPort || parseInt(process.env.NYXX_PORT) || 5000;
const BRIDGE_PORT   = GAMEPAD_PORT;
const GAMEPAD_HOST  = '127.0.0.1';
const CERT_DIR      = path.join(__dirname, 'cert');
const CERT_FILE     = path.join(CERT_DIR, 'cert.pem');
const KEY_FILE      = path.join(CERT_DIR, 'key.pem');
const PUBLIC_DIR    = path.join(__dirname, '..', 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css' : 'text/css',
  '.js'  : 'application/javascript',
  '.png' : 'image/png',
  '.ico' : 'image/x-icon',
  '.svg' : 'image/svg+xml',
};

// ── Helpers ────────────────────────────────────────────────────────────────

function getLocalIP() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const iface of ifaces) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address;
    }
  }
  return 'localhost';
}

function ensureCert() {
  if (fs.existsSync(CERT_FILE) && fs.existsSync(KEY_FILE)) {
    return { cert: fs.readFileSync(CERT_FILE), key: fs.readFileSync(KEY_FILE) };
  }
  console.log('Generating self-signed TLS certificate (one-time)...');
  const selfsigned = require('selfsigned');
  const pems = selfsigned.generate(
    [{ name: 'commonName', value: 'nyxx-local' }],
    { days: 3650, keySize: 2048, algorithm: 'sha256' }
  );
  fs.mkdirSync(CERT_DIR, { recursive: true });
  fs.writeFileSync(CERT_FILE, pems.cert);
  fs.writeFileSync(KEY_FILE, pems.private);
  return { cert: pems.cert, key: pems.private };
}

// ── Static file server ────────────────────────────────────────────────────

function serveStatic(req, res) {
  // Only handle GET/HEAD
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405); res.end(); return;
  }

  const url = req.url === '/' ? '/index.html' : req.url.split('?')[0];

  // Certificate trust helper endpoint
  if (url === '/auth') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`
      <!DOCTYPE html>
      <html lang="en">
      <head>
        <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Nyxx - Cert Trusted</title>
        <style>
          body { background: #0a0a0a; color: #4ade80; font-family: system-ui, sans-serif; 
                 display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; text-align: center; padding: 20px; }
          h1 { margin-bottom: 8px; }
          p { color: #aaa; }
        </style>
      </head>
      <body>
        <h1>✅ Certificate Trusted!</h1>
        <p>You may now close this tab and return to the controller.</p>
        <script>setTimeout(() => window.close(), 3000);</script>
      </body>
      </html>
    `);
    return;
  }

  const filePath = path.normalize(path.join(PUBLIC_DIR, url));

  // Security: prevent path traversal outside public/
  if (!filePath.startsWith(PUBLIC_DIR + path.sep) && filePath !== PUBLIC_DIR) {
    res.writeHead(403); res.end(); return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) { res.writeHead(404); res.end('Not found'); return; }
    const mime = MIME[path.extname(filePath)] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': mime, 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

// ── Bridge server ──────────────────────────────────────────────────────────

const { cert, key } = ensureCert();
const httpServer = https.createServer({ cert, key }, serveStatic);
const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  const clientAddr = req.socket.remoteAddress;
  console.log(`[+] Browser connected: ${clientAddr}`);

  // Dedicated UDP socket per browser client
  const udp = dgram.createSocket('udp4');
  udp.bind();   // OS picks a random ephemeral port

  // UDP → WebSocket  (relay server replies back to browser)
  udp.on('message', (msg) => {
    if (ws.readyState !== ws.OPEN) return;
    const text = msg.toString('ascii');
    // Forward known text commands; silently drop anything unexpected
    if (text.startsWith('PONG:') || text.startsWith('RUMBLE:') ||
        text === 'FULL' || text === 'DISCONNECT') {
      ws.send(text);
    }
  });

  udp.on('error', (err) => console.error('UDP error:', err.message));

  // WebSocket → UDP  (relay browser data to C# server)
  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      // Binary gamepad state packet — forward verbatim
      udp.send(Buffer.from(data), GAMEPAD_PORT, GAMEPAD_HOST);
    } else {
      // Text command (only "PING" expected)
      const text = data.toString().trim();
      if (text === 'PING') udp.send(Buffer.from('PING'), GAMEPAD_PORT, GAMEPAD_HOST);
    }
  });

  // Cleanup on browser disconnect
  ws.on('close', () => {
    console.log(`[-] Browser disconnected: ${clientAddr}`);
    udp.send(Buffer.from('DISCONNECT'), GAMEPAD_PORT, GAMEPAD_HOST, () => {
      try { udp.close(); } catch {}
    });
  });

  ws.on('error', () => {
    try { udp.close(); } catch {}
  });
});

// ── Start & print QR ──────────────────────────────────────────────────────

httpServer.listen(BRIDGE_PORT, '0.0.0.0', () => {
  const ip  = getLocalIP();
  const url = `https://${ip}:${BRIDGE_PORT}`;

  console.log('\n╔══════════════════════════════════════════╗');
  console.log( '║        Nyxx Web Controller Bridge        ║');
  console.log( '╠══════════════════════════════════════════╣');
  console.log(`║  URL: ${url.padEnd(35)}║`);
  console.log( '╠══════════════════════════════════════════╣');
  console.log( '║  First launch: Open URL in phone browser ║');
  console.log( '║  → tap Advanced → Proceed (cert warning) ║');
  console.log( '║  This is only needed ONCE per device.    ║');
  console.log( '╚══════════════════════════════════════════╝\n');

  try {
    require('qrcode-terminal').generate(url, { small: true });
  } catch {}
});
