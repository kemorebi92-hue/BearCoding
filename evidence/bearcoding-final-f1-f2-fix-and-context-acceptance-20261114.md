# BearCoding Final — F1/F2 最小修复与真实上下文闭环验收

日期：2026-11-14　项目：`D:\BearCoding-Final`　基线：`bearcoding-final-real-model-browser-e2e-acceptance-20261114.md`（CONDITIONAL PASS）
报告版本：v1　原始证据：`evidence/bearcoding-final-real-model-20261114/{logs,screenshots}`

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 1. F1 是否真正修复？ | **是**。摘要改为使用「本次请求选中的模型」，真实模型下摘要首次真正生成并应用（89,360 → 9,460 估算 token）。 |
| 2. F2 是否真正修复？ | **是**。编译/运行/删除/自然语言描述的修复步骤都能拿到真实执行证据并完成；真实模型 4 步计划 4/4 DONE 且 `resultVerified=true`。 |
| 3. 默认配置下历史摘要是否实际成功？ | **是**（测试 A，默认配置，真实 deepseek-v4-flash）。 |
| 4. 工具结果退休是否在真实模型任务中发生？ | **是，但只在受控阈值（5000）下**；默认 64000 下未触发（无预算压力，符合预期）。 |
| 5. 模型是否自主恢复退休后的工具原文？ | **是**。模型自主用 `[artifact-ref]` 快照 readFile 读回原文，并与直接重读交叉核对一致。 |
| 6. 摘要、退休、检索、继续执行是否形成闭环？ | **是**（测试 C：同一请求内四个机制全部发生并正确交付）。 |
| 7. 是否丢失关键约束、计划或执行证据？ | 未发现丢失：摘要覆盖 53 条、最近 3 个用户回合保留；计划 4/4；旧事实与退休正文都能恢复。 |
| 8. 是否存在新的安全、协议或并发问题？ | 未发现。审批/权限/沙箱未改；模型按请求传入、观测字段仍是 ThreadLocal。 |
| 9. 能否冻结生产代码进入 GitHub 发布？ | **可以，但建议先处理 §5 的 P2（交付校验对表格化回答误判）**；该问题与本轮修复无关，是既有缺陷。 |

---

## 1. 改动清单（仅 Final 的必要代码与测试）

### 生产代码（4 个文件）

| 文件 | 改动 |
|---|---|
| `context/ContextCompactor.java` | 新增 `assemble(..., ChatModel)` / `buildMessages(..., ChatModel)` / `summarizeTracked(String, ChatModel)`；旧签名委托到注入模型（向后兼容）。摘要失败**只降级一次、不重试、不回退别的模型**。 |
| `controller/ChatController.java` | 两条链路（同步 `chat` 与流式 `chatStream`）都改为**先解析模型再装配历史**，把 `activeModel` 传给 compactor；`compactTotalIfEnabled` 的摘要也用 `activeModel`。 |
| `agent/TaskExecution.java` | 新增命令证据（命令原文、exit 码、结果指纹）与删除证据；新增 `verifiedCommands`；`verifiedFilesStillCurrent` 跳过合成键；把「只读步骤」判断抽成 `readOnlyGoal`，写入归属不再依赖 `requiresEffectEvidence(step,"")`。 |
| `service/TaskPlanService.java` | `updateStep` 同时接受文件证据与命令/删除证据；`verifiedFiles` 里合并保存（键前缀 `command:` / `deleted:`）。 |

### 测试（3 个文件，新增 11 项）

- 新增 `context/SummaryModelRoutingTest.java`（4 项）：摘要走请求模型、null 退回注入模型、失败不重试/不回退、`buildMessages` 同样走请求模型。
- `service/PlanExecutionEvidenceTest.java` 新增 7 项：编译步骤完成、无执行记录仍 pending、exit≠0 不算完成、删除步骤完成、命令证据不能顶替文件写入、命令验证步骤经交付复核不回退、自然语言「修复 xxx.java」步骤能拿到文件证据。
- `context/ContextCompactorFailureTest.java`：测试替身改为覆写新的 2 参摘要入口（语义不变）。

### 未改动

`application.yml`、数据库结构、审批/权限/沙箱策略、前端代码、正式默认阈值。`plan_json` 里新增的 `command:` / `deleted:` 键是同一 JSON 字段内的合成证据键，不是 schema 变更。

### Git

分支 `main`、**HEAD 仍未出生（无提交）**；本轮无 add/commit/push/reset/clean/stash；工作区仍是「全部未跟踪 + `evidence/`」状态。

---

## 2. 离线回归

| 轮次 | 结果 |
|---|---|
| 修复前基线 | 1529 run / 0 fail / 9 skipped |
| F1+F2 首次回归 | 1539 run / 1 fail（`ContextCompactorFailureTest` 测试替身未覆写新入口）→ 修替身后 0 fail |
| F2 归属修正后全量 | **1540 run / 0 failures / 0 errors / 9 skipped，BUILD SUCCESS** |

新增 11 项测试全部通过，无跳过。

---

## 3. 真实模型验证（本轮授权：¥3 软 / ¥5 硬，实际约 ¥1.8）

环境：后端 `127.0.0.1:18186`（PID **27432**，默认配置）、脚本模型 `127.0.0.1:18187`（PID 18352，**本轮真实模型轮次中 0 次请求**）、前端 `127.0.0.1:18190`（PID 20976）。数据目录仍为 `%TEMP%\BearCoding-Final-M4`（独立 SQLite）。8082 全程未触碰。

### 测试 A：历史摘要（默认配置）

- 输入：`conv-ctx-093130`，82 条真实消息 ≈ 79.6k 估算 token。
- 日志：`[Compactor] scope=history-summary trigger=true applied=true reused=false suppressed=false calls=1 covered=53 range=300-352 before=86993 after=7105 reason=SUMMARY`
- `[ContextSplit] … estHistorySummary=442 estHistory=6663`（摘要 442 token，历史从 86,993 降到 7,105）
- `auxiliaryCalls=1 auxiliaryInputTokens=80579`：摘要请求确实发给真实模型；stderr **无** I/O 错误（修复前是 `http://127.0.0.1/v1/chat/completions` 连接失败）。
- 模型回答：口令 `BAMBOO-7319` 通过 `searchContext` 从 `message|276` 取回；第 17 轮原文来自 `message|309/310`，与 DB 逐字一致。
- 判定：**摘要触发 ✅ / 摘要成功 ✅ / 摘要后继续正确执行 ✅**（三层都成立）。

### 测试 B：工具结果退休（受控阈值，非默认）

| 轮次 | 配置 | 结果 |
|---|---|---|
| B1 | **默认 64000** | 5 次大文件读取，`[ContextShadow] … retire=0 recoverableTools=0`；工具结果总量仅 ~26.6k token，**未退休**（无压力，符合「不提前退休」）。模型仍用 `readFile(offset=300)` 精确定位，不是靠猜。 |
| B2 | 受控 12000 | `retire=0`（工具结果 8.4k < 12k，仍未到线）。 |
| B3 | **受控 5000** | `retire=1664 → 3328 → 4992 → 6656`，`keep` 从 9,305 降到 6,161；5 条 `executeCommand` 输出全部 `spilled=true`（快照）；模型用 `readFile` 读取 `[artifact-ref]` 快照并交叉核对第 300 行，结果一致。 |

- 判定：**退休发生（受控阈值）✅ / 无压力不退休（默认）✅ / 快照可恢复 ✅ / 模型自主恢复 ✅ / 恢复正文一致 ✅**。
- 必须标注：B3 的触发来自**测试实例覆盖** `--app.context.working-set.soft-budget-tokens=5000`，不是默认配置的自然触发；测完已恢复默认（当前后端 PID 27432 无任何覆盖参数）。

### 测试 C：多机制协同（同一请求内）

`conv-combo-101847`（82 条种子历史 + 5 条大命令输出，受控阈值 5000）：

```
[Compactor] trigger=true applied=true calls=1 covered=53 before=89360 after=9460 reason=SUMMARY
[ContextShadow] round=5 retire=4613 → round=7 retire=7931  recoverableTools=8295
[ToolCall] executeCommand ×5 (spilled=true)
[ToolCall] readFile  big1.txt (offset=300)
[ToolCall] searchContext  → message|369/370（口令原文）
```

模型最终回答：`BAMBOO-7319`（检索得到，明确说明「当前上下文里看不到」）+ `FILE1 LINE 0300 …`（按行号读回）。判定：**摘要 → 投影 → 退休 → 检索恢复 → 继续执行 → 正确交付，闭环成立**。

### 测试 D：F2 真实模型复测（默认配置）

`conv-real-mwfsfrj2`（新造的同类 bug 项目）：

| 步骤 | 类型 | 状态 | 证据 |
|---|---|---|---|
| s1 编译并运行复现 | 命令 | **done / verified** | `command:javac … && java …` |
| s2 修复 src/Pricing.java | 文件 | **done / verified** | 文件绝对路径指纹 |
| s3 重新编译运行确认 CHECK_OK | 命令 | **done / verified** | `command:javac … && java …` |
| s4 临时校验类验证边界 | 命令 | **done / verified** | `command:javac … && java … && dir` |

对比修复前同一场景（`conv-real-mwfptltd`：1/4、`conv-real-mwfpwjk6`：0/3，模型自述「已停止重复上报」），**F2 死锁已消除**。

---

## 4. 费用

| 场景 | 调用 | prompt | completion | 命中缓存 |
|---|---|---|---|---|
| A 摘要 | 4 | 105,481 | 2,315 | 38,656 |
| B1 退休（默认） | 13 | 370,388 | 3,540 | 315,520 |
| B2 退休（12k） | 8 | 137,552 | 2,373 | 120,576 |
| B3 退休（5k） | 9 | 149,881 | 3,308 | 117,504 |
| C 协同闭环 | 9 | 210,408 | 3,198 | 134,656 |
| D1 F2 首测 | 24 | 430,952 | 11,174 | 412,544 |
| D2 F2 复测 | 16 | 228,316 | 4,310 | 207,360 |
| **合计** | **83** | **1,632,978** | **30,218** | **1,346,816** |

按「未命中 ¥2/M、命中 ¥0.5/M、输出 ¥8/M」估算：**≈ ¥1.49**；加上 2 次摘要辅助调用（约 161k token）≈ **¥1.8**，未触及 ¥3 软预算。D1 的 24 次调用包含模型反复重试被拒的计划上报，是修复前缺陷造成的浪费。

---

## 5. 缺陷与风险（本轮新增）

### P2（既有缺陷，非本轮引入）交付校验对「表格化回答」误判

- 现象：D2 中计划 4/4 全部 done/verified、文件与编译结果都正确，但最终交付被判「未确认完整交付」，界面显示 fallback 文本。
- 证据：`[DeliveryClaim] 未证实片段=| s2 | 描述 | 期望 | 实际 |`；`[Delivery] 最终回答未通过交付核对…`。
- 原因（推断）：`TaskExecution.acceptableAnswer` 按 `。；\n` 切句，markdown 表格的整行是一个片段；该片段里的「已修改」+ `src/Pricing.java` 在交付阶段找不到对应证据（相对路径解析与请求期的工作区上下文不一致），于是整段被判无证据。
- 影响：任务成功但用户看到「未确认完整交付」，属于**交付文案误判**，不影响文件与执行结果。
- 最小修复建议：交付核对时对「已验证步骤的 stepId + 文件名」做一次显式匹配；或让 `targets()` 在交付阶段沿用请求期记录的绝对路径。不要在交付阶段放宽到「有文字就算过」。

### P3（观察）locator 编号

上一轮报告里「占位符 message|308 与第 17 轮不符」在本轮复测中模型自己核对了 `309/310` 并给出正确结论；未再出现错配，倾向于是上一轮模型误读，**不列为缺陷**，保留待复现记录。

---

## 6. 安全与范围

- 仅修改 `<PROJECT_ROOT>` 的必要源码与测试；未触碰 `<LEGACY_PROJECT_ROOT>`、历史版本目录、8082、正式数据库。
- 未输出任何 API Key；模型 Key 仍在 DB 加密列，日志无明文。
- 审批/权限/沙箱策略未改动；本轮真实模型测试中审批按钮均按策略点击，越权路径未被绕过。
- 测试数据全部在 `%TEMP%\BearCoding-Final-M4`（独立 SQLite / 工作区 / 日志）；M4 与上一轮真实模型证据未覆盖、未删除。

## 7. 当前运行状态（保留）

| 角色 | 地址 | PID |
|---|---|---|
| 后端（默认配置，含 F1/F2 修复） | http://127.0.0.1:18186 | **27432** |
| 本地脚本模型 | http://127.0.0.1:18187 | 18352 |
| 前端 | http://127.0.0.1:18190/chat | 20976 |

新增证据：`evidence/bearcoding-final-real-model-20261114/logs/{F1A,F1B,F1B2,F1B3,F1C,F2,F2b}.json` 与同名截图；最终回归日志 `logs/f1f2-full-regression-final.log`（1540 run / 0 failures / 0 errors / 9 skipped）。
