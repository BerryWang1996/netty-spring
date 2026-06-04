package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo chat room controller illustrating real-world WebSocket usage patterns.
 * <p>
 * This controller manages a multi-user chat room at {@code /ws/chat} with the
 * following features:
 * <ul>
 *   <li>Join/leave notifications broadcast to all connected users</li>
 *   <li>Live online-user list updates</li>
 *   <li>Public broadcast messages</li>
 *   <li>Private (direct) messages via the {@code /pm <nickname> <text>} command</li>
 * </ul>
 * The HTML chat UI is served at {@code /chat} and connects back to the WebSocket
 * endpoint with a user-chosen nickname.
 *
 * @author berrywang1996
 * @since V1.3.0
 */
@Slf4j
@Controller
public class ChatRoomController {

    /** The WebSocket URI that this chat room listens on. */
    private static final String CHAT_URI = "/ws/chat";

    /** Injected message sender for broadcasting and targeted WebSocket messaging. */
    private final MessageSender messageSender;

    /** This node's cluster id when running in cluster mode (profile=cluster); {@code null} single-node. */
    private final String nodeId;

    /** Maps WebSocket session IDs to user-chosen nicknames for all connected users. */
    private final Map<String, String> nicknames = new ConcurrentHashMap<>();

    /**
     * Constructs the chat room controller. The {@link ClusterNodeManager} is optional — present only
     * when the cluster starter is active ({@code cluster.enable=true}); in single-node mode it is absent
     * and {@link #nodeId} stays {@code null}, so all cluster-specific behavior is skipped.
     *
     * @param messageSender             the WebSocket message sender for broadcast and targeted delivery
     * @param clusterNodeManagerProvider optional provider of the cluster node manager
     */
    public ChatRoomController(MessageSender messageSender,
                              ObjectProvider<ClusterNodeManager> clusterNodeManagerProvider) {
        this.messageSender = messageSender;
        ClusterNodeManager mgr = clusterNodeManagerProvider.getIfAvailable();
        this.nodeId = (mgr != null) ? mgr.getNodeId() : null;
    }

    /**
     * Handles a new WebSocket connection to the chat room.
     * <p>
     * Extracts the user's nickname from the {@code nickname} query parameter (or generates
     * a default), registers the session, and broadcasts a join notification to all users.
     *
     * @param session the newly connected WebSocket session
     */
    @MessageMapping(value = CHAT_URI, messageType = MessageType.ON_CONNECTED)
    public void onConnected(MessageSession session) {
        String nickname = session.getQueryParam("nickname");
        if (nickname == null || nickname.isBlank()) {
            nickname = "User-" + session.getSessionId().substring(0, 6);
        }
        nicknames.put(session.getSessionId(), nickname);
        log.info("Chat user joined: {} (sessionId={})", nickname, session.getSessionId());

        // Notify everyone about the new user
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "join");
        event.put("nickname", nickname);
        event.put("onlineUsers", getOnlineUsers());
        event.put("onlineCount", nicknames.size());
        if (nodeId != null) {
            event.put("originNode", nodeId);
        }
        messageSender.broadcastJson(CHAT_URI, event);
    }

    /**
     * Handles an incoming text message in the chat room.
     * <p>
     * If the message type is {@code "private"} and a target nickname is provided,
     * the message is delivered only to the targeted session. Otherwise, the message
     * is broadcast to all connected users.
     *
     * @param msg     the deserialized chat message DTO
     * @param session the sender's WebSocket session
     */
    @MessageMapping(value = CHAT_URI, messageType = MessageType.TEXT_MESSAGE)
    public void onMessage(ChatMessage msg, MessageSession session) {
        String nickname = nicknames.getOrDefault(session.getSessionId(), "Unknown");
        log.info("Chat message from {}: {}", nickname, msg.getText());

        if ("private".equals(msg.getType()) && msg.getTarget() != null) {
            // Private message: find target session
            String targetSessionId = findSessionByNickname(msg.getTarget());
            if (targetSessionId != null) {
                Map<String, Object> privateMsg = new LinkedHashMap<>();
                privateMsg.put("type", "private");
                privateMsg.put("from", nickname);
                privateMsg.put("text", msg.getText());
                messageSender.sendJsonToSession(CHAT_URI, privateMsg, targetSessionId);
                // Also echo back to sender
                privateMsg.put("type", "private-sent");
                privateMsg.put("to", msg.getTarget());
                messageSender.sendJsonToSession(CHAT_URI, privateMsg, session.getSessionId());
            } else {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("type", "error");
                error.put("text", "User '" + msg.getTarget() + "' not found");
                messageSender.sendJsonToSession(CHAT_URI, error, session.getSessionId());
            }
        } else {
            // Broadcast message
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type", "message");
            broadcast.put("nickname", nickname);
            broadcast.put("text", msg.getText());
            if (nodeId != null) {
                broadcast.put("originNode", nodeId);
            }
            messageSender.broadcastJson(CHAT_URI, broadcast);
        }
    }

    /**
     * Handles a WebSocket session close event.
     * <p>
     * Removes the user from the nicknames map and broadcasts a leave notification
     * with the updated online user list.
     *
     * @param session the WebSocket session that was closed
     */
    @MessageMapping(value = CHAT_URI, messageType = MessageType.ON_CLOSE)
    public void onClose(MessageSession session) {
        String nickname = nicknames.remove(session.getSessionId());
        if (nickname != null) {
            log.info("Chat user left: {} (sessionId={})", nickname, session.getSessionId());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "leave");
            event.put("nickname", nickname);
            event.put("onlineUsers", getOnlineUsers());
            event.put("onlineCount", nicknames.size());
            if (nodeId != null) {
                event.put("originNode", nodeId);
            }
            messageSender.broadcastJson(CHAT_URI, event);
        }
    }

    /**
     * Handles WebSocket errors in the chat room by logging the exception.
     *
     * @param e the exception that occurred during WebSocket processing
     */
    @MessageMapping(value = CHAT_URI, messageType = MessageType.ON_ERROR)
    public void onError(Exception e) {
        log.warn("Chat room error", e);
    }

    /**
     * Serves the chat room HTML page at {@code /chat}.
     *
     * @return the inline HTML string containing the chat room UI and JavaScript
     */
    @RequestMapping("/chat")
    public String chatPage() {
        return CHAT_HTML;
    }

    /**
     * Reports which cluster node served this request (or {@code "single-node"} when cluster mode is
     * off). The chat UI calls this to show a node badge and to prove cross-node delivery in the
     * multi-node Docker demo.
     *
     * @return a small JSON document {@code {"nodeId":"<id>"}}
     */
    @RequestMapping("/whoami")
    public String whoami() {
        String id = (nodeId != null) ? nodeId : "single-node";
        return "{\"nodeId\":\"" + id + "\"}";
    }

    /**
     * Returns a snapshot of all currently online user nicknames.
     *
     * @return an unordered list of nicknames
     */
    private List<String> getOnlineUsers() {
        return new ArrayList<>(nicknames.values());
    }

    /**
     * Finds the WebSocket session ID associated with the given nickname.
     *
     * @param nickname the nickname to search for
     * @return the session ID, or {@code null} if no user with that nickname is connected
     */
    private String findSessionByNickname(String nickname) {
        for (Map.Entry<String, String> entry : nicknames.entrySet()) {
            if (entry.getValue().equals(nickname)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * DTO for deserializing inbound chat messages from the WebSocket client.
     * <p>
     * The {@code type} field distinguishes between broadcast ({@code "message"})
     * and private ({@code "private"}) messages. For private messages the
     * {@code target} field holds the recipient's nickname.
     */
    public static class ChatMessage {
        /** Message type: {@code "message"} for broadcast or {@code "private"} for direct. */
        private String type;
        /** The text content of the chat message. */
        private String text;
        /** The target nickname for private messages; {@code null} for broadcast. */
        private String target;

        /** @return the message type */
        public String getType() { return type; }
        /** @param type the message type to set */
        public void setType(String type) { this.type = type; }
        /** @return the message text */
        public String getText() { return text; }
        /** @param text the message text to set */
        public void setText(String text) { this.text = text; }
        /** @return the target nickname for private messages */
        public String getTarget() { return target; }
        /** @param target the target nickname to set */
        public void setTarget(String target) { this.target = target; }
    }

    private static final String CHAT_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Chat Room - netty-spring demo</title>
<style>
:root{color-scheme:light;--ink:#14213d;--muted:#64748b;--bg:#f8fafc;--card:rgba(255,255,255,.92);--line:#d8e2ef;--accent:#b45309;--accent-soft:#fef3c7;--green:#059669;--red:#dc2626}
*{box-sizing:border-box;margin:0}
body{min-height:100vh;color:var(--ink);font-family:ui-sans-serif,system-ui,sans-serif;background:var(--bg)}
.wrap{display:grid;grid-template-columns:260px 1fr;height:100vh}
.sidebar{background:var(--card);border-right:1px solid var(--line);padding:20px;display:flex;flex-direction:column}
.sidebar h2{font-size:1.1rem;margin-bottom:12px}
.status{font-size:.82rem;color:var(--muted);margin-bottom:16px}
.status .dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:4px}
.dot.on{background:var(--green)}.dot.off{background:var(--red)}
#userList{flex:1;overflow-y:auto;list-style:none;padding:0}
#userList li{padding:6px 10px;border-radius:8px;font-size:.92rem;cursor:pointer}
#userList li:hover{background:var(--accent-soft)}
#userList li.me{font-weight:700;color:var(--accent)}
.back{margin-top:auto;padding-top:12px;border-top:1px solid var(--line)}
.back a{color:var(--accent);text-decoration:none;font-size:.88rem}
.main{display:flex;flex-direction:column}
.header{padding:14px 20px;border-bottom:1px solid var(--line);background:var(--card);font-weight:600;font-size:1.05rem}
#messages{flex:1;overflow-y:auto;padding:16px 20px;display:flex;flex-direction:column;gap:6px}
.msg{max-width:80%;padding:8px 14px;border-radius:16px;font-size:.92rem;line-height:1.5;word-break:break-word}
.msg.system{align-self:center;background:#e0f2fe;color:#0369a1;border-radius:20px;font-size:.82rem;padding:4px 14px}
.msg.in{align-self:flex-start;background:var(--card);border:1px solid var(--line)}
.msg.out{align-self:flex-end;background:var(--accent-soft);color:#78350f}
.msg.private-in{align-self:flex-start;background:#fce7f3;border:1px solid #f9a8d4}
.msg.private-out{align-self:flex-end;background:#ede9fe;color:#5b21b6}
.msg .nick{font-weight:700;font-size:.8rem;margin-bottom:2px;color:var(--accent)}
.msg .pm-label{font-size:.72rem;color:var(--muted);font-style:italic}
.input-bar{display:flex;gap:8px;padding:12px 20px;border-top:1px solid var(--line);background:var(--card)}
#msgInput{flex:1;border:1px solid var(--line);border-radius:12px;padding:10px 14px;font-size:.94rem;outline:none}
#msgInput:focus{border-color:var(--accent)}
button{border:none;border-radius:12px;padding:10px 20px;font-weight:700;font-size:.9rem;cursor:pointer;background:var(--accent);color:#fff}
button:hover{opacity:.9}
.join-overlay{position:fixed;inset:0;background:rgba(0,0,0,.4);display:flex;align-items:center;justify-content:center;z-index:10}
.join-box{background:#fff;border-radius:24px;padding:36px;width:min(400px,90vw);text-align:center;box-shadow:0 20px 60px rgba(0,0,0,.15)}
.join-box h1{font-size:1.6rem;margin-bottom:6px}
.join-box p{color:var(--muted);font-size:.92rem;margin-bottom:20px}
.join-box input{width:100%;border:1px solid var(--line);border-radius:12px;padding:12px;font-size:1rem;text-align:center;outline:none;margin-bottom:14px}
.join-box input:focus{border-color:var(--accent)}
@media(max-width:700px){.wrap{grid-template-columns:1fr}.sidebar{display:none}}
</style>
</head>
<body>

<div class="join-overlay" id="joinOverlay">
  <div class="join-box">
    <h1>Join Chat Room</h1>
    <p>Enter a nickname to start chatting</p>
    <input id="nicknameInput" placeholder="Your nickname..." maxlength="20" autofocus>
    <br><button onclick="joinChat()">Join</button>
  </div>
</div>

<div class="wrap">
  <div class="sidebar">
    <h2>Online Users</h2>
    <div class="status"><span class="dot off" id="statusDot"></span><span id="statusText">Disconnected</span></div>
    <ul id="userList"></ul>
    <div class="back"><a href="/">&larr; Back to demo home</a></div>
  </div>
  <div class="main">
    <div class="header">Chat Room <span id="nodeBadge" style="font-size:.8rem;color:var(--muted);font-weight:400;margin-left:8px"></span></div>
    <div id="messages"></div>
    <div class="input-bar">
      <input id="msgInput" placeholder="Type a message... (use /pm nickname text for private)" disabled>
      <button id="sendBtn" onclick="sendMsg()" disabled>Send</button>
    </div>
  </div>
</div>

<script>
let ws, myNickname, myNode = null, privateTarget = null;
fetch('/whoami').then(r => r.json()).then(d => { myNode = d.nodeId; renderNodeBadge(); }).catch(() => {});
function renderNodeBadge() { const b = document.getElementById('nodeBadge'); if (b && myNode) b.textContent = 'Node: ' + myNode; }
const msgDiv = document.getElementById('messages');
const msgInput = document.getElementById('msgInput');
const sendBtn = document.getElementById('sendBtn');
const userList = document.getElementById('userList');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');

document.getElementById('nicknameInput').addEventListener('keydown', e => { if(e.key==='Enter') joinChat(); });
msgInput.addEventListener('keydown', e => { if(e.key==='Enter') sendMsg(); });

function joinChat() {
  myNickname = document.getElementById('nicknameInput').value.trim();
  if(!myNickname) return;
  document.getElementById('joinOverlay').style.display = 'none';
  connect();
}

function connect() {
  const host = location.host;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + host + '/ws/chat?nickname=' + encodeURIComponent(myNickname));
  ws.onopen = () => {
    statusDot.className = 'dot on'; statusText.textContent = 'Connected';
    msgInput.disabled = false; sendBtn.disabled = false; msgInput.focus();
  };
  ws.onclose = () => {
    statusDot.className = 'dot off'; statusText.textContent = 'Disconnected';
    msgInput.disabled = true; sendBtn.disabled = true;
    addSystem('Connection closed. Refresh to reconnect.');
  };
  ws.onmessage = e => {
    try { handleMsg(JSON.parse(e.data)); } catch(err) { console.error(err); }
  };
}

function handleMsg(data) {
  if(data.type === 'join') {
    addSystem(data.nickname + ' joined the chat' + viaSuffix(data));
    updateUsers(data.onlineUsers);
  } else if(data.type === 'leave') {
    addSystem(data.nickname + ' left the chat' + viaSuffix(data));
    updateUsers(data.onlineUsers);
  } else if(data.type === 'message') {
    const isMe = data.nickname === myNickname;
    addChat(data.nickname, data.text, isMe ? 'out' : 'in', crossNode(data) ? data.originNode : null);
  } else if(data.type === 'private') {
    addPrivate(data.from, data.text, 'private-in');
  } else if(data.type === 'private-sent') {
    addPrivate('To ' + data.to, data.text, 'private-out');
  } else if(data.type === 'error') {
    addSystem(data.text);
  }
}

function sendMsg() {
  const text = msgInput.value.trim();
  if(!text || !ws || ws.readyState !== 1) return;
  // Check for /pm command
  const pmMatch = text.match(/^\\/pm\\s+(\\S+)\\s+(.+)$/);
  if(pmMatch) {
    ws.send(JSON.stringify({type:'private', target:pmMatch[1], text:pmMatch[2]}));
  } else {
    ws.send(JSON.stringify({type:'message', text:text}));
  }
  msgInput.value = '';
}

function addSystem(text) {
  const d = document.createElement('div');
  d.className = 'msg system'; d.textContent = text;
  msgDiv.appendChild(d); msgDiv.scrollTop = msgDiv.scrollHeight;
}

function crossNode(data) { return data.originNode && myNode && data.originNode !== myNode; }
function viaSuffix(data) { return crossNode(data) ? ' (via ' + data.originNode + ')' : ''; }

function addChat(nick, text, cls, via) {
  const d = document.createElement('div');
  d.className = 'msg ' + cls;
  const viaTag = via ? ' <span class="pm-label">via ' + esc(via) + '</span>' : '';
  d.innerHTML = '<div class="nick">' + esc(nick) + viaTag + '</div>' + esc(text);
  msgDiv.appendChild(d); msgDiv.scrollTop = msgDiv.scrollHeight;
}

function addPrivate(label, text, cls) {
  const d = document.createElement('div');
  d.className = 'msg ' + cls;
  d.innerHTML = '<div class="pm-label">Private</div><div class="nick">' + esc(label) + '</div>' + esc(text);
  msgDiv.appendChild(d); msgDiv.scrollTop = msgDiv.scrollHeight;
}

function updateUsers(users) {
  userList.innerHTML = '';
  (users || []).sort().forEach(u => {
    const li = document.createElement('li');
    li.textContent = u;
    if(u === myNickname) li.className = 'me';
    li.onclick = () => { if(u !== myNickname) { msgInput.value = '/pm ' + u + ' '; msgInput.focus(); } };
    userList.appendChild(li);
  });
}

function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
</script>
</body>
</html>
""";
}
