# 后端

BearCoding 的 Agent 循环、工具、权限、MCP 和 SQLite 都在这个目录。产品说明见 [根 README](../README.md)。

## 职责

- `POST /ai/stream`：SSE 对话与工具循环（`controller/ChatController.java`）
- 文件、命令、计划、Explore / Implement、检索和记忆工具
- 工作区路径检查（`sandbox/PathSandbox.java`）和命令检查
- 工具权限与人工审批（`permission/`）
- MCP Host（`mcp/`）：stdio 与 Streamable HTTP
- SQLite。启动时执行 `src/main/resources/schema-sqlite.sql`

## 技术栈

Java 17，Spring Boot 3.5.0，Spring AI 1.0.0，MyBatis 3.0，SQLite，MCP Java SDK 1.1.0。工程版本 `3.0.0`，产物名 `bailianAI`。

## 包

| 包 | 内容 |
|---|---|
| `controller` | 聊天、模型、工作区、会话、审批、MCP 管理 |
| `agent` | 子代理规格、房间、循环保护、工具结果截断 |
| `context` | 压缩、工作集投影、`searchContext` |
| `tool` / `utils` | 工具实现与分发 |
| `permission` | 内置规则、MCP 策略、审批 |
| `sandbox` | 路径与命令范围 |
| `mcp` | 连接、重连、凭证、大结果落盘 |
| `usermemory` | 跨会话记忆的写入和删除 |
| `service` | 会话、工作区、计划 |

## 构建与运行

需要 JDK 17。Maven 3.9 即可；也可以用本目录的 `mvnw` / `mvnw.cmd`（Wrapper 3.3.4，指向 Maven 3.9.14）。

```powershell
mvn -o test
mvn -DskipTests package
java -jar target/bailianAI-3.0.0.jar
```

`mvn -o` 只用本机缓存。缓存不完整时去掉 `-o`。

打包前若要让 jar 自带页面，把前端 `dist` 放进 `src/main/resources/static`。步骤见根 README。开发时也可以另开前端，不必先复制静态文件。

进程监听 `127.0.0.1:8082`。没有模型 Key 也能启动。

离线测试最近一次结果：1616 项运行，0 失败，0 错误，9 跳过。需要浏览器、真实 MCP 或真实模型的项会跳过或留在清单里，跳过不是端到端已通过。

## 配置

默认值在 `src/main/resources/application.yml`。不要把密钥写进这个文件。

本地覆盖是进程工作目录下的 `config/application-local.yml`，由 `spring.config.import` 可选加载，并且已被忽略。

源码默认：子代理开启，计划开启，工作集开启，工作区记忆开启，用户记忆开启，MCP Host 开启但服务器列表为空。用户记忆的保存和删除是 ASK。数据模式默认 `desktop`：`JINGJING_DATA_DIR`，否则 `%LOCALAPPDATA%\BearCoding`。

## 更多说明

- [架构](../docs/ARCHITECTURE.md)
- [上下文](../docs/CONTEXT-MANAGEMENT.md)
- [MCP 与安全边界](../docs/MCP-AND-SECURITY.md)
