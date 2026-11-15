口径：把工具定义序列化成 JSON 后按「字符数 ÷ 1.5」估算 token，与 ChatController 一致，**不是**服务商计费。
原生工具用 5 个 stub（readFile / writeFile / executeCommand / createPlan / spawnSubagent）。
MCP 侧用 `FatMcpServer` 的真实 description + schema（tracker 15 + ops 15）。

| 条件 | 工具数 | schema 字符 | 估算 token | 相对「MCP 关」 |
|---|---:|---:|---:|---:|
| MCP 关 | 5 | 677 | 451 | 0 |
| 2 个 echo 工具 | 7 | 998 | 665 | +214 |
| 30 个 FatMcp 工具 | 35 | 10324 | 6883 | +6432 |

`app.context.max-tokens=8000` 只约束**历史压缩**，不硬截本轮工具 schema。
30 个真实 schema 大约再吃 6432 token 的工具定义（比 Phase 1 的 20 个中等 stub 更肥）。
在线对话会不会被服务商 context 打爆，见 `mcp-deferred-baseline.md`。
