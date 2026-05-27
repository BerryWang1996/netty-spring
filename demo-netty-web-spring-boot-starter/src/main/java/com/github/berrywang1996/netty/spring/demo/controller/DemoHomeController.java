package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;

@Controller
public class DemoHomeController {

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
                    <a class="card" href="/ws/sendWebsocketMessage?message=hello">
                        <span class="eyebrow">WebSocket</span>
                        <h2>Broadcast text</h2>
                        <p>Open a WebSocket client on /ws/test first, then use this endpoint to broadcast text.</p>
                        <code>/ws/test?room=demo</code>
                    </a>
                    <a class="card" href="/ws/sendWebsocketJson?message=hello-json">
                        <span class="eyebrow">JSON</span>
                        <h2>Broadcast JSON</h2>
                        <p>Send a JSON payload through the MessageSender convenience API.</p>
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
                    <a class="card" href="http://localhost:8081/actuator/metrics">
                        <span class="eyebrow">Micrometer</span>
                        <h2>Actuator metrics</h2>
                        <p>Netty runtime counters are bridged to Micrometer when spring-boot-starter-actuator is present.</p>
                        <code>:8081/actuator/metrics</code>
                    </a>
                    <a class="card" href="#">
                        <span class="eyebrow">Auth</span>
                        <h2>Token interceptor</h2>
                        <p>Run with --spring.profiles.active=auth-demo. Connect with ?token=demo-token-2026.</p>
                        <code>auth-demo profile</code>
                    </a>
                </div>
            </main>
            </body>
            </html>
            """;

    @RequestMapping("/")
    public String home() {
        return HOME_HTML;
    }
}
