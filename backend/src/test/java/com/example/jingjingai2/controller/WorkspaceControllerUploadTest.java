package com.example.jingjingai2.controller;

import com.example.jingjingai2.service.DirectoryPickerService;
import com.example.jingjingai2.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /ai/workspace/upload 的 HTTP 约定：正常上传返回 200；相对路径越界/绝对路径偷渡返回 400，
 * 不能变成 500（回归时实测过 500，前端只能显示“上传失败”而看不到原因）。
 */
class WorkspaceControllerUploadTest {

    private WorkspaceService workspaceService;
    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        controller = new WorkspaceController(workspaceService, mock(DirectoryPickerService.class));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", 1L);
        return req;
    }

    private static List<MultipartFile> files() {
        return List.of(new MockMultipartFile("files", "hello.txt", "text/plain", "HELLO".getBytes()));
    }

    @Test
    @DisplayName("正常上传：200 + uploaded/files")
    void uploadReturnsSavedPaths() {
        when(workspaceService.saveUpload(eq(1L), eq("c1"), any(), any(), any()))
                .thenReturn(List.of("hello.txt", "src/nested.txt"));

        ResponseEntity<?> res = controller.upload("c1", files(), List.of("hello.txt"), null, request());

        assertEquals(HttpStatus.OK, res.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals(2, body.get("uploaded"));
        assertEquals(List.of("hello.txt", "src/nested.txt"), body.get("files"));
    }

    @Test
    @DisplayName("上传相对路径越界：400 + 明确原因，不是 500")
    void traversalIsBadRequest() {
        when(workspaceService.saveUpload(eq(1L), eq("c1"), any(), any(), any()))
                .thenThrow(new SecurityException("路径越界已拦截: ../escape.txt"));

        ResponseEntity<?> res = controller.upload("c1", files(), List.of("../escape.txt"), null, request());

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertTrue(String.valueOf(body.get("error")).contains("路径越界"));
    }

    @Test
    @DisplayName("上传绝对路径：400，不落盘")
    void absolutePathIsBadRequest() {
        when(workspaceService.saveUpload(eq(1L), eq("c1"), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("不允许使用绝对路径: C:\\Windows\\win.ini"));

        ResponseEntity<?> res = controller.upload("c1", files(), List.of("C:\\Windows\\win.ini"), null, request());

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("error")).contains("绝对路径"));
    }
}
