package com.example.jingjingai2.eval;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextCompactor;
import com.example.jingjingai2.context.TokenEstimator;
import com.example.jingjingai2.mapper.ConversationMapper;
import com.example.jingjingai2.service.ConversationHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04 的预置历史要能在当前产品上触发压缩。截断走 {@link ConversationHistoryService}，
 * 估算走 {@link ContextCompactor}，不另写一套算法。
 */
class T04HistoryWindowTest {

    @Test
    @DisplayName("截断后约束还在，判断值高于预算")
    void presetFitsWindowAndTriggersCompaction() throws Exception {
        int maxTokens = configuredMaxTokens();
        String prompt = Files.readString(Path.of("docs/eval/tasks/T04.prompt.txt")).strip();
        List<MessageRecord> seeded = readSeed();
        List<MessageRecord> stored = new ArrayList<>(seeded);
        stored.add(new MessageRecord("user", prompt));

        int[] seenLimit = new int[1];
        ConversationMapper mapper = (ConversationMapper) Proxy.newProxyInstance(
                ConversationMapper.class.getClassLoader(),
                new Class[]{ConversationMapper.class},
                (proxy, method, args) -> {
                    if ("getHistory".equals(method.getName())) {
                        int limit = (Integer) args[2];
                        seenLimit[0] = limit;
                        List<MessageRecord> newestFirst = new ArrayList<>(stored);
                        java.util.Collections.reverse(newestFirst);
                        if (newestFirst.size() > limit) {
                            return new ArrayList<>(newestFirst.subList(0, limit));
                        }
                        return newestFirst;
                    }
                    if ("toString".equals(method.getName())) return "fixture-mapper";
                    throw new UnsupportedOperationException(method.getName());
                });

        List<MessageRecord> history = new ConversationHistoryService(mapper).getHistory(1L, "t04");
        assertTrue(seenLimit[0] > 0, "没走到真实的截断查询");
        assertEquals(seeded.get(0).getContent(), history.get(0).getContent(), "约束消息不在窗口最旧的位置");
        assertTrue(history.get(0).getContent().contains("不要改 KEEP.txt"));

        ContextCompactor compactor = new ContextCompactor(null, maxTokens);
        compactor.buildMessages(history, prompt);
        ContextCompactor.CompactStats stats = compactor.lastStats();
        List<MessageRecord> counted = history;
        if (!counted.isEmpty() && "user".equals(counted.get(counted.size() - 1).getRole())) {
            counted = counted.subList(0, counted.size() - 1);
        }
        int expected = TokenEstimator.estimate(prompt);
        for (MessageRecord record : counted) expected += TokenEstimator.estimate(record.getContent());
        assertEquals(expected, stats.tokensBeforeEst(), "末尾已入库的本轮 user 不和当前消息重复计算");
        assertTrue(stats.tokensBeforeEst() > maxTokens, "判断值 " + stats.tokensBeforeEst());
        assertFalse(stats.compacted(), "没有摘要模型时超预算也不把失败记成已压缩");
    }

    private static int configuredMaxTokens() throws Exception {
        for (String line : Files.readAllLines(Path.of("src/main/resources/application.yml"))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("max-tokens:")) {
                return Integer.parseInt(trimmed.substring("max-tokens:".length()).trim());
            }
        }
        throw new IllegalStateException("application.yml 没有 max-tokens");
    }

    private static List<MessageRecord> readSeed() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<MessageRecord> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("docs/eval/fixtures/T04/messages.jsonl"))) {
            if (line.isBlank()) continue;
            var node = json.readTree(line);
            rows.add(new MessageRecord(node.get("role").asText(), node.get("content").asText()));
        }
        return rows;
    }
}
