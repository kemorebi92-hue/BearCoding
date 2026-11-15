package com.example.jingjingai2.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** TaskTopicIdentifier 的边界：大小写、空白、单字、空输入、重复命中。 */
class TaskTopicIdentifierTest {

    @Test
    @DisplayName("大小写不敏感，命中词按词表里的写法返回")
    void caseInsensitive() {
        TaskTopicIdentifier.Identification id =
                TaskTopicIdentifier.identify("看看 Billing 模块", List.of("billing", "deploy"));
        assertEquals(TaskTopicIdentifier.Kind.IDENTIFIED, id.kind());
        assertEquals(List.of("billing"), id.topics());
    }

    @Test
    @DisplayName("同一个词命中多次只算一次")
    void repeatedHitCountsOnce() {
        TaskTopicIdentifier.Identification id =
                TaskTopicIdentifier.identify("账单账单账单", List.of("账单"));
        assertEquals(TaskTopicIdentifier.Kind.IDENTIFIED, id.kind());
        assertEquals(1, id.topics().size());
    }

    @Test
    @DisplayName("空白和 null 一律未知，不抛异常")
    void blankInputs() {
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify(null, List.of("账单")).kind());
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify("账单", null).kind());
        assertEquals(TaskTopicIdentifier.Kind.UNKNOWN,
                TaskTopicIdentifier.identify("账单", List.of(" ", "账")).kind());
    }

    @Test
    @DisplayName("词表里的空词和单字词被跳过，不把整句判成命中")
    void shortTermsIgnored() {
        TaskTopicIdentifier.Identification id =
                TaskTopicIdentifier.identify("部署流程", List.of("", "部", "部署"));
        assertEquals(TaskTopicIdentifier.Kind.IDENTIFIED, id.kind());
        assertEquals(List.of("部署"), id.topics());
    }

    @Test
    @DisplayName("三个词同时命中仍是歧义，不取第一个")
    void threeWayAmbiguity() {
        TaskTopicIdentifier.Identification id =
                TaskTopicIdentifier.identify("账单、部署和日志一起看", List.of("日志", "账单", "部署"));
        assertEquals(TaskTopicIdentifier.Kind.AMBIGUOUS, id.kind());
        assertEquals(3, id.topics().size());
        assertTrue(id.toContext(1L, "shop").topics.isEmpty());
    }
}
