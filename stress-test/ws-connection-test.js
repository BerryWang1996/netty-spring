/**
 * WebSocket Connection Stress Test
 *
 * Tests the maximum number of concurrent WebSocket connections
 * the netty-spring server can handle.
 *
 * Usage:
 *   node ws-connection-test.js [options]
 *
 * Options:
 *   --host=<host>          Server host (default: localhost)
 *   --port=<port>          Server port (default: 8080)
 *   --max=<connections>    Maximum connections to attempt (default: 50000)
 *   --batch=<size>         Connections per batch (default: 100)
 *   --delay=<ms>           Delay between batches in ms (default: 50)
 *   --keepalive=<seconds>  Hold connections open for N seconds after reaching max (default: 30)
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
const MAX_CONNECTIONS = parseInt(args.max || '50000');
const BATCH_SIZE = parseInt(args.batch || '100');
const BATCH_DELAY = parseInt(args.delay || '50');
const KEEPALIVE_SECONDS = parseInt(args.keepalive || '30');
const WS_PATH = args.path || '/ws/test';

const WS_URL = `ws://${HOST}:${PORT}${WS_PATH}`;

// Stats
let connected = 0;
let failed = 0;
let closed = 0;
let errors = 0;
const connections = [];
let peakConnections = 0;
let startTime = Date.now();
let reachedMax = false;
let firstFailureAt = 0;

// Memory tracking
const memorySnapshots = [];

function formatMemory() {
    const usage = process.memoryUsage();
    return `RSS=${(usage.rss / 1024 / 1024).toFixed(1)}MB Heap=${(usage.heapUsed / 1024 / 1024).toFixed(1)}/${(usage.heapTotal / 1024 / 1024).toFixed(1)}MB`;
}

function printStats(label) {
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    const rate = (connected / (Date.now() - startTime) * 1000).toFixed(0);
    console.log(
        `[${elapsed}s] ${label} | ` +
        `Connected: ${connected} | Failed: ${failed} | Closed: ${closed} | Errors: ${errors} | ` +
        `Peak: ${peakConnections} | Rate: ${rate}/s | ${formatMemory()}`
    );
}

function createConnection(id) {
    return new Promise((resolve) => {
        try {
            const ws = new WebSocket(WS_URL, {
                handshakeTimeout: 10000,
                perMessageDeflate: false,
            });

            const timeout = setTimeout(() => {
                ws.terminate();
                failed++;
                if (!firstFailureAt) firstFailureAt = connected;
                resolve(null);
            }, 15000);

            ws.on('open', () => {
                clearTimeout(timeout);
                connected++;
                if (connected > peakConnections) peakConnections = connected;
                connections.push(ws);
                resolve(ws);
            });

            ws.on('error', (err) => {
                clearTimeout(timeout);
                errors++;
                if (!firstFailureAt && connected > 0) firstFailureAt = connected;
                resolve(null);
            });

            ws.on('close', () => {
                clearTimeout(timeout);
                closed++;
                const idx = connections.indexOf(ws);
                if (idx >= 0) {
                    connections.splice(idx, 1);
                    connected--;
                }
            });

            // Silently consume incoming messages
            ws.on('message', () => {});

        } catch (err) {
            failed++;
            resolve(null);
        }
    });
}

async function createBatch(startId, count) {
    const promises = [];
    for (let i = 0; i < count; i++) {
        promises.push(createConnection(startId + i));
    }
    return Promise.all(promises);
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function runConnectionTest() {
    console.log('==========================================================');
    console.log('  WebSocket Connection Stress Test');
    console.log('==========================================================');
    console.log(`  Target: ${WS_URL}`);
    console.log(`  Max Connections: ${MAX_CONNECTIONS}`);
    console.log(`  Batch Size: ${BATCH_SIZE}`);
    console.log(`  Batch Delay: ${BATCH_DELAY}ms`);
    console.log(`  Keepalive: ${KEEPALIVE_SECONDS}s`);
    console.log('==========================================================\n');

    startTime = Date.now();
    let consecutiveFailures = 0;
    const MAX_CONSECUTIVE_FAILURES = 5; // Stop after 5 batches of all failures

    for (let batch = 0; batch < Math.ceil(MAX_CONNECTIONS / BATCH_SIZE); batch++) {
        const startId = batch * BATCH_SIZE;
        const count = Math.min(BATCH_SIZE, MAX_CONNECTIONS - startId);

        const results = await createBatch(startId, count);
        const batchSuccess = results.filter(r => r !== null).length;

        if (batchSuccess === 0) {
            consecutiveFailures++;
        } else {
            consecutiveFailures = 0;
        }

        if ((batch + 1) % 10 === 0 || consecutiveFailures > 0) {
            printStats(`Batch ${batch + 1}`);
        }

        // Stop if we hit consistent failures
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            console.log(`\n⚠ Stopped: ${MAX_CONSECUTIVE_FAILURES} consecutive batches failed.`);
            break;
        }

        if (connected >= MAX_CONNECTIONS) {
            reachedMax = true;
            break;
        }

        await sleep(BATCH_DELAY);
    }

    printStats('PEAK');

    // Hold connections for keepalive period
    console.log(`\nHolding ${connected} connections for ${KEEPALIVE_SECONDS}s to test stability...`);
    for (let i = 0; i < KEEPALIVE_SECONDS; i += 5) {
        await sleep(5000);
        printStats(`Hold +${i + 5}s`);
    }

    // Graceful cleanup
    console.log(`\nClosing ${connections.length} connections...`);
    const closeStart = Date.now();
    for (const ws of [...connections]) {
        try { ws.close(1000); } catch (e) {}
    }
    await sleep(3000);

    // Force terminate remaining
    for (const ws of [...connections]) {
        try { ws.terminate(); } catch (e) {}
    }

    const totalTime = ((Date.now() - startTime) / 1000).toFixed(1);

    console.log('\n==========================================================');
    console.log('  RESULTS');
    console.log('==========================================================');
    console.log(`  Peak Connections:       ${peakConnections}`);
    console.log(`  First Failure At:       ${firstFailureAt || 'N/A'}`);
    console.log(`  Total Failed:           ${failed}`);
    console.log(`  Total Errors:           ${errors}`);
    console.log(`  Total Closed (during):  ${closed}`);
    console.log(`  Total Time:             ${totalTime}s`);
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

runConnectionTest().catch(err => {
    console.error('Test failed:', err);
    process.exit(1);
});
