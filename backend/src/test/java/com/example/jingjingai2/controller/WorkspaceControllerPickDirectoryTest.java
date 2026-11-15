package com.example.jingjingai2.controller;

import com.example.jingjingai2.service.DirectoryPickerService;
import com.example.jingjingai2.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * /ai/workspace/pick-directory 的 HTTP 约定：本机才允许、取消/错误不伪装成路径。
 */
class WorkspaceControllerPickDirectoryTest {

    private DirectoryPickerService picker;
    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        picker = mock(DirectoryPickerService.class);
        controller = new WorkspaceController(mock(WorkspaceService.class), picker);
    }

    private static MockHttpServletRequest request(Long userId, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (userId != null) req.setAttribute("userId", userId);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("本机选中目录：返回 path")
    void returnsSelectedPath() {
        when(picker.pick(any(Path.class))).thenReturn("D:\\java\\practice\\proj");
        Map<String, Object> res = controller.pickDirectory(request(1L, "127.0.0.1"));
        assertEquals("D:\\java\\practice\\proj", res.get("path"));
        assertFalse(res.containsKey("error"));
    }

    @Test
    @DisplayName("用户取消：返回 cancelled=true，不返回 path")
    void returnsCancelled() {
        when(picker.pick(any(Path.class))).thenReturn(null);
        Map<String, Object> res = controller.pickDirectory(request(1L, "::1"));
        assertEquals(Boolean.TRUE, res.get("cancelled"));
        assertFalse(res.containsKey("path"));
    }

    @Test
    @DisplayName("远程请求：拒绝弹窗，提示手动填写")
    void rejectsRemoteRequest() {
        Map<String, Object> res = controller.pickDirectory(request(1L, "192.168.1.20"));
        assertTrue(String.valueOf(res.get("error")).contains("只能在本机使用"));
        verifyNoInteractions(picker);
    }

    @Test
    @DisplayName("未登录：拒绝")
    void rejectsAnonymous() {
        Map<String, Object> res = controller.pickDirectory(request(null, "127.0.0.1"));
        assertEquals("请先登录", res.get("error"));
        verifyNoInteractions(picker);
    }

    @Test
    @DisplayName("选择器报错：把原因带给前端，不吞成路径")
    void exposesPickerError() {
        when(picker.pick(any(Path.class)))
                .thenThrow(new IllegalStateException("已经有一个文件夹选择窗口在等待"));
        Map<String, Object> res = controller.pickDirectory(request(1L, "127.0.0.1"));
        assertTrue(String.valueOf(res.get("error")).contains("已经有一个"));
        assertFalse(res.containsKey("path"));
    }
}
