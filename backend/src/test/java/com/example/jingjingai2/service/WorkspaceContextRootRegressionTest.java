package com.example.jingjingai2.service;

import com.example.jingjingai2.sandbox.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归：ChatController 把 resolveRoot 的结果 set 进 WorkspaceContext 之后，
 * 未绑定会话仍是 {root}/{uid}/{convId}，已绑定会话仍是真实目录。
 * 期望值按改动前 WorkspaceService.currentRoot 的语义钉死。
 */
class WorkspaceContextRootRegressionTest {

    private static final Long USER = 7L;

    @TempDir Path tmp;

    @AfterEach
    void clear() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("未绑定会话：WorkspaceContext 根 = 该会话独立沙箱")
    void unboundUsesOwnSandbox() {
        FakeWorkspaceMapper wsMapper = new FakeWorkspaceMapper();
        FakeConversationMapper convMapper = new FakeConversationMapper(wsMapper);
        WorkspaceService service = new WorkspaceService(tmp.toString(), wsMapper, convMapper);
        convMapper.touch(USER, "unbound-conv", "hi", null, ConversationService.MODE_ASK);

        Path expected = tmp.resolve("7").resolve("unbound-conv").toAbsolutePath().normalize();
        Path root = service.currentRoot(USER, "unbound-conv");
        WorkspaceContext.set(root);

        assertEquals(expected, WorkspaceContext.get());
        assertEquals(expected, service.workspaceFor(USER, "unbound-conv"));
        assertTrue(Files.isDirectory(WorkspaceContext.get()));
    }

    @Test
    @DisplayName("已绑定会话：WorkspaceContext 根 = 绑定的真实目录")
    void boundUsesRealDirectory() throws Exception {
        FakeWorkspaceMapper wsMapper = new FakeWorkspaceMapper();
        FakeConversationMapper convMapper = new FakeConversationMapper(wsMapper);
        WorkspaceService service = new WorkspaceService(tmp.toString(), wsMapper, convMapper);
        Path real = Files.createDirectories(tmp.resolve("real-project"));
        convMapper.touch(USER, "bound-conv", "hi", null, ConversationService.MODE_ASK);
        service.bindConversation(USER, "bound-conv", real.toString());

        Path expected = real.toAbsolutePath().normalize();
        Path root = service.currentRoot(USER, "bound-conv");
        WorkspaceContext.set(root);

        assertEquals(expected, WorkspaceContext.get());
        assertNotEquals(service.workspaceFor(USER, "bound-conv"), WorkspaceContext.get());
    }
}
