package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.service.ListenTogetherService;
import com.neko.music.util.HttpResourceCache;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一起听实时连接。
 *
 * <p>浏览器连接时只携带一次性 ticket，不把长期用户 access token 放进 URL。建立连接后，
 * 所有播放与临时歌单操作都使用 JSON 文本帧；服务端广播权威快照，客户端不自行修改共享状态。</p>
 */
@ServerEndpoint(ListenTogetherWebSocketEndpoint.PATH)
public final class ListenTogetherWebSocketEndpoint {

    public static final String PATH = "/api/listen/ws";
    private static final Logger logger = LoggerFactory.getLogger(ListenTogetherWebSocketEndpoint.class);
    private static final int MAX_TEXT_BYTES = 8 * 1024;

    private String roomToken;
    private int userId;
    private String connectionId;
    private ConnectedClient client;

    public static ServerEndpointConfig endpointConfig() {
        return ServerEndpointConfig.Builder.create(ListenTogetherWebSocketEndpoint.class, PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public void modifyHandshake(
                            ServerEndpointConfig config,
                            jakarta.websocket.server.HandshakeRequest request,
                            jakarta.websocket.HandshakeResponse response
                    ) {
                        response.getHeaders().put(
                                "Cache-Control", List.of(HttpResourceCache.CACHE_CONTROL_NO_STORE));
                    }
                })
                .build();
    }

    @OnOpen
    public void onOpen(Session session) {
        session.setMaxTextMessageBufferSize(MAX_TEXT_BYTES);
        session.setMaxBinaryMessageBufferSize(256);
        session.setMaxIdleTimeout(120_000L);

        String ticket = firstQueryValue(session, "ticket");
        try {
            ListenTogetherService service = Main.getListenTogetherService();
            ListenTogetherService.TicketIdentity identity = service.consumeTicket(ticket);
            roomToken = identity.roomToken();
            userId = identity.userId();
            connectionId = UUID.randomUUID().toString();
            client = new ConnectedClient(connectionId, userId, session);
            service.attach(roomToken, userId, client);
        } catch (ListenTogetherService.ListenTogetherException e) {
            sendErrorAndClose(session, e.code(), e.messageKey(), CloseReason.CloseCodes.VIOLATED_POLICY);
        } catch (Exception e) {
            logger.warn("建立一起听 WebSocket 失败: {}", e.getMessage());
            sendErrorAndClose(session, "service_unavailable", "listen.service_unavailable",
                    CloseReason.CloseCodes.TRY_AGAIN_LATER);
        }
    }

    @OnMessage
    public void onText(Session session, String text) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_BYTES) {
            sendError(session, null, "bad_message", "listen.bad_message");
            return;
        }
        try {
            JsonNode command = Main.getObjectMapper().readTree(text);
            String type = command.path("type").asText("").trim();
            if ("clock".equals(type)) {
                ObjectNode response = Main.getObjectMapper().createObjectNode();
                response.put("type", "clock");
                response.put("requestId", command.path("requestId").asText(""));
                response.put("clientTime", command.path("clientTime").asLong(0));
                response.put("serverTime", System.currentTimeMillis());
                send(session, response.toString());
                return;
            }
            if (!"control".equals(type)) {
                sendError(session, command.path("opId").asText(null),
                        "bad_command", "listen.bad_command");
                return;
            }
            Main.getListenTogetherService().handleControl(roomToken, userId, command);
        } catch (ListenTogetherService.ListenTogetherException e) {
            JsonNode command = null;
            try {
                command = Main.getObjectMapper().readTree(text);
            } catch (Exception ignored) {
                // 已经按消息格式错误返回
            }
            String opId = command == null ? null : command.path("opId").asText(null);
            sendError(session, opId, e.code(), e.messageKey());
        } catch (Exception e) {
            logger.debug("一起听 WebSocket 消息处理失败: {}", e.getMessage());
            sendError(session, null, "bad_message", "listen.bad_message");
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        detach();
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (error != null) {
            logger.debug("一起听 WebSocket 异常: {}", error.getMessage());
        }
        detach();
    }

    private void detach() {
        if (roomToken != null && connectionId != null) {
            Main.getListenTogetherService().detach(roomToken, userId, connectionId);
            roomToken = null;
            connectionId = null;
        }
    }

    private static void sendErrorAndClose(
            Session session,
            String code,
            String messageKey,
            CloseReason.CloseCode closeCode
    ) {
        sendError(session, null, code, messageKey);
        try {
            if (session.isOpen()) {
                session.close(new CloseReason(closeCode, ""));
            }
        } catch (IOException ignored) {
            // Peer may have already closed.
        }
    }

    private static void sendError(Session session, String opId, String code, String messageKey) {
        ObjectNode error = Main.getObjectMapper().createObjectNode();
        error.put("type", "error");
        error.put("code", code);
        error.put("message", ApiMessages.translate(messageKey));
        if (opId != null && !opId.isBlank()) {
            error.put("opId", opId);
        }
        send(session, error.toString());
    }

    private static void send(Session session, String text) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(text);
            } catch (IOException e) {
                logger.debug("写入一起听 WebSocket 响应失败: {}", e.getMessage());
            }
        }
    }

    private static String firstQueryValue(Session session, String name) {
        Map<String, List<String>> parameters = session.getRequestParameterMap();
        List<String> values = parameters == null ? null : parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static final class ConnectedClient implements ListenTogetherService.ClientConnection {
        private final String id;
        private final int userId;
        private final Session session;

        private ConnectedClient(String id, int userId, Session session) {
            this.id = id;
            this.userId = userId;
            this.session = session;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int userId() {
            return userId;
        }

        @Override
        public void send(String json) {
            ListenTogetherWebSocketEndpoint.send(session, json);
        }

        @Override
        public void close(String messageKey) {
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, ""));
                }
            } catch (IOException ignored) {
                // Peer may have already closed.
            }
        }
    }
}
