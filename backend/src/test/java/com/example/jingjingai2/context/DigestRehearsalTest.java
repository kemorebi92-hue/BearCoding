package com.example.jingjingai2.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不调用真实模型。T11 用假模型按真实文件大小走完 6 次读取；T04 只对预置历史做一次 digest。
 */
class DigestRehearsalTest {

    private static final int THRESHOLD = 20000;
    private static final int FIXED = 9270;
    private static final Path T11 = Path.of("docs/eval/fixtures/T11");
    private static final Path T04_HISTORY = Path.of("docs/eval/fixtures/T04/messages.jsonl");
    private static final Path T04_PROMPT = Path.of("docs/eval/tasks/T04.prompt.txt");
    private static final Path T11_PROMPT = Path.of("docs/eval/tasks/T11.prompt.txt");

    @Test
    @DisplayName("T11 假模型：summary 和 digest 都在读文件中途压到阈值的 90% 以下")
    void t11BothStrategiesCompactMidLoop() throws Exception {
        for (String strategy : List.of("summary", "digest")) {
            Rehearsal result = runT11(strategy);
            System.out.println("REHEARSAL T11 " + strategy
                    + " compactRound=" + result.compactRound
                    + " filesAlreadyRead=" + result.filesAlreadyRead
                    + " before=" + result.before
                    + " after=" + result.after
                    + " reason=" + result.reason);
            assertTrue(result.compactRound > 0, strategy + " 压缩发生在第 0 轮");
            assertTrue(result.after <= 18000, strategy + " 压缩后 " + result.after + " 超过 18000");
            assertNotEquals("COMPACTION_INEFFECTIVE", result.reason, strategy);
            assertFalse(result.sawIneffective, strategy + " 出现了压不下去");
            assertTrue(result.paired, strategy + " 有孤立的工具结果");
            if ("digest".equals(strategy)) {
                assertFalse(result.text.contains("CODE-PART-B: VIOLET-5521"), "早期口令还在原文里");
                assertTrue(result.text.contains("[已清除的工具结果："));
            }
        }
    }

    @Test
    @DisplayName("tiered：工具为主走 digest，T04 历史走摘要，组不拆开")
    void tieredPicksDigestForToolsAndSummaryForHistory() throws Exception {
        Rehearsal tools = runT11("tiered");
        assertTrue(tools.compactRound > 0);
        assertTrue(tools.after <= 18000, "after=" + tools.after);
        assertTrue(tools.paired);
        assertFalse(tools.sawIneffective);
        assertTrue(tools.text.contains("[压缩索引]"));

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("固".repeat(FIXED)));
        ObjectMapper mapper = new ObjectMapper();
        for (String line : Files.readAllLines(T04_HISTORY)) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            String role = node.get("role").asText();
            String content = node.get("content").asText();
            history.add("user".equals(role) ? new UserMessage(content) : new AssistantMessage(content));
        }
        history.add(new UserMessage(Files.readString(T04_PROMPT).strip()));
        int[] calls = {0};
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                history, null, 0, THRESHOLD, 3, 0.30, 1000, new RoundCompactor.Guard(),
                body -> {
                    calls[0]++;
                    return new RoundCompactor.Summary("固定摘要", 1, 1, false, false);
                },
                "tiered", 0, 0.9);
        assertEquals(1, calls[0]);
        assertEquals("SUMMARY", outcome.compactMode());
        assertTrue(outcome.after() <= (int) (THRESHOLD * 0.9), "after=" + outcome.after());
        assertTrue(joined(history).contains("固定摘要"));
    }

    @Test
    @DisplayName("T04 预置历史离线 digest：只观察约束还在不在")
    void t04HistoryDigestIsObservable() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("固".repeat(FIXED)));
        ObjectMapper mapper = new ObjectMapper();
        String firstUser = null;
        int firstUserChars = 0;
        int index = 0;
        for (String line : Files.readAllLines(T04_HISTORY)) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            String role = node.get("role").asText();
            String content = node.get("content").asText();
            if (index == 0) {
                firstUser = content;
                firstUserChars = content.length();
            }
            messages.add("user".equals(role) ? new UserMessage(content) : new AssistantMessage(content));
            index++;
        }
        String prompt = Files.readString(T04_PROMPT).strip();
        messages.add(new UserMessage(prompt));
        int before = RoundCompactor.estimate(messages, null);
        RoundCompactor.Outcome outcome = RoundCompactor.apply(
                messages, null, 0, THRESHOLD, 3, 0.30, 1000, new RoundCompactor.Guard(),
                body -> new RoundCompactor.Summary("固定摘要", 1, 1, false, false),
                "digest", 0);
        int after = outcome.after();
        String text = joined(messages);
        boolean constraintIntact = firstUser != null && text.contains(firstUser);
        System.out.println("REHEARSAL T04 before=" + before
                + " after=" + after
                + " messages=" + messages.size()
                + " firstUserChars=" + firstUserChars
                + " constraintIntact=" + constraintIntact
                + " phrasePresent=" + text.contains("不要改 KEEP.txt")
                + " compacted=" + outcome.compacted()
                + " reason=" + outcome.decision().reason());
        assertTrue(outcome.compacted(), "预置历史没有触发 digest");
    }

    private static Rehearsal runT11(String strategy) throws Exception {
        String prompt = Files.readString(T11_PROMPT).strip();
        List<String> files = new ArrayList<>();
        for (int i = 1; i <= 6; i++) files.add(Files.readString(T11.resolve("part" + i + ".txt")));
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("固".repeat(FIXED)));
        messages.add(new UserMessage(prompt));
        RoundCompactor.Guard guard = new RoundCompactor.Guard();
        Rehearsal rehearsal = new Rehearsal();
        int read = 0;
        for (int round = 0; round < 12; round++) {
            int before = RoundCompactor.estimate(messages, null);
            RoundCompactor.Outcome outcome = RoundCompactor.apply(
                    messages, null, round, THRESHOLD, 3, 0.30, 1000, guard,
                    body -> new RoundCompactor.Summary("固定摘要", 1, 1, false, false),
                    strategy, 0);
            if ("COMPACTION_INEFFECTIVE".equals(outcome.decision().reason())) rehearsal.sawIneffective = true;
            if (rehearsal.compactRound < 0 && outcome.compacted()) {
                rehearsal.compactRound = round;
                rehearsal.filesAlreadyRead = read;
                rehearsal.before = before;
                rehearsal.after = outcome.after();
                rehearsal.reason = outcome.decision().reason();
                rehearsal.text = joined(messages);
                rehearsal.paired = toolResultsStayWithCalls(messages);
            }
            if (read == files.size()) break;
            int n = read + 1;
            messages.add(new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall("c" + n, "function", "readFile",
                            "{\"path\":\"part" + n + ".txt\"}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("c" + n, "readFile", files.get(read)))));
            read++;
        }
        assertNotNull(rehearsal.text, strategy + " 没有发生压缩");
        return rehearsal;
    }

    private static boolean toolResultsStayWithCalls(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                if (i == 0 || !(messages.get(i - 1) instanceof AssistantMessage assistant)
                        || assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String joined(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message.getText() != null) sb.append(message.getText()).append('\n');
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    sb.append(response.responseData()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static final class Rehearsal {
        int compactRound = -1;
        int filesAlreadyRead;
        int before;
        int after;
        String reason;
        String text;
        boolean paired;
        boolean sawIneffective;
    }
}
