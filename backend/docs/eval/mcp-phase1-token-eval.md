# MCP Phase 1 token 成本（工具 schema）

口径：把本轮下发的工具定义序列化成 JSON 后按「字符数 ÷ 1.5」估算 token，
与 ChatController 的估算一致，**不是**服务商计费值。
原生工具用一组固定 stub（readFile / writeFile / executeCommand / createPlan / spawnSubagent）；
MCP 工具每条带一段中等 schema（3 个 string 字段）。

| 条件 | 工具数 | schema 字符 | 估算 token | 相对「MCP 关」 |
|---|---:|---:|---:|---:|
| MCP 关 | 5 | 691 | 461 | 0 |
| 2 个 MCP 工具 | 7 | 1085 | 723 | +262 |
| 20 个 MCP 工具 | 25 | 4651 | 3101 | +2640 |

`app.context.max-tokens=8000`。20 个 MCP 工具大约再吃 2640 token 的工具定义，
会明显挤占对话预算。默认暴露 server 发现的所有工具（`enabled-tools` 已废弃，不再收窄），
要收敛就用 `disabled-tools` 排除个别工具，或靠 `tool-loading: deferred` 只下发激活过的工具。

3 问 × 3 次的在线评测见同目录 `mcp-phase1-live-eval.md`（无密钥或服务不可达时会标明跳过）。
