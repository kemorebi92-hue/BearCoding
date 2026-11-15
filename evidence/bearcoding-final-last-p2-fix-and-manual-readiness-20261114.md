# BearCoding Final —— 最后一个 P2 修复、离线回归与手动验收环境准备

日期：2026-11-14　项目：`D:\BearCoding-Final`　分支：`main`（无提交）
上一份报告：`evidence/bearcoding-final-f1-f2-fix-and-context-acceptance-20261114.md`
报告版本：v1　本轮未新增任何真实模型付费调用

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 1. 最后一个 P2（交付校验对 Markdown 表格误判）是否修复？ | **是**。根因不是表格，而是「反引号里的代码表达式被当成文件目标」。已按最小改动修复，并用真实回答原文做回归。 |
| 2. 是否新增回归失败？ | **没有**。全量 `1548 run / 0 failures / 0 errors / 9 skipped`，BUILD SUCCESS（基线 1540，新增 8 项全绿）。 |
| 3. F2 证据回退是否存在安全误判？ | **存在两处可复现的误判，已局部修复**：①不相关命令顶替当前步骤；②同一条命令给两个步骤作证。真实跑过的步骤不受影响（4/4 场景的归属路径未改）。 |
| 4. 后端和前端是否正常运行？ | **是**。后端 `127.0.0.1:18186`（PID 18400，含本轮修复的新 JAR），前端 `127.0.0.1:18190`（PID 24488，仅监听本机）。 |
| 5. 用户能否直接用浏览器开始测试？ | **可以**。`http://127.0.0.1:18190/chat` 已用无头浏览器核验：三个页面 200、0 个 JS 错误、API 全部经代理打到 18186。 |
| 6. 实际使用的是哪个模型？ | `deepseek-v4-flash`（id=2，`defaultModel=true`）。`local-script-model` 存在但**不是默认**，本轮也**没有启动**它。 |
| 7. 测试工作区在哪里？ | `%TEMP%\BearCoding-Final-M4\workspace\manual-java-demo`（5 个 Java 文件 + 1 个审批拒绝文本 + `run.cmd`）。 |
| 8. 手动验收教程在哪里？ | `docs/BearCoding-Final-用户手动验收教程.md` |
| 9. 推荐先测哪三个？ | 测试 3（改 Java + 审批）、测试 5（Plan-and-Execute，同时验证本轮 P2）、测试 6（Explore 子代理）。 |
| 10. 是否可以等用户验收后进入 GitHub 清理与 Electron 封装？ | **可以**。本轮未做任何 git add/commit/push、未打包桌面版。 |

---

## 1. P2 根因（先复现，再改）

### 1.1 真实证据

`%TEMP%\BearCoding-Final-M4\logs\backend-run6.out.log`（D2 轮次，conversation `conv-real-mwfsfrj2`）：

```
[DeliveryClaim] 未证实片段=| s2 | 修正公式为 `price * (1.0 - rate)`，清除 BOM | ✅ 已修改 |
[Delivery] 最终回答未通过产物核对，长度=1832 全文=「## 检查结果与修复交付 …」
```

同一轮里 4 个步骤都是 `done / resultVerified=true`，文件与编译输出都正确，
`planText = 任务计划 4/4 已完成`，但最终回答被替换成「本次未确认完整交付」。

### 1.2 真正的判定链（不是「表格有竖线」）

| 环节 | 代码 | 对这条片段的结果 |
|---|---|---|
| 切句 | `TaskExecution.acceptableAnswer`：`text.split("[。；;\n]")` | 表格的一整行是**一个**片段，竖线本来就不是切分符（这里没问题） |
| 找「声称写了文件」 | `FILE_CLAIM`：`已(?:写入\|保存\|生成\|创建\|修改\|覆盖)…` | 命中「已修改」→ 需要核对 |
| 解析文件目标 | `FileExecutionEvidence.targets` → `path("price * (1.0 - rate)")` | Windows 上 `*` 是非法路径字符，`Path` 抛 `InvalidPathException` → 返回 `null` → **没有解析出任何目标** |
| 判断「提到了目标」 | `FileExecutionEvidence.mentionsTarget`：`TARGET.matcher(text).find()` | 反引号片段本身匹配 `TARGET` 的「引号内引用」分支 → **返回 true** |
| 合并判定 | `claimsAreEvidenced`：`claimed.isEmpty()` → `return !(mentionsTarget \|\| 含"文件")` | `!(true \|\| …)` = **false** → 判为未证实 |

也就是说：**同一个引用在「提到了目标」里算数，在「解析出目标」里不算数**，两个判断互相矛盾。
模型说的其实是一句公式说明（`price * (1.0 - rate)`），根本不是文件。

### 1.3 为什么「整段再合看一次」也没救回来

`acceptableAnswer` 还有一层兜底：`claimsAreEvidenced(text, steps)`（用整篇回答再判一次）。
这条兜底同样失败，因为整篇回答里还提到了 `Main.java`、`Order.java`——
它们**本次请求确实没有被写过**，逐路径核对自然拿不到证据。
所以问题必须在前一步（片段级）解决，不能靠放宽整段兜底。

### 1.4 顺带发现的同类隐患

裸写的数字也会被当成文件名：`30.0` 能匹配「名字.扩展名」，`Path.of("30.0")` 在 Windows 上合法，
于是「| s2 | 改成 1.0 | ✅ 已修改 |」这种表格行同样会被判未证实。本轮一并处理。

---

## 2. 实际修改（2 个生产文件 + 1 个新测试文件）

### 2.1 `backend/src/main/java/com/example/jingjingai2/agent/FileExecutionEvidence.java`

| 位置 | 改动 |
|---|---|
| `CODE_EXPRESSION`（第 44 行） | 新增：引号内出现 `* + < > = ( ) [ ] { } ' " \| & ; ~ ^ % $ # @ ! ?` 的引用是**表达式**，不是文件名。**故意不含空格**（见 §2.4），也不含 `:`（`C:\work\a.txt` 是合法路径） |
| `NUMERIC`（第 46 行） | 新增：纯数字（`1.0`、`3.14`）不是「文件名.扩展名」 |
| `pathShaped(String)`（第 51 行） | 新增：唯一的「这条引用能不能当文件核对」判定 |
| `mentionsTarget`（第 57 行） | 改为**遍历全部引用**，只有 `pathShaped` 为真的才算「提到了目标」；表达式/数字直接跳过 |
| `targets`（第 67 行） | 解析前先过 `pathShaped`，不合法引用 `continue`（第 79 行）。原有的「引号里带『文件』且解析失败 → 整句作废」保护原样保留 |

### 2.2 `backend/src/main/java/com/example/jingjingai2/agent/TaskExecution.java`

| 位置 | 改动 |
|---|---|
| `commandOwner`（第 62 行） | 新增：命令下标 → 已用它作证的 stepId |
| `verifiedCommands`（第 300 行） | ①直接按标识符对齐命中时登记归属（第 310 行）；②退回「最近一次成功命令」前先看归属，已被别的步骤用过就跳过并打日志（第 317 行）；③再看 `namesUnrunIdentifier`，命中就拒绝归属（第 323 行） |
| `namesUnrunIdentifier`（第 343 行） | 新增：步骤/上报正文点名的「具体标识符」在**所有成功命令里都没出现过**时，不允许别的命令顶替 |
| `identifiers`（第 353 行） | 新增：保留大小写抽取两类强特征——多词驼峰类名（`VerifyTmp`、`PricingCheck`）与带扩展名的文件名 |

`acceptableAnswer` / `claimsAreEvidenced`（第 421 / 441 行）**没有改判定逻辑**，只是它们依赖的
`mentionsTarget` / `targets` 现在自洽了。

### 2.3 新增测试 `backend/src/test/java/com/example/jingjingai2/service/DeliveryClaimTableTest.java`（8 项）

| 用例 | 覆盖的本轮要求 |
|---|---|
| `realFileEditDeliveredAsMarkdownTableIsAcceptable` | 要求 1 + 3 + 4：真实改对文件、编译运行通过，**用 D2 的最终回答原文**（含 `price * (1.0 - rate)` 表格行）必须可交付；同时断言证据键是绝对路径且指向 `src/Pricing.java` |
| `tableHeaderAndSeparatorAreNotTreatedAsClaims` | 要求 2：`\| 步骤 \| 文件 \| 已修改 \|` 这种表头 + `\|---\|---\|---\|` 分隔行不算交付声明 |
| `claimWithoutRealWriteIsStillRejected` | 要求 4 + 6：没写过就不能说「已修改」；同一句话在真的写入之后必须通过 |
| `failedCompileCannotCompleteTheStep` | 要求 5：`exit=1` 不是成功证据，步骤不得 DONE |
| `unrelatedSuccessfulCommandDoesNotProveAnotherStep` | 要求 7：声称跑过 `VerifyTmp`、实际只跑了 `PricingCheck` → 必须 pending；真的跑了之后才 DONE |
| `sameCommandCannotVouchForTwoSteps` | 要求 7：一条成功命令不能给两个步骤作证 |
| `plainTextAnswerStillPasses` | 要求 8：普通文字回答不回归；纯承诺仍然不算交付 |
| `relativeAndAbsoluteEvidenceAgree` | 要求 3：相对路径 ↔ 请求期绝对路径证据一致 |

### 2.4 一次自我纠偏（值得记录）

第一版 `CODE_EXPRESSION` **把空格也算作代码特征**，结果两条既有测试立刻失败：

- `PlanExecutionEvidenceTest.extensionlessTargetsMustBeAccountedFor`
- `PlanExecutionEvidenceTest.basenameDoesNotMatchSpacedPathButWorkspaceAliasesDo`

它们证明工作区里**真的可以有** `plain notes`、`notes/weekly a.md` 这种带空格的文件名。
把空格从特征集合里去掉后，两条测试恢复通过，P2 的复现用例仍然失败→修复后通过。
**结论：判定只能收紧到「代码符号 + 纯数字」，不能顺手把带空格的文件名也否掉。**

---

## 3. 为什么这个修复没有放宽交付验证（逐条对照）

| 要求 | 落实情况 |
|---|---|
| 1. 表格不能因为竖线/表头/多字段被误判 | 竖线从来不是切分符；真正修的是「反引号表达式」这个误判源 |
| 2. 已验证的文件修改步骤必须能关联到工作区路径 | `pathShaped` 只做减法（排除表达式/数字），对真实文件名不做任何限制；`relativeAndAbsoluteEvidenceAgree` 锁住相对↔绝对一致 |
| 3. 相对路径与请求期绝对路径证据一致 | 未改 `path()` 的解析规则（`WorkspaceContext` 在整条请求内都有效），只改了「什么样的引用值得解析」 |
| 4. stepId / 文件名 / 真实证据参与核对 | 已有逻辑保留：`claimsAreEvidenced` 会查「已 verified 且指纹仍一致的步骤」的 `verifiedFiles` |
| 5. 未执行的工具操作仍不能被文字冒充成功 | 没写过 → `targets()` 解析出真实路径 → `evidence` 为空 → 仍然拒绝（`claimWithoutRealWriteIsStillRejected`） |
| 6. 不允许关闭交付校验器 | 校验器完整保留；`acceptableAnswer` / `claimsAreEvidenced` 的判定结构一行没删 |
| 7. 不允许把「没有证据」统一当成功 | 只有**明确不是文件**的引用被排除；路径型引用一旦解析成功，仍然逐路径要求证据 |
| 8. 不允许仅凭模型输出的表格认定完成 | 表格本身不产生任何证据；`realFileEditDeliveredAsMarkdownTableIsAcceptable` 里表格能通过，是因为同一次请求里 4 个步骤都拿到了真实文件/命令证据 |

**唯一的语义变化**：`mentionsTarget` 不再把「代码片段/数字」算成「提到了一个文件」。
它原本的用途是「你提到了一个像文件的东西但我解析不出来，所以不敢放行」；
对 `price * (1.0 - rate)` 这种明确不是路径的片段继续判死，属于误判，不是安全边界。

---

## 4. F2 命令证据回退审查

### 4.1 结论

原实现的**回退条件**存在两处可复现的误判（不是「没问题」），已做局部收紧；
**按标识符直接对齐的主路径没有改动**，所以已经通过的 4/4 计划验证不会被打回。

### 4.2 误判 1：不相关的成功命令顶替当前步骤

- 场景：步骤目标写「编译并运行 `VerifyTmp` 校验边界」，本次请求真实执行成功的命令只有
  `javac … PricingCheck.java && java … PricingCheck`。
- 旧行为：`sharesTarget` 对不上 → 退回「最近一次成功命令」→ 把 PricingCheck 的成功当成 VerifyTmp 的证据。
- 违反：`exit=0` 只说明**那条命令**成功了，不说明这一步的业务目标达成。
- 现在：`namesUnrunIdentifier` 发现 `VerifyTmp` 在所有成功命令里都没出现过 → 拒绝归属，步骤保持 pending。
  日志：`[PlanEvidence] 步骤点名了命令里没出现的标识符，拒绝按最近一次成功命令归属: step=…`
- 保护 4/4 场景：`identifiers()` **只认**多词驼峰类名与带扩展名的文件名。
  D2 里 s1/s4 的正文是 `total=30.0`、`CHECK_FAIL`、`6 个用例全部 PASS` 这类**期望输出**，
  本来就不会出现在命令行里，因此不会被误判（这也是当初必须保留回退的原因）。
  s2/s3/s4 点到的 `Main`、`PricingCheck` 在命令里都出现过，不受影响。

### 4.3 误判 2：同一条命令给两个步骤作证（过期证据复用）

- 场景：s1 用命令 A 完成；s2 没有跑新命令就上报 done，旧逻辑会把 A 再给 s2 用一次。
- 现在：`commandOwner` 记录「这条命令已经给哪个 stepId 作过证」，不同步骤不再复用。
  日志：`[PlanEvidence] 命令证据已被步骤 s1 使用，不再复用: step=s2 …`
- 保护 4/4 场景：D2 里 s1 走回退（cmd2），s3 按 `Main` 直接命中（cmd3），s4 走回退（cmd5），
  三者命令下标不同，不会互相顶掉。

### 4.4 明确保留（未改）与残留风险

- **跨任务误用**：结构上不可能。`TaskExecution` 是每请求一个的账本，经 `ThreadLocal` 挂在请求线程上，
  命令记录只存在于本次请求内。
- **按标识符直接对齐**仍是「共享一个 token 就算同一目标」，例如目标写「运行 Main 并检查输出」，
  一条 `type Main.java`（exit=0）也会命中。这是既有设计的宽松点，**本轮不动**：
  收紧它会直接威胁已经通过的 4/4 计划验证，属于下一轮（如有需要）的独立议题。
- 上述两点已在 §8「已知限制」登记。

---

## 5. 回归结果

### 5.1 先证明测试能抓到旧缺陷

把两处修复**临时回退**后单独跑三个新用例，结果全部失败（原始日志
`evidence/bearcoding-final-manual-readiness-20261114/revert-proof.log`）：

```
[ERROR] Tests run: 3, Failures: 3, Errors: 0, Skipped: 0
[ERROR]   realFileEditDeliveredAsMarkdownTableIsAcceptable:221 真实改对文件、编译运行通过时，表格化最终回答必须可交付 ==> expected: <true> but was: <false>
[ERROR]   sameCommandCannotVouchForTwoSteps:333 expected: <true> but was: <false>
[ERROR]   unrelatedSuccessfulCommandDoesNotProveAnotherStep:306 没有跑过 VerifyTmp 就不能拿别的命令顶替 ==> expected: <true> but was: <false>
```

随后从备份恢复，两个文件的 SHA-256 与回退前**逐字节一致**：

```
FileExecutionEvidence.java  1A652A61…52F2D5
TaskExecution.java          5F5526F8…DF2D72
```

### 5.2 相关模块

```
DeliveryClaimTableTest            8 / 0
PlanExecutionEvidenceTest        17 / 0
PlanVerifierTest                 10 / 0
TaskPlanCacheIsolationTest        6 / 0
TaskPlanModelTest                 7 / 0
SummaryModelRoutingTest           4 / 0
ContextCompactorFailureTest       3 / 0
```

### 5.3 全量

```
[WARNING] Tests run: 1548, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

| 轮次 | 结果 |
|---|---|
| 上一轮基线 | 1540 run / 0 fail / 0 error / 9 skipped |
| 本轮 | **1548 run / 0 fail / 0 error / 9 skipped** |

新增 8 项，无新增失败，跳过项数量不变（9 项仍是既有的环境依赖跳过）。
原始日志：`evidence/bearcoding-final-manual-readiness-20261114/full-regression.log`。
D2 失败原文（GBK 解码后）：`evidence/bearcoding-final-manual-readiness-20261114/failing-delivery-answer.txt`。

---

## 6. Git 状态（只读记录，本轮未做任何写操作）

```
分支：main
提交：无（fatal: your current branch 'main' does not have any commits yet）
工作区：全部未跟踪
  ?? .gitignore          ?? ACCEPTANCE.md        ?? BearCoding-Final-docs.zip
  ?? README.md           ?? backend/              ?? docs/
  ?? evidence/           ?? frontend/             ?? 手动验收教程.md
```

本轮**没有**执行 `git add / commit / push / reset / clean / restore / stash`，也没有切换分支。
新增/修改的文件同样处于未跟踪状态——**不能因为「没有 git diff」就认为文件没被改过**。

---

## 7. 当前服务、模型配置与测试工作区

### 7.1 服务（本轮启动，保留运行）

| 角色 | 地址 | PID（2026-11-14 15:19 实测） | 说明 |
|---|---|---|---|
| 后端 | `http://127.0.0.1:18186` | **17328**（启动器 28884） | `java -jar backend\target\bailianAI-3.0.0.jar --server.port=18186`，工作目录 `%TEMP%\BearCoding-Final-M4`，`JINGJING_DATA_DIR=%TEMP%\BearCoding-Final-M4\data` |
| 前端 | `http://127.0.0.1:18190/chat` | **30172** | `vue-cli-service serve --port 18190 --host 127.0.0.1`，代理 → `http://127.0.0.1:18186` |
| 本地脚本模型 | `127.0.0.1:18187` | 未启动 | 本轮手动验收不需要，**故意不启动** |
| 正式服务 8082 | — | 未占用 | 全程未触碰 |

> ⚠️ PID 会随重启变化，**以 `netstat -ano | findstr :18186` 为准**。
> 这些进程在本轮中途被另一个并行会话重启过，详见 §7.5。

- 数据根：`JINGJING_DATA_DIR=%TEMP%\BearCoding-Final-M4\data`，SQLite 落在
  `%TEMP%\BearCoding-Final-M4\data\data\jingjing.db`（1,495,040 字节，与上一轮同一个库，安全复用）。
- 密钥：`data\secrets\model-aes.key` 等仍在原处，模型 API Key 仍是加密列，**未读取、未打印明文**。
- 新 JAR 确认含本轮修复：`BOOT-INF/classes/…/FileExecutionEvidence.class` 含 `pathShaped`，
  `…/TaskExecution.class` 含 `namesUnrunIdentifier`。
- 前端从源码启动（dev server），所以前端也是最新代码；`compress:false` + `/ai` 代理头已按原配置生效。

### 7.2 模型配置（`GET /ai/models` 实测）

```json
[{"id":2,"provider":"deepseek","baseUrl":"https://api.deepseek.com",
  "modelName":"deepseek-v4-flash","defaultModel":true,"apiKeyMasked":"已加密保存"},
 {"id":1,"provider":"local-script","baseUrl":"http://127.0.0.1:18187/v1",
  "modelName":"local-script-model","defaultModel":false,"apiKeyMasked":"已加密保存"}]
```

- 默认模型 = `deepseek-v4-flash`（页面 `/models` 上也显示「默认」标记在该行）。
- `local-script-model` 存在但不默认，且 18187 未启动 → 不会被误选、也连不上。
- 不需要重新索取 API Key；F1 修复后的摘要走「本次请求选中的模型」，即 `deepseek-v4-flash`。

### 7.3 页面核验（无头浏览器，只读）

| 页面 | 状态 | 结果 |
|---|---|---|
| `/chat` | 200 | 标题 `BearCoding`，输入框 1 个，左侧工作区分组与会话列表正常渲染 |
| `/models` | 200 | 两行模型，`deepseek-v4-flash` 带「默认」，`local-script-model` 无默认 |
| `/mcp` | 200 | `echo-local` 已连接、发现 2 个工具（`echo` / `add`），权限区正常 |

- JS 错误：**0**（`console error` 与 `pageerror` 均为空）。
- 所有业务请求都走 `127.0.0.1:18190`（dev-server 代理）→ 后端 18186，没有跨域直连。
- 截图：`evidence/bearcoding-final-manual-readiness-20261114/{01-chat,02-models,03-mcp}.png`。

### 7.4 测试工作区（本轮新建，与 Final 源码完全隔离）

`%TEMP%\BearCoding-Final-M4\workspace\manual-java-demo`

| 文件 | 作用 | 初始状态 |
|---|---|---|
| `src/Main.java` | 入口：2×键盘 100.0 + 1×鼠标 50.0，九折，自检 | 无缺陷 |
| `src/Cart.java` | 购物车与小计 | **缺陷 2**：`subtotal()` 忽略数量 |
| `src/Order.java` | 订单 = 购物车 + 折扣率 | 无缺陷 |
| `src/Pricing.java` | 折扣与四舍五入 | **缺陷 1**：`applyDiscount` 返回 `price * rate` |
| `src/Report.java` | 只打印小票 | 无缺陷 |
| `notes/do-not-edit.txt` | 审批拒绝测试文件 | 含 `KEEP-ME-1234` |
| `out/` | 编译输出目录 | 空 |
| `run.cmd` | 一键编译运行 | — |

初始（带缺陷）输出已实测：

```
subtotal=150.0
total=15.0
CHECK_FAIL expected subtotal=250.0 total=225.0
```

两处都改对之后（在临时副本里实测，副本已删除）：

```
subtotal=250.0
total=225.0
CHECK_OK
```

### 7.5 运行期事件：并行会话重启服务（2026-11-14 15:09–15:18）

**这不是代码缺陷，是环境操作事故，已恢复并留档。**

| 时间 | 发生了什么 | 证据 |
|---|---|---|
| ~15:09 | 另一个并行会话（`bash.exe` + `~/.claude/shell-snapshots/…`）重新构建了 JAR | `target\generated-sources` 15:17、`jar.original` 15:17:29、`bailianAI-3.0.0.jar` **15:18:09** |
| ~15:09 | 正在运行的旧实例（PID 18400，从该 JAR 启动）**懒加载类失败**并退出 | 日志末尾 `ClassNotFoundException: org.springframework.web.servlet.ModelAndViewDefiningException`、`NoClassDefFoundError: org/apache/catalina/util/RequestUtil`、HTTP 500 |
| 15:15:02 | 该会话重启后端，但**没有带 `JINGJING_DATA_DIR`** | 新实例落到桌面模式兜底目录 `%LOCALAPPDATA%\BearCoding` |
| 15:15:06 | 于是**新建了一个空数据根**：`%LOCALAPPDATA%\BearCoding\data\jingjing.db`（139,264 字节，0 会话 0 模型）+ 三把新密钥 | 该文件 mtime 15:15:06；`/ai/models` 返回 `[]`、`/ai/conversations` 返回 0 |
| 15:17–15:18 | 该会话再次重建 JAR，我 15:17 启动的实例同样因 JAR 被替换而崩溃 | 我的 job 退出码 1，日志同上 |
| 15:18:15 | 该会话第三次启动后端，这次环境变量正确 | PID 17328；`/ai/models` 返回 2 条、`/ai/conversations` 返回 **77** 条、`echo-local` 已连接 |

**关键结论：用户的隔离数据没有受损。**
`%TEMP%\BearCoding-Final-M4\data\data\jingjing.db` 在 15:09:52 之后**没有任何写入**，
直到 15:18:19 被正确实例正常打开；那个空实例碰的是另一个目录，从未写入 M4。

**恢复后的核验（15:19）**：`/chat`、`/models`、`/mcp` 全部 200，JS 错误 0，
模型 2 条（`deepseek-v4-flash` 默认），会话 77 条，MCP `echo-local` 已连接 2 工具，
运行中的 JAR 仍含 `pathShaped` 与 `namesUnrunIdentifier`（说明并行会话的重新构建保留了本轮修复）。

**根因与规避**（已落地为两个启动脚本，放在隔离目录，不进仓库）：

- `%TEMP%\BearCoding-Final-M4\start-backend.cmd`
  固定 `JINGJING_DATA_DIR=%M4%\data`，并且**先把 JAR 复制到 `%M4%\run\` 再运行**——
  这样后续重新构建 `backend\target` 不会再把运行中的 JVM 打崩。
- `%TEMP%\BearCoding-Final-M4\start-frontend.cmd`
  固定 `VUE_APP_PROXY_TARGET=http://127.0.0.1:18186`、`--host 127.0.0.1`。

**两条给后续操作的硬性提醒**：

1. **不要在服务运行时重建 JAR**（`mvn package` 会原地替换 `target\*.jar`，正在运行的 JVM 会因懒加载失败而 500/退出）。
   要重建就先停服务，或用上面的脚本（跑副本）。
2. **不要让两个会话同时管理同一组端口/数据库**。本次事故就是并行会话在 18186 上重启导致的。

**残留物（未删除，留给发布清理阶段处理）**：
`%LOCALAPPDATA%\BearCoding\`（空库 139KB + 三把新密钥，由 15:15 的错误启动创建）。
它不含任何用户数据，但属于误建目录；本轮按「不删除自己没打算创建的东西」原则保留并登记。

---

## 8. 是否具备手动验收条件 / 已知限制

**具备。** 后端、前端、模型配置、MCP、测试工作区、教程均已就绪，用户可以直接打开
`http://127.0.0.1:18190/chat` 开始按教程操作。

已知限制（都不阻断手动验收）：

1. **命令证据的「直接对齐」仍偏宽松**（§4.4）：共享一个标识符就算同一目标。
   已通过 4/4 计划验证的场景依赖这条宽松规则，本轮不动。
2. **单步验证的 PlanVerifier 是关键词兜底**：它只看「有没有失败信号 / 是否完全没沾到预期要点」，
   不做语义判断。所以 PASS 判定必须看**真实文件与真实程序输出**，不能只看计划变绿（教程里已反复强调）。
3. **交付校验只覆盖「声称写了文件」的句子**：如果模型完全不提文件名也不说「文件」，
   它不会被这条规则拦住——这是既有设计（真正的兜底是计划步骤的证据要求），本轮没有放宽也没有收紧。
4. 本轮**未做真实模型验证**（按授权要求）。因此 P2 修复的验证方式是：
   D2 的真实回答原文 + 真实执行序列重放的离线用例（已证明回退后会失败）。
5. 前端 dev server 的 `open:true` 会自己拉起一个浏览器标签；如果看到多余标签，直接关掉即可，
   地址始终是 `http://127.0.0.1:18190/chat`。
6. 8082 正式服务、`<LEGACY_PROJECT_ROOT>`、历史版本目录与数据库**全程未访问、未修改**。

---

## 9. 本轮未做（按指令）

- 未开始 GitHub 提交 / 推送 / 清理发布内容。
- 未做 Electron 桌面封装与打包。
- 未新增真实模型付费调用（本轮 0 次模型调用）。
- 未删除、未覆盖上一轮的 M4 证据与真实模型证据。
