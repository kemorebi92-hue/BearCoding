package com.example.jingjingai2.DTO;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestTest {

    @Test
    void acceptsExplicitSystemModelFlag() throws Exception {
        ChatRequest request = new ObjectMapper().readValue(
                "{\"message\":\"hello\",\"useSystemModel\":true}",
                ChatRequest.class);

        assertTrue(request.isUseSystemModel());
    }

    @Test
    void acceptsWorkspaceIdOnFirstMessage() throws Exception {
        ChatRequest request = new ObjectMapper().readValue(
                "{\"message\":\"hello\",\"conversationId\":\"c1\",\"workspaceId\":42}",
                ChatRequest.class);

        assertEquals(42L, request.getWorkspaceId());
        assertEquals("c1", request.getConversationId());
    }
}
