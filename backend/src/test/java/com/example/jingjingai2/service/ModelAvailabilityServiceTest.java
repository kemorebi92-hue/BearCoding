package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.ModelConfig;
import com.example.jingjingai2.mapper.ModelConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** “有没有可用模型”的判定：系统 Key 和用户模型必须分开看。 */
class ModelAvailabilityServiceTest {

    @Test
    @DisplayName("空库且系统 Key 为空：未配置（前端应引导到 /models）")
    void emptyDatabaseAndNoSystemKey() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        when(mapper.findByUserId(1L)).thenReturn(List.of());

        ModelAvailabilityService service = new ModelAvailabilityService(mapper, "");

        ModelAvailabilityService.Status status = service.status(1L);
        assertFalse(status.systemModelAvailable());
        assertFalse(status.hasUserModels());
        assertFalse(status.modelConfigured());
        assertFalse(service.anyModelAvailable(1L));
    }

    @Test
    @DisplayName("系统 Key 已配置：即使没有用户模型也可用")
    void systemModelAvailable() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        when(mapper.findByUserId(1L)).thenReturn(List.of());

        ModelAvailabilityService service = new ModelAvailabilityService(mapper, "sk-system");

        assertTrue(service.systemModelAvailable());
        assertTrue(service.status(1L).modelConfigured());
    }

    @Test
    @DisplayName("用户保存过模型：系统 Key 为空也可用")
    void userModelAvailable() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        ModelConfig cfg = new ModelConfig();
        cfg.setId(9L);
        when(mapper.findByUserId(1L)).thenReturn(List.of(cfg));

        ModelAvailabilityService service = new ModelAvailabilityService(mapper, "  ");

        ModelAvailabilityService.Status status = service.status(1L);
        assertFalse(status.systemModelAvailable());
        assertTrue(status.hasUserModels());
        assertTrue(status.modelConfigured());
    }

    @Test
    @DisplayName("没有身份时不把任何人的模型算成自己的")
    void nullUserHasNoModels() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        ModelAvailabilityService service = new ModelAvailabilityService(mapper, "");

        assertFalse(service.hasUserModels(null));
        assertFalse(service.status(null).modelConfigured());
        verify(mapper, never()).findByUserId(any());
    }

    @Test
    @DisplayName("mapper 返回 null 也不误判")
    void nullListIsNotConfigured() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        when(mapper.findByUserId(1L)).thenReturn(null);

        ModelAvailabilityService service = new ModelAvailabilityService(mapper, "");
        assertFalse(service.hasUserModels(1L));
    }
}
