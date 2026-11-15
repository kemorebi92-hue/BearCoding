package com.example.jingjingai2.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class HarnessReplySelectionTest {

    @Test
    @DisplayName("预置了助手消息、这次没有新消息，回复是 null")
    void seededAssistantWithoutNewMessageIsNull() {
        List<HarnessCli.ReplyRow> rows = List.of(
                new HarnessCli.ReplyRow(1, "user", "请记住"),
                new HarnessCli.ReplyRow(2, "assistant", "已核对单号 SO2028"));
        assertNull(HarnessCli.selectReply(rows, 2L));
    }

    @Test
    @DisplayName("这次有新的助手消息，导出最新那条")
    void newAssistantMessageIsExported() {
        List<HarnessCli.ReplyRow> rows = List.of(
                new HarnessCli.ReplyRow(2, "assistant", "已核对单号 SO2028"),
                new HarnessCli.ReplyRow(8, "user", "把两个标记都改成 LATEST"),
                new HarnessCli.ReplyRow(9, "assistant", "先看一下"),
                new HarnessCli.ReplyRow(15, "assistant", "CHANGE.txt 已改成 LATEST"));
        assertEquals("CHANGE.txt 已改成 LATEST", HarnessCli.selectReply(rows, 2L));
    }

    @Test
    @DisplayName("T06：回复是 null 时 replyContains 不通过")
    void replyNullDoesNotPass(@TempDir Path tmp) throws Exception {
        Path workspace = tmp.resolve("t06");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("marker.txt"),
                Files.readString(Path.of("docs/eval/fixtures/T06/marker.txt")));
        var checks = Check.load(Path.of("docs/eval/tasks/T06.expect.json"));
        JudgeResult result = ExpectationJudge.judge(workspace, checks,
                new JudgeContext(List.of(), List.of(), List.of(), null));
        assertEquals(JudgeResult.Outcome.FAIL, result.outcome());
        boolean replyFailed = false;
        for (JudgeResult.Item item : result.items()) {
            if ("replyContains".equals(item.check().type()) && !item.passed()) replyFailed = true;
        }
        assertFalse(result.passed());
        assertEquals(true, replyFailed);
    }
}
