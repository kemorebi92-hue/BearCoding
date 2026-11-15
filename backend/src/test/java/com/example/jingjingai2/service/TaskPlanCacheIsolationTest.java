package com.example.jingjingai2.service;

import com.example.jingjingai2.DTO.PlanStep;
import com.example.jingjingai2.DTO.StepInput;
import com.example.jingjingai2.DTO.TaskPlan;
import com.example.jingjingai2.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskPlanService 缓存必须按 userId+conversationId 隔离。
 * 不连数据库：Mapper 用 mock，计划正文走内存缓存路径。
 */
class TaskPlanCacheIsolationTest {

    private static final String SAME_CONV = "shared-conv-id";

    private ConversationMapper mapper;
    private TaskPlanService service;
    /** 模拟 DB：userId|conversationId → plan_json 原文是否存在由 create/persist 写入次数验证 */
    private final ConcurrentHashMap<String, String> db = new ConcurrentHashMap<>();
    private final AtomicInteger selectCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationMapper.class);
        when(mapper.selectPlanJson(anyLong(), anyString())).thenAnswer(inv -> {
            selectCount.incrementAndGet();
            Long userId = inv.getArgument(0);
            String conv = inv.getArgument(1);
            return db.get(userId + "\0" + conv);
        });
        when(mapper.updatePlanJson(anyLong(), anyString(), any())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            String conv = inv.getArgument(1);
            String json = inv.getArgument(2);
            String key = userId + "\0" + conv;
            if (json == null) db.remove(key);
            else db.put(key, json);
            return 1;
        });
        // chatModel 在 create/get 正常路径上不会被用到
        service = new TaskPlanService(mapper, null);
    }

    @Test
    @DisplayName("相同 conversationId、不同用户：各自读到自己的计划，互不串读")
    void sameConversationIdDifferentUsersAreIsolated() {
        TaskPlan planA = service.create(1L, SAME_CONV, List.of(step("A 的步骤")));
        TaskPlan planB = service.create(2L, SAME_CONV, List.of(step("B 的步骤")));
        assertNotNull(planA);
        assertNotNull(planB);
        assertNotEquals(planA.getPlanId(), planB.getPlanId());

        TaskPlan readA = service.get(1L, SAME_CONV);
        TaskPlan readB = service.get(2L, SAME_CONV);
        assertSame(planA, readA, "用户 A 应命中自己的缓存对象");
        assertSame(planB, readB, "用户 B 应命中自己的缓存对象");
        assertEquals("A 的步骤", readA.getSteps().get(0).getDescription());
        assertEquals("B 的步骤", readB.getSteps().get(0).getDescription());
    }

    @Test
    @DisplayName("同一用户同一会话重复 get 命中缓存，不再查库")
    void sameUserSameConversationHitsCache() {
        service.create(7L, "c1", List.of(step("一步")));
        int afterCreate = selectCount.get();
        TaskPlan first = service.get(7L, "c1");
        TaskPlan second = service.get(7L, "c1");
        assertSame(first, second);
        assertEquals(afterCreate, selectCount.get(), "缓存命中不应再 selectPlanJson");
    }

    @Test
    @DisplayName("清理用户 A 的缓存不影响用户 B 的同 conversationId 计划")
    void clearOneUserDoesNotAffectTheOther() {
        service.create(1L, SAME_CONV, List.of(step("A")));
        service.create(2L, SAME_CONV, List.of(step("B")));

        service.clear(1L, SAME_CONV);

        // A 的缓存没了，会回源 DB（create 时已 persist）
        TaskPlan reloadedA = service.get(1L, SAME_CONV);
        assertNotNull(reloadedA);
        assertEquals("A", reloadedA.getSteps().get(0).getDescription());

        TaskPlan stillB = service.get(2L, SAME_CONV);
        assertEquals("B", stillB.getSteps().get(0).getDescription());
        // B 仍是创建时放进缓存的同一对象（未被 clear 掉）
        assertTrue(stillB.getSteps().get(0).getDescription().equals("B"));
    }

    @Test
    @DisplayName("resetIfFinished 只清当前用户缓存，另一用户同 conversationId 仍在")
    void resetIfFinishedIsPerUser() {
        TaskPlan planA = service.create(1L, SAME_CONV, List.of(step("A")));
        service.create(2L, SAME_CONV, List.of(step("B")));
        planA.getSteps().get(0).setStatus(PlanStep.DONE);

        assertFalse(service.resetIfFinished(1L, SAME_CONV), "DONE without delivery must be retained");
        service.noteDeliveryReturned(1L, SAME_CONV);
        assertTrue(service.resetIfFinished(1L, SAME_CONV));
        assertNull(service.get(1L, SAME_CONV), "A 的计划已清空（缓存+DB）");
        assertNotNull(service.get(2L, SAME_CONV), "B 不受影响");
        assertEquals("B", service.get(2L, SAME_CONV).getSteps().get(0).getDescription());
    }

    @Test
    @DisplayName("不同 conversationId 仍然隔离")
    void differentConversationIdsStayIsolated() {
        service.create(1L, "c-a", List.of(step("会话A")));
        service.create(1L, "c-b", List.of(step("会话B")));
        assertEquals("会话A", service.get(1L, "c-a").getSteps().get(0).getDescription());
        assertEquals("会话B", service.get(1L, "c-b").getSteps().get(0).getDescription());
    }

    @Test
    @DisplayName("cacheKey 必须同时包含 userId 与 conversationId")
    void cacheKeyIncludesBothIdentities() {
        assertEquals("1\0c", TaskPlanService.cacheKey(1L, "c"));
        assertEquals("2\0c", TaskPlanService.cacheKey(2L, "c"));
        assertNotEquals(TaskPlanService.cacheKey(1L, "c"), TaskPlanService.cacheKey(2L, "c"));
        assertNull(TaskPlanService.cacheKey(null, "c"));
        assertNull(TaskPlanService.cacheKey(1L, null));
        assertNull(TaskPlanService.cacheKey(1L, "  "));
    }

    private static StepInput step(String description) {
        StepInput in = new StepInput();
        in.setDescription(description);
        in.setExpected("ok");
        return in;
    }
}
