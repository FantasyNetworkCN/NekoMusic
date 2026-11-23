package com.neko.music.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaAccessTicketServiceTest {

    @Test
    void ticketIsBoundToMusicAndRejectsTampering() {
        String ticket = MediaAccessTicketService.issue(7);

        assertTrue(MediaAccessTicketService.verify(7, ticket));
        assertFalse(MediaAccessTicketService.verify(8, ticket));
        assertFalse(MediaAccessTicketService.verify(7, ticket + "x"));
    }
}
