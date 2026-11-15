package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.mcp.McpClientManager;
import com.example.jingjingai2.mcp.McpUntrustedGuard;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 三个最小冒烟场景：组件级组合，不连 DB、不调真实模型。
 * 只证明本轮修复的链路，不冒充 HTTP/SSE 或真实模型验收。
 */
class MinimalFixSmokeTest {

    @Test
    @DisplayName("场景一：历史工具结果省略 → 不可信包装 → 签名引用仍可恢复")
    void historyOmissionMarkerSurvivesUntrustedWrap() throws Exception {
        Path file = Files.createTempFile("smoke-history", ".txt");
        String body = "BODY-" + "x".repeat(500);
        Files.writeString(file, body);
        String marker = ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));

        List<Message> messages = new ArrayList<>();
        String payload = "BODY-" + "x".repeat(500) + "\n" + marker;
        ToolArtifactRef.Ref check = ToolArtifactRef.parse(payload);
        assertNotNull(check, payload);
        assertEquals(sha256(body), check.sha256(), check.toString());
        assertTrue(Files.isRegularFile(Path.of(check.path())), check.path());
        messages.add(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("c1", "function", "mcp__playwright__browser_snapshot", "{}"))));
        messages.add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(
                "c1", "mcp__playwright__browser_snapshot", payload))));
        messages.add(new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("c2", "function", "readFile", "{}"))));
        messages.add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(
                "c2", "readFile", "small"))));

        assertTrue(ToolHistoryCompactor.compactInPlace(messages, 10) > 0);

        McpClientManager manager = mock(McpClientManager.class);
        when(manager.isEnabled()).thenReturn(true);
        when(manager.isUntrustedExposed(anyLong(), anyString())).thenReturn(true);
        McpUntrustedGuard.wrapUntrusted(messages, "conv", manager, 0L);

        String data = ((ToolResponseMessage) messages.get(1)).getResponses().get(0).responseData();
        assertTrue(ToolArtifactRef.isOmissionMarker(data), data);
        assertFalse(data.startsWith(McpUntrustedGuard.UNTRUSTED_PREFIX), data);
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(data);
        assertNotNull(ref, data);
        assertEquals(file.toString(), ref.path());
        assertEquals(body.length(), ref.chars());
    }

    @Test
    @DisplayName("场景二：total + summary 摘要后保留合法签名引用")
    void totalSummaryKeepsSignedRef() throws Exception {
        Path file = Files.createTempFile("smoke-summary", ".txt");
        String body = "BODY-" + "x".repeat(12000);
        Files.writeString(file, body);
        String marker = ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
        AssistantMessage call = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("c1", "function", "readFile", "{}")));
        ToolResponseMessage result = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("c1", "readFile", body + "\n" + marker)));
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("系统"), call, result, new UserMessage("最新")));

        RoundCompactor.Outcome out = RoundCompactor.apply(
                messages, null, 0, 2000, 3, 0.30, 1, new RoundCompactor.Guard(),
                older -> new RoundCompactor.Summary("SUMMARY-BODY", 10, 5, false, false), "summary");

        assertTrue(out.compacted());
        String summary = out.summary().text();
        assertTrue(summary.contains("SUMMARY-BODY"), summary);
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(summary);
        assertNotNull(ref, summary);
        assertEquals(file.toString(), ref.path());
        assertEquals(body.length(), ref.chars());
        assertFalse(summary.contains("BODY-"), "不能重新注入完整工具输出");
        assertTrue(MessageProtocolValidator.check(messages).ok());
    }

    @Test
    @DisplayName("场景三：默认 history 口径未超预算时不压缩，行为不变")
    void defaultHistoryUnderBudgetIsUnchanged() {
        ContextCompactor compactor = new ContextCompactor(null, 24000);
        List<Message> messages = compactor.buildMessages(
                List.of(new MessageRecord("user", "短问题")), "短回答");
        assertFalse(compactor.lastStats().compacted());
        assertTrue(messages.stream().noneMatch(m -> m instanceof SystemMessage
                && m.getText() != null && m.getText().startsWith("[历史摘要]")));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
