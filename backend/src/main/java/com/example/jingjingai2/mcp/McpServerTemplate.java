package com.example.jingjingai2.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个经过审核、可供普通用户通过自然语言添加的个人 MCP 服务模板。
 *
 * <p>模板是后端定义的受控配置：<strong>固定</strong> command / args / env / trust / 超时 / 结果长度，
 * 模型只能选模板和填 {@link #allowedParams()} 列出的非敏感参数，不能改命令、包名或任意启动参数。
 *
 * <p>暴露 ≠ 授权：用模板加出来的服务默认 {@code enabled=false}（见
 * {@link com.example.jingjingai2.service.McpServerProvisionService#saveDisabled}），
 * 不启动子进程；用户要去 MCP 管理页手动启用才会连接。新服务的工具默认 ASK，不自动授权。
 *
 * @param id                   稳定模板 ID（小写字母数字连字符）
 * @param displayName          人类可读名称
 * @param description          用途与边界（含是否需要凭证、是否平台相关）
 * @param command              固定命令（受 {@link McpServerConfigValidator#ALLOWED_COMMANDS} 约束）
 * @param args                 固定参数数组
 * @param env                  固定环境变量（值在模板构建时解析，不含秘密）
 * @param trust                trusted / untrusted
 * @param connectTimeoutSeconds initialize 超时秒（0=用全局）
 * @param maxResultChars       单结果字符硬顶（0=用全局）
 * @param allowedParams        模型可填的参数名；空 = 该模板不接受任何参数
 */
public record McpServerTemplate(
        String id,
        String displayName,
        String description,
        String command,
        List<String> args,
        Map<String, String> env,
        String trust,
        int connectTimeoutSeconds,
        int maxResultChars,
        List<String> allowedParams) {
}
