package com.neko.music.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * “一起听”房间的进程内权威状态机。
 *
 * <p>房间是临时会话：不写入用户歌单，也不落 MySQL。所有成员拥有相同的播放与
 * 临时歌单操作权限；房间标识、成员数、临时歌单和播放状态都以本服务为准，客户端只
 * 提交操作意图，随后接收 WebSocket 广播。</p>
 *
 * <p>当前部署模型与站内消息推送一致，按单 JVM 实现。未来若水平扩容，需要把房间
 * 状态与广播替换为 Redis 状态和 Pub/Sub，但对外 WebSocket 契约可以保持不变。</p>
 */
public final class ListenTogetherService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ListenTogetherService.class);

    /** 创建房间无需任何选项，人数上限是固定的产品规则。 */
    public static final int MAX_MEMBERS_PER_ROOM = 10;
    public static final int MAX_PLAYLIST_ITEMS = 200;

    private static final int WS_TICKET_TTL_SECONDS = 30;
    private static final int WS_TICKET_ATTEMPTS = 3;
    private static final int RECENT_OP_LIMIT = 512;
    /** 短保留用于网络切换重连；异常退出后其他成员看到的是“离线即消失”。 */
    private static final long MEMBER_DISCONNECT_GRACE_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    public static final String MODE_LIST_REPEAT = "list_repeat";
    public static final String MODE_SINGLE_REPEAT = "single_repeat";
    public static final String MODE_SHUFFLE = "shuffle";
    private static final Set<String> REPEAT_MODES =
            Set.of(MODE_LIST_REPEAT, MODE_SINGLE_REPEAT, MODE_SHUFFLE);

    private static final String TICKET_PREFIX = "listen:ws-ticket:";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 只暴露服务需要的 Redis 能力，便于单元测试替换。 */
    public interface TicketStore {
        boolean putIfAbsent(String key, String value, int ttlSeconds);

        String take(String key);
    }

    /** WebSocket 连接的最小抽象；endpoint 负责把 JSON 写到具体 Session。 */
    public interface ClientConnection {
        String id();

        int userId();

        void send(String json);

        void close(String messageKey);
    }

    public record Track(int id, String title, String artist, String album, int duration, String maxQuality) {
    }

    public record TicketIdentity(String roomToken, int userId) {
    }

    public record JoinResult(Map<String, Object> snapshot, String ticket) {
    }

    /** 业务异常同时携带 HTTP 状态、稳定错误码和可翻译文案键。 */
    public static final class ListenTogetherException extends Exception {
        private final int status;
        private final String code;
        private final String messageKey;

        public ListenTogetherException(int status, String code, String messageKey) {
            super(messageKey);
            this.status = status;
            this.code = code;
            this.messageKey = messageKey;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private static final class Member {
        private final int userId;
        private String nickname;
        private long lastSeenAt;

        private Member(int userId, String nickname, long lastSeenAt) {
            this.userId = userId;
            this.nickname = nickname;
            this.lastSeenAt = lastSeenAt;
        }
    }

    private static final class PlaylistItem {
        private final String entryId;
        private final Track track;
        private final int addedByUserId;
        private final long addedAt;

        private PlaylistItem(String entryId, Track track, int addedByUserId, long addedAt) {
            this.entryId = entryId;
            this.track = track;
            this.addedByUserId = addedByUserId;
            this.addedAt = addedAt;
        }
    }

    private static final class Room {
        private final Object lock = new Object();
        private final String token;
        private int ownerUserId;
        private final LinkedHashMap<Integer, Member> members = new LinkedHashMap<>();
        private final LinkedHashMap<String, PlaylistItem> playlist = new LinkedHashMap<>();
        private final LinkedHashMap<String, ClientConnection> connections = new LinkedHashMap<>();
        private final LinkedHashMap<String, Boolean> recentOps = new LinkedHashMap<>();
        private String currentEntryId;
        private boolean playing;
        private long positionMs;
        private long effectiveAt;
        private String repeatMode = MODE_LIST_REPEAT;
        private long revision;
        private long playlistRevision;
        private long lastActiveAt;
        private ScheduledFuture<?> endTask;

        private Room(String token, int ownerUserId, long now) {
            this.token = token;
            this.ownerUserId = ownerUserId;
            this.lastActiveAt = now;
        }
    }

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final TicketStore ticketStore;
    private final IntFunction<Optional<Track>> trackResolver;
    private final IntFunction<String> nicknameResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    public ListenTogetherService(
            TicketStore ticketStore,
            IntFunction<Optional<Track>> trackResolver,
            IntFunction<String> nicknameResolver,
            ObjectMapper objectMapper
    ) {
        this(ticketStore, trackResolver, nicknameResolver, objectMapper, Clock.systemUTC(),
                newScheduler(), true);
    }

    ListenTogetherService(
            TicketStore ticketStore,
            IntFunction<Optional<Track>> trackResolver,
            IntFunction<String> nicknameResolver,
            ObjectMapper objectMapper,
            Clock clock,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.ticketStore = ticketStore;
        this.trackResolver = trackResolver;
        this.nicknameResolver = nicknameResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(this::purgeIdleRooms, 5, 5, TimeUnit.SECONDS);
        }
    }

    /** 创建空房间；创建者自动成为第一个成员，不复制其本地播放队列。 */
    public String createRoom(int userId) throws ListenTogetherException {
        String nickname = resolveNickname(userId);
        long now = clock.millis();
        for (int attempt = 0; attempt < 8; attempt++) {
            String token = randomToken(16);
            Room room = new Room(token, userId, now);
            room.members.put(userId, new Member(userId, nickname, now));
            if (rooms.putIfAbsent(token, room) == null) {
                return token;
            }
        }
        throw new ListenTogetherException(503, "room_unavailable", "listen.service_unavailable");
    }

    /** 幂等加入：同一账号刷新页面或多标签页不会重复占用成员名额。 */
    public JoinResult joinRoom(String token, int userId) throws ListenTogetherException {
        Room room = requireRoom(token);
        String nickname = resolveNickname(userId);
        synchronized (room.lock) {
            room.lastActiveAt = clock.millis();
            Member existing = room.members.get(userId);
            if (existing != null) {
                existing.nickname = nickname;
                existing.lastSeenAt = room.lastActiveAt;
            } else {
                if (room.members.size() >= MAX_MEMBERS_PER_ROOM) {
                    throw new ListenTogetherException(409, "room_full", "listen.full");
                }
                room.members.put(userId, new Member(userId, nickname, room.lastActiveAt));
            }
            String ticket = issueTicketLocked(room, userId);
            return new JoinResult(snapshotLocked(room), ticket);
        }
    }

    public String issueTicket(String token, int userId) throws ListenTogetherException {
        Room room = requireRoom(token);
        synchronized (room.lock) {
            requireMember(room, userId);
            room.lastActiveAt = clock.millis();
            return issueTicketLocked(room, userId);
        }
    }

    public TicketIdentity consumeTicket(String ticket) throws ListenTogetherException {
        if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
            throw new ListenTogetherException(401, "invalid_ticket", "listen.ticket.invalid");
        }
        String value = ticketStore.take(TICKET_PREFIX + ticket);
        if (value == null || value.isBlank()) {
            throw new ListenTogetherException(401, "invalid_ticket", "listen.ticket.invalid");
        }
        int split = value.lastIndexOf(':');
        if (split <= 0 || split >= value.length() - 1) {
            throw new ListenTogetherException(401, "invalid_ticket", "listen.ticket.invalid");
        }
        try {
            return new TicketIdentity(value.substring(0, split), Integer.parseInt(value.substring(split + 1)));
        } catch (NumberFormatException e) {
            throw new ListenTogetherException(401, "invalid_ticket", "listen.ticket.invalid");
        }
    }

    /** 注册一条连接并返回初始完整快照。 */
    public Map<String, Object> attach(String token, int userId, ClientConnection connection)
            throws ListenTogetherException {
        Room room = requireRoom(token);
        Map<String, Object> snapshot;
        synchronized (room.lock) {
            requireMember(room, userId);
            room.connections.put(connection.id(), connection);
            room.lastActiveAt = clock.millis();
            room.members.get(userId).lastSeenAt = room.lastActiveAt;
            snapshot = snapshotLocked(room);
        }
        connection.send(toJson(snapshot));
        broadcastRoom(room);
        return snapshot;
    }

    public void detach(String token, int userId, String connectionId) {
        Room room = rooms.get(token);
        if (room == null) {
            return;
        }
        synchronized (room.lock) {
            ClientConnection removed = room.connections.remove(connectionId);
            if (removed == null) {
                return;
            }
            room.lastActiveAt = clock.millis();
            Member member = room.members.get(userId);
            if (member != null) {
                member.lastSeenAt = room.lastActiveAt;
            }
            transferOwnerIfNeededLocked(room);
        }
        broadcastRoom(room);
    }

    public void leaveRoom(String token, int userId) throws ListenTogetherException {
        Room room = requireRoom(token);
        boolean removedRoom = false;
        synchronized (room.lock) {
            requireMember(room, userId);
            room.members.remove(userId);
            List<String> connectionIds = room.connections.values().stream()
                    .filter(connection -> connection.userId() == userId)
                    .map(ClientConnection::id)
                    .toList();
            for (String connectionId : connectionIds) {
                ClientConnection connection = room.connections.remove(connectionId);
                if (connection != null) {
                    connection.close("listen.leave.ok");
                }
            }
            transferOwnerIfNeededLocked(room);
            if (room.members.isEmpty()) {
                rooms.remove(token, room);
                cancelEndTaskLocked(room);
                removedRoom = true;
            }
        }
        if (!removedRoom) {
            broadcastRoom(room);
        }
    }

    public void closeRoom(String token, int userId) throws ListenTogetherException {
        Room room = requireRoom(token);
        synchronized (room.lock) {
            requireMember(room, userId);
            if (room.ownerUserId != userId) {
                throw new ListenTogetherException(403, "forbidden", "listen.forbidden");
            }
            rooms.remove(token, room);
            cancelEndTaskLocked(room);
            Map<String, Object> event = baseEvent(room, "room.closed");
            event.put("reason", "closed");
            event.put("message", "listen.room.closed");
            String json = toJson(event);
            List<ClientConnection> clients = List.copyOf(room.connections.values());
            room.connections.clear();
            for (ClientConnection client : clients) {
                client.send(json);
                client.close("listen.room.closed");
            }
        }
    }

    /** 处理一条播放/临时歌单操作；任何房间成员都拥有相同权限。 */
    public void handleControl(String token, int userId, JsonNode command) throws ListenTogetherException {
        Room room = requireRoom(token);
        String action = command.path("action").asText("").trim();
        String opId = command.path("opId").asText("").trim();
        if (action.isEmpty() || opId.isEmpty() || opId.length() > 96) {
            throw new ListenTogetherException(400, "bad_command", "listen.bad_command");
        }

        if ("playlist.add".equals(action)) {
            int trackId = command.path("trackId").asInt(0);
            if (trackId <= 0) {
                throw new ListenTogetherException(400, "bad_track_id", "listen.track.not_found");
            }
            Track track = trackResolver.apply(trackId)
                    .orElseThrow(() -> new ListenTogetherException(
                            404, "track_not_found", "listen.track.not_found"));
            synchronized (room.lock) {
                requireMember(room, userId);
                beginOperationLocked(room, opId);
                if (room.playlist.size() >= MAX_PLAYLIST_ITEMS) {
                    throw new ListenTogetherException(409, "playlist_full", "listen.playlist.full");
                }
                String entryId = "q_" + randomToken(8);
                long now = clock.millis();
                room.playlist.put(entryId, new PlaylistItem(entryId, track, userId, now));
                if (room.currentEntryId == null) {
                    room.currentEntryId = entryId;
                    room.positionMs = 0;
                    room.effectiveAt = now;
                }
                room.playlistRevision++;
                room.revision++;
                room.lastActiveAt = now;
                broadcastPlaylistLocked(room, userId);
            }
            return;
        }

        synchronized (room.lock) {
            requireMember(room, userId);
            beginOperationLocked(room, opId);
            long now = clock.millis();
            room.lastActiveAt = now;
            switch (action) {
                case "playback.play" -> applyPlayLocked(room, command, now, userId);
                case "playback.pause" -> applyPauseLocked(room, now, userId);
                case "playback.seek" -> applySeekLocked(room, command, now, userId);
                case "playback.select" -> applySelectLocked(room, command, now, userId);
                case "playback.next" -> applyStepLocked(room, true, now, userId);
                case "playback.prev" -> applyStepLocked(room, false, now, userId);
                case "playlist.remove" -> applyRemoveLocked(room, command, now, userId);
                case "playlist.move" -> applyMoveLocked(room, command, now, userId);
                case "playlist.clear" -> applyClearLocked(room, command, now, userId);
                case "playlist.mode" -> applyModeLocked(room, command, now, userId);
                default -> throw new ListenTogetherException(400, "bad_action", "listen.bad_action");
            }
        }
    }

    public Map<String, Object> currentSnapshot(String token, int userId) throws ListenTogetherException {
        Room room = requireRoom(token);
        synchronized (room.lock) {
            requireMember(room, userId);
            return snapshotLocked(room);
        }
    }

    private void applyPlayLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        String requestedEntry = textOrNull(command, "entryId");
        if (requestedEntry != null) {
            if (!room.playlist.containsKey(requestedEntry)) {
                throw new ListenTogetherException(404, "entry_not_found", "listen.entry.not_found");
            }
            if (!requestedEntry.equals(room.currentEntryId)) {
                room.currentEntryId = requestedEntry;
                room.positionMs = 0;
            }
        } else if (room.currentEntryId == null) {
            room.currentEntryId = room.playlist.keySet().stream().findFirst().orElse(null);
            room.positionMs = 0;
        }
        if (room.currentEntryId == null) {
            throw new ListenTogetherException(409, "playlist_empty", "listen.playlist.empty");
        }
        long durationMs = currentDurationMsLocked(room);
        if (durationMs > 0 && room.positionMs >= durationMs) {
            room.positionMs = 0;
        }
        room.playing = true;
        room.effectiveAt = now;
        room.revision++;
        scheduleEndLocked(room);
        broadcastPlaylistLocked(room, actorUserId);
        broadcastPlaybackLocked(room, actorUserId);
    }

    private void applyPauseLocked(Room room, long now, int actorUserId) {
        room.positionMs = positionAtLocked(room, now);
        room.playing = false;
        room.effectiveAt = now;
        room.revision++;
        cancelEndTaskLocked(room);
        broadcastPlaybackLocked(room, actorUserId);
    }

    private void applySeekLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        if (room.currentEntryId == null) {
            throw new ListenTogetherException(409, "playlist_empty", "listen.playlist.empty");
        }
        if (!command.has("positionMs") || !command.path("positionMs").canConvertToLong()) {
            throw new ListenTogetherException(400, "bad_position", "listen.bad_position");
        }
        long target = command.path("positionMs").asLong(-1);
        if (target < 0) {
            throw new ListenTogetherException(400, "bad_position", "listen.bad_position");
        }
        long durationMs = currentDurationMsLocked(room);
        room.positionMs = durationMs > 0 ? Math.min(target, durationMs) : target;
        room.effectiveAt = now;
        room.revision++;
        scheduleEndLocked(room);
        broadcastPlaybackLocked(room, actorUserId);
    }

    private void applySelectLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        String entryId = textOrNull(command, "entryId");
        if (entryId == null || !room.playlist.containsKey(entryId)) {
            throw new ListenTogetherException(404, "entry_not_found", "listen.entry.not_found");
        }
        room.currentEntryId = entryId;
        room.positionMs = 0;
        room.playing = true;
        room.effectiveAt = now;
        room.revision++;
        scheduleEndLocked(room);
        broadcastPlaylistLocked(room, actorUserId);
        broadcastPlaybackLocked(room, actorUserId);
    }

    private void applyStepLocked(Room room, boolean forward, long now, int actorUserId)
            throws ListenTogetherException {
        if (room.playlist.isEmpty()) {
            throw new ListenTogetherException(409, "playlist_empty", "listen.playlist.empty");
        }
        List<String> entries = new ArrayList<>(room.playlist.keySet());
        int current = entries.indexOf(room.currentEntryId);
        int next;
        if (current < 0) {
            next = forward ? 0 : entries.size() - 1;
        } else if (forward) {
            next = (current + 1) % entries.size();
        } else {
            next = current == 0 ? entries.size() - 1 : current - 1;
        }
        room.currentEntryId = entries.get(next);
        room.positionMs = 0;
        room.playing = true;
        room.effectiveAt = now;
        room.revision++;
        scheduleEndLocked(room);
        broadcastPlaylistLocked(room, actorUserId);
        broadcastPlaybackLocked(room, actorUserId);
    }

    private void applyRemoveLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        checkPlaylistRevision(room, command);
        String entryId = textOrNull(command, "entryId");
        if (entryId == null || !room.playlist.containsKey(entryId)) {
            throw new ListenTogetherException(404, "entry_not_found", "listen.entry.not_found");
        }
        int oldIndex = new ArrayList<>(room.playlist.keySet()).indexOf(entryId);
        boolean removedCurrent = entryId.equals(room.currentEntryId);
        room.playlist.remove(entryId);
        if (removedCurrent) {
            List<String> remaining = new ArrayList<>(room.playlist.keySet());
            if (remaining.isEmpty()) {
                room.currentEntryId = null;
                room.playing = false;
                room.positionMs = 0;
                room.effectiveAt = now;
                cancelEndTaskLocked(room);
            } else {
                int nextIndex = Math.min(oldIndex, remaining.size() - 1);
                room.currentEntryId = remaining.get(nextIndex);
                room.positionMs = 0;
                room.effectiveAt = now;
                scheduleEndLocked(room);
            }
        }
        room.playlistRevision++;
        room.revision++;
        broadcastPlaylistLocked(room, actorUserId);
        if (removedCurrent) {
            broadcastPlaybackLocked(room, actorUserId);
        }
    }

    private void applyMoveLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        checkPlaylistRevision(room, command);
        String entryId = textOrNull(command, "entryId");
        String beforeEntryId = textOrNull(command, "beforeEntryId");
        if (entryId == null || !room.playlist.containsKey(entryId)) {
            throw new ListenTogetherException(404, "entry_not_found", "listen.entry.not_found");
        }
        if (beforeEntryId != null && (!room.playlist.containsKey(beforeEntryId)
                || beforeEntryId.equals(entryId))) {
            throw new ListenTogetherException(400, "bad_target", "listen.bad_target");
        }
        PlaylistItem moving = room.playlist.get(entryId);
        LinkedHashMap<String, PlaylistItem> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, PlaylistItem> entry : room.playlist.entrySet()) {
            if (entry.getKey().equals(entryId)) {
                continue;
            }
            if (beforeEntryId != null && entry.getKey().equals(beforeEntryId)) {
                reordered.put(entryId, moving);
            }
            reordered.put(entry.getKey(), entry.getValue());
        }
        if (beforeEntryId == null) {
            reordered.put(entryId, moving);
        }
        room.playlist.clear();
        room.playlist.putAll(reordered);
        room.playlistRevision++;
        room.revision++;
        room.lastActiveAt = now;
        broadcastPlaylistLocked(room, actorUserId);
    }

    private void applyClearLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        checkPlaylistRevision(room, command);
        if (room.playlist.isEmpty() && room.currentEntryId == null) {
            return;
        }
        room.playlist.clear();
        room.currentEntryId = null;
        room.playing = false;
        room.positionMs = 0;
        room.effectiveAt = now;
        room.playlistRevision++;
        room.revision++;
        cancelEndTaskLocked(room);
        broadcastPlaylistLocked(room, actorUserId);
        broadcastPlaybackLocked(room, actorUserId);
    }

    private void applyModeLocked(Room room, JsonNode command, long now, int actorUserId)
            throws ListenTogetherException {
        String mode = textOrNull(command, "mode");
        if (mode == null || !REPEAT_MODES.contains(mode)) {
            throw new ListenTogetherException(400, "bad_mode", "listen.bad_mode");
        }
        room.repeatMode = mode;
        room.revision++;
        room.lastActiveAt = now;
        scheduleEndLocked(room);
        broadcastPlaylistLocked(room, actorUserId);
    }

    private void checkPlaylistRevision(Room room, JsonNode command) throws ListenTogetherException {
        if (command.has("playlistRevision")
                && command.path("playlistRevision").asLong(-1) != room.playlistRevision) {
            throw new ListenTogetherException(409, "playlist_changed", "listen.playlist.changed");
        }
    }

    private void beginOperationLocked(Room room, String opId) throws ListenTogetherException {
        if (room.recentOps.containsKey(opId)) {
            throw new ListenTogetherException(409, "duplicate_operation", "listen.duplicate_operation");
        }
        room.recentOps.put(opId, Boolean.TRUE);
        while (room.recentOps.size() > RECENT_OP_LIMIT) {
            String eldest = room.recentOps.keySet().iterator().next();
            room.recentOps.remove(eldest);
        }
    }

    private void scheduleEndLocked(Room room) {
        cancelEndTaskLocked(room);
        if (scheduler == null || !room.playing || room.currentEntryId == null) {
            return;
        }
        long durationMs = currentDurationMsLocked(room);
        if (durationMs <= 0) {
            return;
        }
        long remaining = Math.max(1L, durationMs - positionAtLocked(room, clock.millis()));
        String entryId = room.currentEntryId;
        long revision = room.revision;
        room.endTask = scheduler.schedule(
                () -> autoAdvance(room, entryId, revision),
                remaining,
                TimeUnit.MILLISECONDS);
    }

    private void autoAdvance(Room room, String expectedEntryId, long expectedRevision) {
        synchronized (room.lock) {
            if (rooms.get(room.token) != room
                    || !room.playing
                    || !expectedEntryId.equals(room.currentEntryId)
                    || room.revision != expectedRevision) {
                return;
            }
            List<String> entries = new ArrayList<>(room.playlist.keySet());
            if (entries.isEmpty()) {
                room.currentEntryId = null;
                room.playing = false;
                room.positionMs = 0;
                room.effectiveAt = clock.millis();
                room.revision++;
                broadcastPlaybackLocked(room, room.ownerUserId);
                return;
            }
            String nextEntry;
            if (MODE_SINGLE_REPEAT.equals(room.repeatMode)) {
                nextEntry = room.currentEntryId;
            } else if (MODE_SHUFFLE.equals(room.repeatMode) && entries.size() > 1) {
                int current = entries.indexOf(room.currentEntryId);
                int next;
                do {
                    next = ThreadLocalRandom.current().nextInt(entries.size());
                } while (next == current);
                nextEntry = entries.get(next);
            } else {
                int current = entries.indexOf(room.currentEntryId);
                nextEntry = entries.get((current + 1) % entries.size());
            }
            room.currentEntryId = nextEntry;
            room.positionMs = 0;
            room.effectiveAt = clock.millis();
            room.revision++;
            scheduleEndLocked(room);
            broadcastPlaylistLocked(room, room.ownerUserId);
            broadcastPlaybackLocked(room, room.ownerUserId);
        }
    }

    private void cancelEndTaskLocked(Room room) {
        if (room.endTask != null) {
            room.endTask.cancel(false);
            room.endTask = null;
        }
    }

    private long currentDurationMsLocked(Room room) {
        if (room.currentEntryId == null) {
            return 0;
        }
        PlaylistItem item = room.playlist.get(room.currentEntryId);
        return item == null ? 0 : Math.max(0L, item.track.duration() * 1000L);
    }

    private long positionAtLocked(Room room, long now) {
        if (!room.playing) {
            return room.positionMs;
        }
        long elapsed = Math.max(0L, now - room.effectiveAt);
        long durationMs = currentDurationMsLocked(room);
        long position = room.positionMs + elapsed;
        return durationMs > 0 ? Math.min(durationMs, position) : position;
    }

    private Map<String, Object> snapshotLocked(Room room) {
        long now = clock.millis();
        Map<String, Object> snapshot = baseEvent(room, "snapshot");
        snapshot.put("serverTime", now);
        snapshot.put("members", membersLocked(room));
        snapshot.put("playlist", playlistLocked(room));
        snapshot.put("playback", playbackLocked(room, now));
        return snapshot;
    }

    private Map<String, Object> baseEvent(Room room, String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("revision", room.revision);
        event.put("token", room.token);
        event.put("ownerUserId", room.ownerUserId);
        event.put("memberCount", room.members.size());
        event.put("maxMembers", MAX_MEMBERS_PER_ROOM);
        return event;
    }

    private List<Map<String, Object>> membersLocked(Room room) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Member member : room.members.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", member.userId);
            row.put("nickname", member.nickname);
            row.put("owner", member.userId == room.ownerUserId);
            row.put("online", room.connections.values().stream()
                    .anyMatch(connection -> connection.userId() == member.userId));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> playlistLocked(Room room) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlaylistItem item : room.playlist.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryId", item.entryId);
            row.put("track", item.track);
            row.put("addedByUserId", item.addedByUserId);
            row.put("addedAt", item.addedAt);
            items.add(row);
        }
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("revision", room.playlistRevision);
        playlist.put("items", items);
        playlist.put("currentEntryId", room.currentEntryId);
        playlist.put("repeatMode", room.repeatMode);
        return playlist;
    }

    private Map<String, Object> playbackLocked(Room room, long now) {
        Map<String, Object> playback = new LinkedHashMap<>();
        playback.put("currentEntryId", room.currentEntryId);
        playback.put("playing", room.playing);
        playback.put("positionMs", positionAtLocked(room, now));
        // 对外快照中的 positionMs 已经推进到 now；锚点必须同步改成 now，
        // 否则客户端会在此基础上再叠加一次 (now - 旧锚点)，加入房间时进度会翻倍偏移。
        playback.put("effectiveAt", now);
        playback.put("repeatMode", room.repeatMode);
        return playback;
    }

    private void broadcastRoom(Room room) {
        Map<String, Object> event;
        synchronized (room.lock) {
            if (rooms.get(room.token) != room) {
                return;
            }
            event = baseEvent(room, "room.updated");
            event.put("members", membersLocked(room));
        }
        broadcast(room, event);
    }

    private void broadcastPlaylistLocked(Room room, int actorUserId) {
        Map<String, Object> event = baseEvent(room, "playlist.updated");
        event.put("playlist", playlistLocked(room));
        event.put("actor", actor(room, actorUserId));
        broadcast(room, event);
    }

    private void broadcastPlaybackLocked(Room room, int actorUserId) {
        Map<String, Object> event = baseEvent(room, "playback.updated");
        long now = clock.millis();
        event.put("serverTime", now);
        event.put("playback", playbackLocked(room, now));
        event.put("actor", actor(room, actorUserId));
        broadcast(room, event);
    }

    private Map<String, Object> actor(Room room, int userId) {
        Member member = room.members.get(userId);
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("userId", userId);
        actor.put("nickname", member == null ? "听众" : member.nickname);
        return actor;
    }

    private void broadcast(Room room, Map<String, Object> event) {
        String json = toJson(event);
        for (ClientConnection connection : List.copyOf(room.connections.values())) {
            connection.send(json);
        }
    }

    private String toJson(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            logger.error("序列化一起听事件失败", e);
            return "{\"type\":\"error\",\"code\":\"serialization_failed\"}";
        }
    }

    private String issueTicketLocked(Room room, int userId) throws ListenTogetherException {
        for (int attempt = 0; attempt < WS_TICKET_ATTEMPTS; attempt++) {
            String ticket = randomToken(24);
            if (ticketStore.putIfAbsent(
                    TICKET_PREFIX + ticket,
                    room.token + ":" + userId,
                    WS_TICKET_TTL_SECONDS)) {
                return ticket;
            }
        }
        throw new ListenTogetherException(503, "ticket_unavailable", "listen.ticket.unavailable");
    }

    private Room requireRoom(String token) throws ListenTogetherException {
        if (token == null || token.isBlank()) {
            throw new ListenTogetherException(404, "room_not_found", "listen.not_found");
        }
        Room room = rooms.get(token);
        if (room == null) {
            throw new ListenTogetherException(404, "room_not_found", "listen.not_found");
        }
        return room;
    }

    private static void requireMember(Room room, int userId) throws ListenTogetherException {
        if (!room.members.containsKey(userId)) {
            throw new ListenTogetherException(403, "member_required", "listen.member_required");
        }
    }

    private void transferOwnerIfNeededLocked(Room room) {
        if (!room.members.containsKey(room.ownerUserId)) {
            room.ownerUserId = room.members.keySet().stream().findFirst().orElse(0);
            return;
        }
        boolean ownerOnline = room.connections.values().stream()
                .anyMatch(connection -> connection.userId() == room.ownerUserId);
        if (ownerOnline) {
            return;
        }
        Integer nextOwner = room.connections.values().stream()
                .map(ClientConnection::userId)
                .filter(userId -> room.members.containsKey(userId))
                .findFirst()
                .orElse(null);
        if (nextOwner != null) {
            room.ownerUserId = nextOwner;
        }
    }

    private String resolveNickname(int userId) {
        try {
            String nickname = nicknameResolver.apply(userId);
            return nickname == null || nickname.isBlank() ? "听众" : nickname;
        } catch (Exception e) {
            logger.warn("解析一起听成员昵称失败: userId={}, {}", userId, e.getMessage());
            return "听众";
        }
    }

    private void purgeIdleRooms() {
        long now = clock.millis();
        for (Map.Entry<String, Room> entry : List.copyOf(rooms.entrySet())) {
            Room room = entry.getValue();
            boolean remove = false;
            boolean changed = false;
            synchronized (room.lock) {
                Set<Integer> online = room.connections.values().stream()
                        .map(ClientConnection::userId)
                        .collect(java.util.stream.Collectors.toSet());
                List<Integer> staleMembers = room.members.values().stream()
                        .filter(member -> !online.contains(member.userId))
                        .filter(member -> now - member.lastSeenAt >= MEMBER_DISCONNECT_GRACE_MS)
                        .map(member -> member.userId)
                        .toList();
                for (Integer staleUserId : staleMembers) {
                    room.members.remove(staleUserId);
                    changed = true;
                }
                if (changed) {
                    transferOwnerIfNeededLocked(room);
                }
                if (room.connections.isEmpty() && now - room.lastActiveAt >= IDLE_TIMEOUT_MS) {
                    rooms.remove(entry.getKey(), room);
                    cancelEndTaskLocked(room);
                    remove = true;
                } else if (room.members.isEmpty()) {
                    rooms.remove(entry.getKey(), room);
                    cancelEndTaskLocked(room);
                    remove = true;
                }
            }
            if (remove) {
                logger.debug("清理空闲一起听房间: token={}", entry.getKey());
            } else if (changed) {
                broadcastRoom(room);
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "listen-together");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void close() {
        if (scheduler != null && ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}
