/**
 * WebSocket Chat (IM) Stress Test
 *
 * Simulates realistic IM chat load: N users connected,
 * each sending messages at a configurable rate.
 * Measures throughput, latency, and message delivery reliability.
 *
 * Usage:
 *   node ws-chat-test.js [options]
 *
 * Options:
 *   --host=<host>          Server host (default: localhost)
 *   --port=<port>          Server port (default: 8080)
 *   --users=<count>        Number of concurrent chat users (default: 1000)
 *   --batch=<size>         Connection batch size (default: 50)
 *   --msgrate=<ms>         Message send interval per user in ms (default: 2000)
 *   --duration=<seconds>   Test duration after all users connected (default: 60)
 *   --pm-ratio=<0-1>       Ratio of private messages vs broadcast (default: 0.3)
 */

const WebSocket = require('ws');

// Parse CLI arguments
const args = {};
process.argv.slice(2).forEach(arg => {
    const [key, val] = arg.replace(/^--/, '').split('=');
    args[key] = val;
});

const HOST = args.host || 'localhost';
const PORT = parseInt(args.port || '8080');
const USER_COUNT = parseInt(args.users || '1000');
const BATCH_SIZE = parseInt(args.batch || '50');
const MSG_INTERVAL = parseInt(args.msgrate || '2000');
const TEST_DURATION = parseInt(args.duration || '60');
const PM_RATIO = parseFloat(args['pm-ratio'] || '0.3');

const WS_URL = `ws://${HOST}:${PORT}/ws/chat`;

// Stats
let connected = 0;
let connectionsFailed = 0;
let messagesSent = 0;
let messagesReceived = 0;
let broadcastsSent = 0;
let pmsSent = 0;
let messageErrors = 0;
let latencies = [];
const connections = [];
const userNicknames = [];
let startTime;
let testPhase = 'connecting';

// Latency tracking - embed timestamp in message text
const pendingMessages = new Map(); // messageId -> sendTimestamp

function generateMessageId() {
    return `${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function formatMemory() {
    const usage = process.memoryUsage();
    return `RSS=${(usage.rss / 1024 / 1024).toFixed(1)}MB`;
}

function percentile(arr, p) {
    if (arr.length === 0) return 0;
    const sorted = arr.slice().sort((a, b) => a - b);
    const idx = Math.ceil(sorted.length * p / 100) - 1;
    return sorted[Math.max(0, idx)];
}

function printStats(label) {
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    const sendRate = testPhase === 'testing'
        ? (messagesSent / Math.max(1, (Date.now() - startTime) / 1000)).toFixed(0)
        : '0';
    const recvRate = testPhase === 'testing'
        ? (messagesReceived / Math.max(1, (Date.now() - startTime) / 1000)).toFixed(0)
        : '0';

    let latencyStr = 'N/A';
    if (latencies.length > 0) {
        const p50 = percentile(latencies, 50);
        const p95 = percentile(latencies, 95);
        const p99 = percentile(latencies, 99);
        latencyStr = `p50=${p50}ms p95=${p95}ms p99=${p99}ms`;
    }

    console.log(
        `[${elapsed}s] ${label} | ` +
        `Users: ${connected}/${USER_COUNT} | ` +
        `Sent: ${messagesSent} (${sendRate}/s) | Recv: ${messagesReceived} (${recvRate}/s) | ` +
        `Errors: ${messageErrors} | Latency: ${latencyStr} | ${formatMemory()}`
    );
}

function createChatUser(id) {
    return new Promise((resolve) => {
        const nickname = `stress-user-${id}`;
        try {
            const ws = new WebSocket(`${WS_URL}?nickname=${nickname}`, {
                handshakeTimeout: 15000,
                perMessageDeflate: false,
            });

            const timeout = setTimeout(() => {
                ws.terminate();
                connectionsFailed++;
                resolve(null);
            }, 20000);

            ws.on('open', () => {
                clearTimeout(timeout);
                connected++;
                connections.push(ws);
                userNicknames.push(nickname);
                ws._nickname = nickname;
                ws._userId = id;
                resolve(ws);
            });

            ws.on('message', (data) => {
                messagesReceived++;
                try {
                    const msg = JSON.parse(data.toString());
                    // Check if this is a message we sent (for latency tracking)
                    if (msg.text && msg.text.startsWith('__ST:')) {
                        const msgId = msg.text.split(':')[1];
                        const sendTime = pendingMessages.get(msgId);
                        if (sendTime) {
                            const latency = Date.now() - sendTime;
                            latencies.push(latency);
                            pendingMessages.delete(msgId);
                            // Keep latencies array manageable
                            if (latencies.length > 100000) {
                                latencies = latencies.slice(-50000);
                            }
                        }
                    }
                } catch (e) {
                    // Non-JSON message or parse error, ignore
                }
            });

            ws.on('error', (err) => {
                clearTimeout(timeout);
                messageErrors++;
            });

            ws.on('close', () => {
                clearTimeout(timeout);
                const idx = connections.indexOf(ws);
                if (idx >= 0) {
                    connections.splice(idx, 1);
                    connected--;
                }
            });

        } catch (err) {
            connectionsFailed++;
            resolve(null);
        }
    });
}

async function connectAllUsers() {
    console.log(`Connecting ${USER_COUNT} users in batches of ${BATCH_SIZE}...`);

    for (let batch = 0; batch < Math.ceil(USER_COUNT / BATCH_SIZE); batch++) {
        const startId = batch * BATCH_SIZE;
        const count = Math.min(BATCH_SIZE, USER_COUNT - startId);

        const promises = [];
        for (let i = 0; i < count; i++) {
            promises.push(createChatUser(startId + i));
        }
        await Promise.all(promises);

        if ((batch + 1) % 10 === 0) {
            printStats(`Connecting`);
        }

        await sleep(100); // Give server time between batches
    }

    console.log(`\nConnected ${connected} of ${USER_COUNT} users (${connectionsFailed} failed)\n`);
}

function sendRandomMessage(ws) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;

    const msgId = generateMessageId();
    const sendTime = Date.now();

    try {
        if (Math.random() < PM_RATIO && userNicknames.length > 1) {
            // Send private message to random user
            const targetIdx = Math.floor(Math.random() * userNicknames.length);
            const target = userNicknames[targetIdx];
            if (target !== ws._nickname) {
                ws.send(JSON.stringify({
                    type: 'private',
                    target: target,
                    text: `__ST:${msgId}:PM from ${ws._nickname}`
                }));
                pmsSent++;
            } else {
                // Fallback to broadcast
                ws.send(JSON.stringify({
                    type: 'message',
                    text: `__ST:${msgId}:Hello from ${ws._nickname}`
                }));
                broadcastsSent++;
            }
        } else {
            // Send broadcast message
            ws.send(JSON.stringify({
                type: 'message',
                text: `__ST:${msgId}:Hello from ${ws._nickname}`
            }));
            broadcastsSent++;
        }

        pendingMessages.set(msgId, sendTime);
        messagesSent++;

        // Clean up old pending messages (> 30s)
        if (pendingMessages.size > 10000) {
            const cutoff = Date.now() - 30000;
            for (const [id, time] of pendingMessages) {
                if (time < cutoff) pendingMessages.delete(id);
            }
        }

    } catch (err) {
        messageErrors++;
    }
}

async function runChatLoadTest() {
    console.log('==========================================================');
    console.log('  WebSocket Chat (IM) Stress Test');
    console.log('==========================================================');
    console.log(`  Target: ${WS_URL}`);
    console.log(`  Users: ${USER_COUNT}`);
    console.log(`  Message Interval: ${MSG_INTERVAL}ms per user`);
    console.log(`  Test Duration: ${TEST_DURATION}s`);
    console.log(`  PM Ratio: ${(PM_RATIO * 100).toFixed(0)}%`);
    console.log(`  Expected Send Rate: ~${(USER_COUNT * 1000 / MSG_INTERVAL).toFixed(0)} msg/s`);
    console.log('==========================================================\n');

    startTime = Date.now();

    // Phase 1: Connect all users
    testPhase = 'connecting';
    await connectAllUsers();

    if (connected === 0) {
        console.log('No connections established. Is the server running?');
        process.exit(1);
    }

    // Phase 2: Start sending messages
    testPhase = 'testing';
    startTime = Date.now(); // Reset for accurate rate calculation
    messagesSent = 0;
    messagesReceived = 0;
    latencies = [];

    console.log(`Starting message load for ${TEST_DURATION}s...\n`);

    // Each user sends a message at its own interval with jitter
    const intervals = [];
    for (const ws of [...connections]) {
        const jitter = Math.random() * MSG_INTERVAL; // Spread initial sends
        const interval = setInterval(() => {
            sendRandomMessage(ws);
        }, MSG_INTERVAL);

        // Initial jittered send
        setTimeout(() => sendRandomMessage(ws), jitter);
        intervals.push(interval);
    }

    // Progress reporting
    const statsInterval = setInterval(() => {
        printStats('Running');
    }, 5000);

    // Wait for test duration
    await sleep(TEST_DURATION * 1000);

    // Cleanup
    clearInterval(statsInterval);
    for (const interval of intervals) {
        clearInterval(interval);
    }

    printStats('FINAL');

    // Wait a moment for in-flight messages
    await sleep(2000);

    // Calculate final stats
    const totalTime = (Date.now() - startTime) / 1000;
    const avgSendRate = (messagesSent / totalTime).toFixed(1);
    const avgRecvRate = (messagesReceived / totalTime).toFixed(1);

    // Close all connections
    console.log(`\nClosing ${connections.length} connections...`);
    for (const ws of [...connections]) {
        try { ws.close(1000); } catch (e) {}
    }
    await sleep(3000);
    for (const ws of [...connections]) {
        try { ws.terminate(); } catch (e) {}
    }

    console.log('\n==========================================================');
    console.log('  CHAT STRESS TEST RESULTS');
    console.log('==========================================================');
    console.log(`  Connected Users:        ${connected} (of ${USER_COUNT} attempted)`);
    console.log(`  Connection Failures:    ${connectionsFailed}`);
    console.log(`  Test Duration:          ${totalTime.toFixed(1)}s`);
    console.log(`  Messages Sent:          ${messagesSent} (broadcasts=${broadcastsSent}, PMs=${pmsSent})`);
    console.log(`  Messages Received:      ${messagesReceived}`);
    console.log(`  Send Rate:              ${avgSendRate} msg/s`);
    console.log(`  Receive Rate:           ${avgRecvRate} msg/s`);
    console.log(`  Send Errors:            ${messageErrors}`);
    if (latencies.length > 0) {
        console.log(`  Latency (p50):          ${percentile(latencies, 50)}ms`);
        console.log(`  Latency (p95):          ${percentile(latencies, 95)}ms`);
        console.log(`  Latency (p99):          ${percentile(latencies, 99)}ms`);
        console.log(`  Latency (max):          ${Math.max(...latencies)}ms`);
        console.log(`  Latency Samples:        ${latencies.length}`);
    }
    console.log(`  Client Memory:          ${formatMemory()}`);
    console.log('==========================================================');

    process.exit(0);
}

// Handle graceful shutdown
process.on('SIGINT', () => {
    console.log('\n\nInterrupted! Cleaning up...');
    printStats('INTERRUPTED');
    for (const ws of connections) {
        try { ws.terminate(); } catch (e) {}
    }
    process.exit(1);
});

runChatLoadTest().catch(err => {
    console.error('Test failed:', err);
    process.exit(1);
});
