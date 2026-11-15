package com.example.jingjingai2.DTO;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsSnakeCaseFieldsFromFrontend() throws Exception {
        String json = """
                {
                  "provider": "小米",
                  "base_url": "https://example.com/v1",
                  "api_key": "secret",
                  "model_name": "mi-chat",
                  "default_model": true
                }
                """;

        ModelConfigRequest request = objectMapper.readValue(json, ModelConfigRequest.class);

        assertEquals("小米", request.getProvider());
        assertEquals("https://example.com/v1", request.getBaseUrl());
        assertEquals("secret", request.getApiKey());
        assertEquals("mi-chat", request.getModelName());
        assertTrue(request.isDefaultModel());
    }
}
