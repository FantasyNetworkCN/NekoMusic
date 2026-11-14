package com.neko.music.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListenTogetherServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class MemoryTickets implements ListenTogetherService.TicketStore {
        private final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public boolean putIfAbsent(String key, String value, int ttlSeconds) {
            return values.putIfAbsent(key, value) == null;
        }

        @Override
        public String take(String key) {
            return values.remove(key);
        }
    }

    private static final class Client implements ListenTogetherService.ClientConnection {
        private final String id;
        private final int userId;
        private final List<String> messages = new ArrayList<>();

        private Client(String id, int userId) {
            this.id = id;
            this.userId = userId;
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
            messages.add(json);
        }

        @Override
        public void close(String messageKey) {
        }
    }

    private static ListenTogetherService newService(MemoryTickets tickets) {
        return newService(tickets, CLOCK);
    }

    private static ListenTogetherService newService(MemoryTickets tickets, Clock clock) {
        ObjectMapper mapper = new ObjectMapper();
        return new ListenTogetherService(
                tickets,
                id -> Optional.of(new ListenTogetherService.Track(
                        id, "Track " + id, "Artist", "Album", 180, "hq")),
                id -> "用户" + id,
                mapper,
                clock,
                null,
                false);
    }

    @Test
    void roomIsEmptyAndCapsAtTenUniqueUsers() throws Exception {
        ListenTogetherService service = newService(new MemoryTickets());
        String token = service.createRoom(1);
        ListenTogetherService.JoinResult first = service.joinRoom(token, 1);
        assertTrue(((List<?>) first.snapshot().get("members")).size() == 1);
        assertTrue(((List<?>) ((Map<?, ?>) first.snapshot().get("playlist")).get("items")).isEmpty());

        for (int userId = 2; userId <= ListenTogetherService.MAX_MEMBERS_PER_ROOM; userId++) {
            service.joinRoom(token, userId);
        }
        ListenTogetherService.ListenTogetherException full = assertThrows(
                ListenTogetherService.ListenTogetherException.class,
                () -> service.joinRoom(token, 11));
        assertEquals(409, full.status());
        assertEquals("room_full", full.code());

        // 已在房间内的用户重连不占新名额。
        assertNotNull(service.joinRoom(token, 1).ticket());
    }

    @Test
    void anyMemberCanAddAndControlPlayback() throws Exception {
        ListenTogetherService service = newService(new MemoryTickets());
        String token = service.createRoom(1);
        service.joinRoom(token, 2);

        Client client = new Client("c1", 2);
        service.attach(token, 2, client);
        ObjectMapper mapper = new ObjectMapper();
        service.handleControl(token, 2, mapper.readTree("""
                {"type":"control","action":"playlist.add","opId":"add-1","trackId":42}
                """));

        Map<String, Object> snapshot = service.currentSnapshot(token, 1);
        Map<?, ?> playlist = (Map<?, ?>) snapshot.get("playlist");
        List<?> items = (List<?>) playlist.get("items");
        assertEquals(1, items.size());
        String entryId = String.valueOf(((Map<?, ?>) items.get(0)).get("entryId"));

        service.handleControl(token, 2, mapper.readTree("""
                {"type":"control","action":"playback.play","opId":"play-1","entryId":"%s"}
                """.formatted(entryId)));
        Map<?, ?> playback = (Map<?, ?>) service.currentSnapshot(token, 1).get("playback");
        assertEquals(true, playback.get("playing"));
        assertEquals(entryId, playback.get("currentEntryId"));
    }

    @Test
    void removingCurrentItemAdvancesToTheNextItem() throws Exception {
        ListenTogetherService service = newService(new MemoryTickets());
        String token = service.createRoom(1);
        ObjectMapper mapper = new ObjectMapper();
        service.handleControl(token, 1, mapper.readTree("""
                {"type":"control","action":"playlist.add","opId":"add-1","trackId":1}
                """));
        service.handleControl(token, 1, mapper.readTree("""
                {"type":"control","action":"playlist.add","opId":"add-2","trackId":2}
                """));

        Map<?, ?> playlist = (Map<?, ?>) service.currentSnapshot(token, 1).get("playlist");
        List<?> items = (List<?>) playlist.get("items");
        String firstEntry = String.valueOf(((Map<?, ?>) items.get(0)).get("entryId"));
        String secondEntry = String.valueOf(((Map<?, ?>) items.get(1)).get("entryId"));

        service.handleControl(token, 1, mapper.readTree("""
                {"type":"control","action":"playlist.remove","opId":"remove-1","entryId":"%s",
                 "playlistRevision":%d}
                """.formatted(firstEntry, ((Number) playlist.get("revision")).longValue())));

        Map<?, ?> after = (Map<?, ?>) service.currentSnapshot(token, 1).get("playback");
        assertEquals(secondEntry, after.get("currentEntryId"));
    }

    @Test
    void websocketTicketIsConsumedOnce() throws Exception {
        ListenTogetherService service = newService(new MemoryTickets());
        String token = service.createRoom(1);
        String ticket = service.joinRoom(token, 1).ticket();

        ListenTogetherService.TicketIdentity identity = service.consumeTicket(ticket);
        assertEquals(token, identity.roomToken());
        assertEquals(1, identity.userId());

        ListenTogetherService.ListenTogetherException reused = assertThrows(
                ListenTogetherService.ListenTogetherException.class,
                () -> service.consumeTicket(ticket));
        assertEquals("invalid_ticket", reused.code());
    }

    @Test
    void snapshotAnchorDoesNotDoubleCountElapsedTime() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T00:00:00Z"));
        ListenTogetherService service = newService(new MemoryTickets(), clock);
        String token = service.createRoom(1);
        ObjectMapper mapper = new ObjectMapper();
        service.handleControl(token, 1, mapper.readTree("""
                {"type":"control","action":"playlist.add","opId":"add-1","trackId":1}
                """));
        service.handleControl(token, 1, mapper.readTree("""
                {"type":"control","action":"playback.play","opId":"play-1"}
                """));

        clock.advanceSeconds(10);
        Map<?, ?> playback = (Map<?, ?>) service.currentSnapshot(token, 1).get("playback");
        long positionMs = ((Number) playback.get("positionMs")).longValue();
        long effectiveAt = ((Number) playback.get("effectiveAt")).longValue();
        long serverNow = clock.millis();

        assertEquals(10_000L, positionMs);
        assertEquals(serverNow, effectiveAt);
        assertEquals(10_000L, positionMs + (serverNow - effectiveAt));
    }
}
