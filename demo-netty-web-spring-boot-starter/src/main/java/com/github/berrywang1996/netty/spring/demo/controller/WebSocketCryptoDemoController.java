package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequestMapping("/ws")
public class WebSocketCryptoDemoController {

    private static final String CRYPTO_DEMO_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>netty-spring websocket crypto demo</title>
                <style>
                    :root {
                        color-scheme: light;
                        --ink: #18212f;
                        --muted: #64748b;
                        --line: #d8e2ef;
                        --paper: rgba(255, 255, 255, .82);
                        --accent: #0f766e;
                        --accent-strong: #134e4a;
                    }
                    * { box-sizing: border-box; }
                    body {
                        margin: 0;
                        min-height: 100vh;
                        color: var(--ink);
                        font-family: ui-serif, Georgia, Cambria, "Times New Roman", serif;
                        background:
                            radial-gradient(circle at 10% 10%, #ccfbf1 0, transparent 30%),
                            radial-gradient(circle at 90% 20%, #fee2e2 0, transparent 28%),
                            linear-gradient(135deg, #f8fafc 0%, #f1f5f9 45%, #ecfeff 100%);
                    }
                    main {
                        width: min(1120px, calc(100% - 32px));
                        margin: 0 auto;
                        padding: 48px 0;
                    }
                    h1 {
                        margin: 0 0 12px;
                        font-size: clamp(2.1rem, 5vw, 4.5rem);
                        line-height: .95;
                        letter-spacing: -.055em;
                    }
                    .lead {
                        max-width: 760px;
                        color: var(--muted);
                        font-size: 1.08rem;
                        line-height: 1.7;
                    }
                    .grid {
                        display: grid;
                        grid-template-columns: minmax(0, 420px) minmax(0, 1fr);
                        gap: 22px;
                        margin-top: 28px;
                    }
                    section {
                        border: 1px solid var(--line);
                        border-radius: 28px;
                        background: var(--paper);
                        box-shadow: 0 20px 60px rgba(15, 23, 42, .08);
                        padding: 24px;
                    }
                    label {
                        display: block;
                        margin-top: 16px;
                        color: var(--muted);
                        font: 700 .74rem/1.2 ui-sans-serif, system-ui, sans-serif;
                        letter-spacing: .12em;
                        text-transform: uppercase;
                    }
                    input, textarea, select {
                        width: 100%;
                        margin-top: 8px;
                        border: 1px solid var(--line);
                        border-radius: 16px;
                        padding: 12px 14px;
                        color: var(--ink);
                        background: rgba(255, 255, 255, .72);
                        font: 500 .98rem/1.45 ui-sans-serif, system-ui, sans-serif;
                    }
                    textarea {
                        min-height: 112px;
                        resize: vertical;
                    }
                    button {
                        border: 0;
                        border-radius: 999px;
                        padding: 12px 18px;
                        color: white;
                        background: var(--accent);
                        font: 800 .82rem/1 ui-sans-serif, system-ui, sans-serif;
                        letter-spacing: .08em;
                        text-transform: uppercase;
                        cursor: pointer;
                    }
                    button.secondary {
                        color: var(--accent-strong);
                        background: #ccfbf1;
                    }
                    .actions {
                        display: flex;
                        flex-wrap: wrap;
                        gap: 10px;
                        margin-top: 18px;
                    }
                    .hint {
                        margin-top: 18px;
                        color: var(--muted);
                        font: 500 .9rem/1.6 ui-sans-serif, system-ui, sans-serif;
                    }
                    pre {
                        min-height: 460px;
                        max-height: 72vh;
                        overflow: auto;
                        margin: 0;
                        border-radius: 22px;
                        padding: 18px;
                        color: #d1fae5;
                        background: #0f172a;
                        font: 500 .86rem/1.55 ui-monospace, SFMono-Regular, Consolas, monospace;
                        white-space: pre-wrap;
                    }
                    @media (max-width: 860px) {
                        .grid { grid-template-columns: 1fr; }
                        main { padding: 28px 0; }
                    }
                </style>
            </head>
            <body>
            <main>
                <h1>WebSocket AES-GCM demo</h1>
                <p class="lead">
                    Enable the commented server.netty.websocket.crypto.* properties in the demo application,
                    open this page, then send a message. Browser DevTools will show an AES-GCM JSON envelope
                    while the server handler receives the decrypted plaintext.
                </p>
                <div class="grid">
                    <section>
                        <label for="endpoint">WebSocket endpoint</label>
                        <input id="endpoint" spellcheck="false">
                        <label for="keyId">Key id</label>
                        <select id="keyId">
                            <option value="demo-2026-05">demo-2026-05</option>
                            <option value="demo-2026-04">demo-2026-04</option>
                        </select>
                        <label for="message">Plain message</label>
                        <textarea id="message" spellcheck="false">hello crypto websocket</textarea>
                        <div class="actions">
                            <button id="connect">Connect</button>
                            <button id="send" class="secondary">Encrypt and send</button>
                            <button id="close" class="secondary">Close</button>
                        </div>
                        <p class="hint">
                            The demo keys are intentionally hard-coded toy keys to mirror the demoProvider bean.
                            Production clients must fetch and protect keys through an application-specific design.
                        </p>
                    </section>
                    <section>
                        <pre id="log"></pre>
                    </section>
                </div>
            </main>
            <script>
            const keys = {
                "demo-2026-04": "abcdef0123456789",
                "demo-2026-05": "9876543210fedcba"
            };
            const encoder = new TextEncoder();
            const decoder = new TextDecoder();
            let socket;

            const endpoint = document.getElementById("endpoint");
            const keyId = document.getElementById("keyId");
            const message = document.getElementById("message");
            const logEl = document.getElementById("log");

            endpoint.value = defaultEndpoint();

            document.getElementById("connect").addEventListener("click", connect);
            document.getElementById("send").addEventListener("click", sendEncryptedText);
            document.getElementById("close").addEventListener("click", () => {
                if (socket) {
                    socket.close(1000, "demo closed");
                }
            });

            function defaultEndpoint() {
                const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
                return `${protocol}//${window.location.host}/ws/test?room=crypto-demo`;
            }

            function connect() {
                if (socket && socket.readyState <= WebSocket.OPEN) {
                    log("already connected");
                    return;
                }
                socket = new WebSocket(endpoint.value);
                socket.addEventListener("open", () => log(`connected: ${endpoint.value}`));
                socket.addEventListener("close", event => log(`closed: code=${event.code}, reason=${event.reason}`));
                socket.addEventListener("error", () => log("websocket error"));
                socket.addEventListener("message", async event => {
                    log(`raw inbound frame: ${event.data}`);
                    try {
                        const envelope = JSON.parse(event.data);
                        if (envelope.alg === "AES-GCM") {
                            log(`decrypted inbound text: ${await decryptText(envelope)}`);
                        }
                    } catch (error) {
                        log(`inbound plaintext or non-json frame: ${event.data}`);
                    }
                });
            }

            async function sendEncryptedText() {
                if (!socket || socket.readyState !== WebSocket.OPEN) {
                    log("connect before sending");
                    return;
                }
                const envelope = await encryptText(message.value);
                const frame = JSON.stringify(envelope);
                socket.send(frame);
                log(`sent plaintext: ${message.value}`);
                log(`sent encrypted frame: ${frame}`);
            }

            async function encryptText(plainText) {
                const kid = keyId.value;
                const iv = crypto.getRandomValues(new Uint8Array(12));
                const cryptoKey = await importAesKey(kid, ["encrypt"]);
                const ciphertext = await crypto.subtle.encrypt({
                    name: "AES-GCM",
                    iv,
                    additionalData: aad(kid, "text"),
                    tagLength: 128
                }, cryptoKey, encoder.encode(plainText));
                return {
                    alg: "AES-GCM",
                    kid,
                    typ: "text",
                    iv: toBase64(iv),
                    ciphertext: toBase64(new Uint8Array(ciphertext))
                };
            }

            async function decryptText(envelope) {
                const cryptoKey = await importAesKey(envelope.kid, ["decrypt"]);
                const plaintext = await crypto.subtle.decrypt({
                    name: "AES-GCM",
                    iv: fromBase64(envelope.iv),
                    additionalData: aad(envelope.kid, envelope.typ),
                    tagLength: 128
                }, cryptoKey, fromBase64(envelope.ciphertext));
                return decoder.decode(plaintext);
            }

            function aad(kid, type) {
                return encoder.encode(`AES-GCM|${kid}|${type}`);
            }

            async function importAesKey(kid, usages) {
                const raw = keys[kid];
                if (!raw) {
                    throw new Error(`Unknown demo key id: ${kid}`);
                }
                return crypto.subtle.importKey("raw", encoder.encode(raw), "AES-GCM", false, usages);
            }

            function toBase64(bytes) {
                let binary = "";
                for (let i = 0; i < bytes.length; i += 1) {
                    binary += String.fromCharCode(bytes[i]);
                }
                return btoa(binary);
            }

            function fromBase64(value) {
                const binary = atob(value);
                const bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i += 1) {
                    bytes[i] = binary.charCodeAt(i);
                }
                return bytes;
            }

            function log(line) {
                logEl.textContent += `[${new Date().toLocaleTimeString()}] ${line}\\n`;
                logEl.scrollTop = logEl.scrollHeight;
            }
            </script>
            </body>
            </html>
            """;

    @RequestMapping("/crypto-demo")
    public String cryptoDemo() {
        return CRYPTO_DEMO_HTML;
    }
}
