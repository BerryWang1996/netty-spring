package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;

/**
 * Demo home page controller that renders an HTML dashboard linking to all demo endpoints.
 * <p>
 * The landing page at {@code /} provides quick-access cards for each feature area:
 * HTTP MVC, WebSocket text/JSON messaging, AES-GCM crypto, health/status endpoints,
 * Micrometer metrics, token-based auth, and the chat room demo.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Controller
public class DemoHomeController {

    /** Inline HTML template for the demo dashboard landing page. */
    private static final String HOME_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>netty-spring demo</title>
                <style>
                    :root {
                        color-scheme: light;
                        --ink: #14213d;
                        --muted: #64748b;
                        --paper: rgba(255, 255, 255, .86);
                        --line: #d8e2ef;
                        --accent: #b45309;
                        --accent-soft: #fef3c7;
                    }
                    * { box-sizing: border-box; }
                    body {
                        margin: 0;
                        min-height: 100vh;
                        color: var(--ink);
                        font-family: ui-serif, Georgia, Cambria, "Times New Roman", serif;
                        background:
                            radial-gradient(circle at 12% 12%, #fde68a 0, transparent 28%),
                            radial-gradient(circle at 86% 18%, #bae6fd 0, transparent 30%),
                            linear-gradient(135deg, #fff7ed 0%, #f8fafc 52%, #ecfeff 100%);
                    }
                    main {
                        width: min(1120px, calc(100% - 32px));
                        margin: 0 auto;
                        padding: 48px 0;
                    }
                    h1 {
                        margin: 0;
                        max-width: 880px;
                        font-size: clamp(2.4rem, 6vw, 5.2rem);
                        line-height: .92;
                        letter-spacing: -.055em;
                    }
                    .lead {
                        max-width: 760px;
                        color: var(--muted);
                        font: 500 1.08rem/1.7 ui-sans-serif, system-ui, sans-serif;
                    }
                    .grid {
                        display: grid;
                        grid-template-columns: repeat(3, minmax(0, 1fr));
                        gap: 18px;
                        margin-top: 30px;
                    }
                    a.card {
                        min-height: 180px;
                        border: 1px solid var(--line);
                        border-radius: 28px;
                        padding: 24px;
                        color: inherit;
                        background: var(--paper);
                        text-decoration: none;
                        box-shadow: 0 20px 60px rgba(15, 23, 42, .08);
                        transition: transform .18s ease, box-shadow .18s ease;
                    }
                    a.card:hover {
                        transform: translateY(-4px);
                        box-shadow: 0 28px 70px rgba(15, 23, 42, .13);
                    }
                    section.sandbox {
                        margin-top: 32px;
                        padding: 22px 26px;
                        border: 1px solid var(--line);
                        border-radius: 28px;
                        background: var(--paper);
                        box-shadow: 0 20px 60px rgba(15, 23, 42, .08);
                    }
                    section.sandbox h2 {
                        margin: 0 0 4px;
                        font-size: 1.35rem;
                    }
                    section.sandbox .row {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        flex-wrap: wrap;
                        margin-top: 10px;
                    }
                    section.sandbox button {
                        border: 1px solid var(--line);
                        border-radius: 999px;
                        padding: 8px 14px;
                        background: var(--accent-soft);
                        color: #78350f;
                        font: 700 .82rem/1 ui-sans-serif, system-ui, sans-serif;
                        cursor: pointer;
                        transition: transform .12s ease;
                    }
                    section.sandbox button:disabled {
                        opacity: .45;
                        cursor: not-allowed;
                    }
                    section.sandbox button:hover:not(:disabled) {
                        transform: translateY(-1px);
                    }
                    .ws-state {
                        font: 600 .85rem/1 ui-monospace, SFMono-Regular, Consolas, monospace;
                        color: var(--muted);
                    }
                    .ws-state .dot {
                        display: inline-block;
                        width: 10px;
                        height: 10px;
                        border-radius: 50%;
                        background: #94a3b8;
                        margin-right: 6px;
                        vertical-align: middle;
                    }
                    .ws-state .dot.on { background: #16a34a; }
                    .ws-state .dot.off { background: #ef4444; }
                    .ws-log {
                        margin-top: 14px;
                        padding: 14px 16px;
                        max-height: 260px;
                        overflow-y: auto;
                        background: #0f172a;
                        color: #e2e8f0;
                        border-radius: 16px;
                        font: 500 .82rem/1.55 ui-monospace, SFMono-Regular, Consolas, monospace;
                    }
                    .ws-log .line { white-space: pre-wrap; word-break: break-word; }
                    .ws-log .line.send { color: #fbbf24; }
                    .ws-log .line.recv { color: #86efac; }
                    .ws-log .line.sys { color: #94a3b8; font-style: italic; }
                    .ws-log .ts {
                        color: #64748b;
                        margin-right: 8px;
                    }
                    .eyebrow {
                        color: var(--accent);
                        font: 800 .76rem/1 ui-sans-serif, system-ui, sans-serif;
                        letter-spacing: .12em;
                        text-transform: uppercase;
                    }
                    h2 {
                        margin: 14px 0 8px;
                        font-size: 1.35rem;
                    }
                    p {
                        margin: 0;
                        color: var(--muted);
                        font: 500 .96rem/1.6 ui-sans-serif, system-ui, sans-serif;
                    }
                    code {
                        display: inline-block;
                        margin-top: 12px;
                        border-radius: 999px;
                        padding: 7px 10px;
                        color: #78350f;
                        background: var(--accent-soft);
                        font: 700 .78rem/1 ui-monospace, SFMono-Regular, Consolas, monospace;
                    }
                    @media (max-width: 900px) {
                        .grid { grid-template-columns: 1fr; }
                        main { padding: 28px 0; }
                    }
                </style>
            </head>
            <body>
            <main>
                <h1>netty-spring demo cockpit</h1>
                <p class="lead">
                    Start here when trying the starter locally. These links cover the shortest path through HTTP,
                    WebSocket text/JSON messaging, crypto envelope testing, and the built-in runtime status endpoint.
                </p>
                <div class="grid">
                    <a class="card" href="/http/get">
                        <span class="eyebrow">HTTP MVC</span>
                        <h2>Cookie echo</h2>
                        <p>Exercise the MVC mapping path and JSON response rendering.</p>
                        <code>GET /http/get</code>
                    </a>
                    <a class="card" data-broadcast="text" href="/ws/sendWebsocketMessage?message=hello">
                        <span class="eyebrow">WebSocket</span>
                        <h2>Broadcast text</h2>
                        <p>Triggers a server-side broadcast to /ws/test. The sandbox below auto-connects and shows the frame.</p>
                        <code>/ws/test?room=demo</code>
                    </a>
                    <a class="card" data-broadcast="json" href="/ws/sendWebsocketJson?message=hello-json">
                        <span class="eyebrow">JSON</span>
                        <h2>Broadcast JSON</h2>
                        <p>Send a JSON payload through the MessageSender convenience API; arrives in the sandbox below.</p>
                        <code>/ws/json</code>
                    </a>
                    <a class="card" href="/ws/crypto-demo">
                        <span class="eyebrow">Crypto</span>
                        <h2>AES-GCM demo</h2>
                        <p>Use the crypto-demo profile, then verify encrypted browser frames and plaintext handlers.</p>
                        <code>--spring.profiles.active=crypto-demo</code>
                    </a>
                    <a class="card" href="/netty/health">
                        <span class="eyebrow">Health</span>
                        <h2>Health endpoint</h2>
                        <p>Enable server.netty.management.enable=true to expose this lightweight endpoint.</p>
                        <code>GET /netty/health</code>
                    </a>
                    <a class="card" href="/netty/status">
                        <span class="eyebrow">Runtime</span>
                        <h2>Status snapshot</h2>
                        <p>Read handler, HTTP, WebSocket runtime counters and event counters while debugging.</p>
                        <code>GET /netty/status</code>
                    </a>
                    <a class="card" href="/netty/status">
                        <span class="eyebrow">Micrometer</span>
                        <h2>Runtime metrics</h2>
                        <p>Netty runtime counters and event counters; the same data Micrometer bridges to MeterRegistry when actuator HTTP endpoints are reachable.</p>
                        <code>GET /netty/status</code>
                    </a>
                    <a class="card" href="#">
                        <span class="eyebrow">Auth</span>
                        <h2>Token interceptor</h2>
                        <p>Run with --spring.profiles.active=auth-demo. Connect with ?token=demo-token-2026.</p>
                        <code>auth-demo profile</code>
                    </a>
                    <a class="card" href="/chat">
                        <span class="eyebrow">Chat Room</span>
                        <h2>Multi-user chat</h2>
                        <p>Join with a nickname, broadcast messages, and send private messages via /pm command.</p>
                        <code>/ws/chat</code>
                    </a>
                </div>

                <section class="sandbox" id="sandbox">
                    <span class="eyebrow">Live</span>
                    <h2>WebSocket sandbox — /ws/test</h2>
                    <p>Auto-connects when this page loads. Clicking the Broadcast cards above triggers the server-side
                       MessageSender, and any frame delivered to <code>/ws/test</code> shows up here in real time.</p>
                    <div class="row">
                        <span class="ws-state"><span class="dot" id="wsDot"></span><span id="wsState">connecting…</span></span>
                        <button id="reconnectBtn" type="button">Reconnect</button>
                        <button id="broadcastTextBtn" type="button">Broadcast text</button>
                        <button id="broadcastJsonBtn" type="button">Broadcast JSON</button>
                        <button id="clearLogBtn" type="button">Clear log</button>
                    </div>
                    <div class="ws-log" id="wsLog"></div>
                </section>
            </main>
            <script>
              (function () {
                const wsDot = document.getElementById('wsDot');
                const wsState = document.getElementById('wsState');
                const wsLog = document.getElementById('wsLog');
                const reconnectBtn = document.getElementById('reconnectBtn');
                const broadcastTextBtn = document.getElementById('broadcastTextBtn');
                const broadcastJsonBtn = document.getElementById('broadcastJsonBtn');
                const clearLogBtn = document.getElementById('clearLogBtn');
                let socket = null;

                function ts() {
                  const d = new Date();
                  return d.toTimeString().slice(0, 8);
                }
                function logLine(kind, text) {
                  const div = document.createElement('div');
                  div.className = 'line ' + kind;
                  div.innerHTML = '<span class="ts">' + ts() + '</span>' + text;
                  wsLog.appendChild(div);
                  wsLog.scrollTop = wsLog.scrollHeight;
                }
                function setState(s, on) {
                  wsState.textContent = s;
                  wsDot.className = 'dot' + (on === true ? ' on' : on === false ? ' off' : '');
                }
                function connect() {
                  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
                    return;
                  }
                  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                  const url = proto + '//' + location.host + '/ws/test?room=demo';
                  setState('connecting…');
                  logLine('sys', 'opening ' + url);
                  socket = new WebSocket(url);
                  socket.onopen = () => { setState('connected', true); logLine('sys', 'open'); };
                  socket.onmessage = e => {
                    let display = e.data;
                    try { display = JSON.stringify(JSON.parse(e.data)); } catch (_) {}
                    logLine('recv', '◀ ' + display);
                  };
                  socket.onclose = e => { setState('closed', false); logLine('sys', 'close code=' + e.code); };
                  socket.onerror = () => { setState('error', false); logLine('sys', 'error'); };
                }
                async function broadcast(kind) {
                  const url = kind === 'json'
                    ? '/ws/sendWebsocketJson?message=hello-' + Date.now()
                    : '/ws/sendWebsocketMessage?message=hello-' + Date.now();
                  logLine('send', '▶ GET ' + url);
                  try {
                    const r = await fetch(url, { method: 'GET' });
                    const body = await r.text();
                    logLine('sys', 'server responded ' + r.status + ' ' + body.trim());
                  } catch (err) {
                    logLine('sys', 'fetch failed: ' + err.message);
                  }
                }

                reconnectBtn.addEventListener('click', () => { if (socket) socket.close(); connect(); });
                broadcastTextBtn.addEventListener('click', () => broadcast('text'));
                broadcastJsonBtn.addEventListener('click', () => broadcast('json'));
                clearLogBtn.addEventListener('click', () => { wsLog.innerHTML = ''; });

                // intercept the two "Broadcast" cards above so they trigger inline broadcast
                // instead of navigating away to a {"success"} blank page
                document.querySelectorAll('a.card[data-broadcast]').forEach(card => {
                  card.addEventListener('click', e => {
                    e.preventDefault();
                    document.getElementById('sandbox').scrollIntoView({ behavior: 'smooth', block: 'start' });
                    broadcast(card.dataset.broadcast);
                  });
                });

                connect();
              })();
            </script>
            </body>
            </html>
            """;

    /**
     * Serves the demo home page HTML at the root URL.
     *
     * @return the inline HTML string for the demo dashboard
     */
    @RequestMapping("/")
    public String home() {
        return HOME_HTML;
    }
}
