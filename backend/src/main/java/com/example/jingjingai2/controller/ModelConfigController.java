package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.ModelConfigRequest;
import com.example.jingjingai2.DTO.ModelConfigResponse;
import com.example.jingjingai2.service.ModelAvailabilityService;
import com.example.jingjingai2.service.ModelConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户模型配置接口（放在 /ai 下，受登录拦截器保护）。
 * 提供模型配置的增删改查 + 设默认。密钥在落库时加密、列表时脱敏。
 */
@RestController
@RequestMapping("/ai/models")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", allowedHeaders = "*")
public class ModelConfigController {

    private final ModelConfigService service;
    private final ModelAvailabilityService availability;

    public ModelConfigController(ModelConfigService service, ModelAvailabilityService availability) {
        this.service = service;
        this.availability = availability;
    }

    /** 列出当前用户的所有模型（密钥脱敏） */
    @GetMapping
    public List<ModelConfigResponse> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return service.listForUser(userId);
    }

    /**
     * 首屏引导状态：系统模型是否真的可用、当前用户有没有模型。
     * 不返回任何密钥，只返回布尔值。
     */
    @GetMapping("/status")
    public ModelAvailabilityService.Status status(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return availability.status(userId);
    }

    /** 新增模型 */
    @PostMapping
    public ModelConfigResponse add(@RequestBody ModelConfigRequest body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return service.add(userId, body);
    }

    /** 更新模型 */
    @PutMapping("/{id}")
    public ModelConfigResponse update(@PathVariable Long id,
                                     @RequestBody ModelConfigRequest body,
                                     HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return service.update(userId, id, body);
    }

    /** 删除模型 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        service.delete(userId, id);
        return new HashMap<>(Map.of("message", "删除成功"));
    }

    /** 设为默认模型 */
    @PostMapping("/{id}/default")
    public Map<String, Object> setDefault(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        service.setDefault(userId, id);
        return new HashMap<>(Map.of("message", "已设为默认"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException exception) {
        return new HashMap<>(Map.of("message", exception.getMessage()));
    }
}
