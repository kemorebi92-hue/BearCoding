package com.example.jingjingai2.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerSpecTest {

    @Test
    @DisplayName("connect-timeout-seconds / max-result-chars ≤0 回落全局，>0 用 server 级")
    void serverLevelOverridesGlobal() {
        McpProperties props = new McpProperties();
        props.setInitializeTimeoutMs(15_000);
        props.setMaxResultChars(8000);
        McpServerSpec spec = new McpServerSpec();
        assertEquals(15_000L, spec.initializeTimeoutMs(props));
        assertEquals(8000, spec.effectiveMaxResultChars(props));
        spec.setConnectTimeoutSeconds(60);
        spec.setMaxResultChars(6000);
        assertEquals(60_000L, spec.initializeTimeoutMs(props));
        assertEquals(6000, spec.effectiveMaxResultChars(props));
        spec.setConnectTimeoutSeconds(0);
        spec.setMaxResultChars(0);
        assertEquals(15_000L, spec.initializeTimeoutMs(props));
        assertEquals(8000, spec.effectiveMaxResultChars(props));
    }

    @Test
    @DisplayName("trust 默认 untrusted；prune min 默认 500；url-policy 默认 default")
    void hardeningDefaults() {
        McpServerSpec spec = new McpServerSpec();
        assertTrue(spec.isUntrusted());
        spec.setTrust("trusted");
        assertFalse(spec.isUntrusted());
        spec.setTrust("");
        assertTrue(spec.isUntrusted());
        assertEquals(500, spec.effectiveHistoryPruneMinChars());
        spec.setHistoryPruneMinChars(800);
        assertEquals(800, spec.effectiveHistoryPruneMinChars());
        assertFalse(spec.getUrlPolicy().isStrict());
        spec.getUrlPolicy().setMode("strict");
        assertTrue(spec.getUrlPolicy().isStrict());
    }

    @Test
    @DisplayName("props 为 null 时仍有默认 15s / 8000")
    void nullPropsDefaults() {
        McpServerSpec spec = new McpServerSpec();
        assertEquals(15_000L, spec.initializeTimeoutMs(null));
        assertEquals(8000, spec.effectiveMaxResultChars(null));
    }
}
