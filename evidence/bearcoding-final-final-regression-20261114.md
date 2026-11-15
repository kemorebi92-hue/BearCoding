# BearCoding Final —— 最终回归报告（前端整理后的全链路）

日期：2026-11-14　项目：`D:\BearCoding-Final`　分支：`main`（无提交）
上一份报告：`evidence/bearcoding-final-last-p2-fix-and-manual-readiness-20261114.md`
本轮：0 次真实模型调用；未改任何安全策略、未放宽任何断言、未关闭任何功能。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 1. 把 15:24–16:56 的改动（工作区绑定/选择器、分组、ChatView、辅助额度测试）加进回归后，是否全绿？ | **是**。后端 `1553 run / 0 failures / 0 errors / 9 skipped`，前端 47 项 node 测试全过，lint 0 错误，`npm run build` 成功。 |
| 2. 回归过程中是否发现真实问题？ | **发现 4 个，已全部修复并补测**：①文件夹路径反推返回 `D:`；②上传成功但文件树不刷新（缓存）；③上传路径越界返回 500；④Agent 跑完刷新文件树仍命中旧缓存。 |
| 3. 修复后是否重新跑过全量？ | **是**。后端全量两次（1550 → 1553），前端 node/lint/build 各两次，浏览器冒烟两次（16/16）。 |
| 4. 打好的 jar 是否包含最新前端？ | **是**。`BOOT-INF/classes/static/index.html` 引用 `app.384c163a.js`，与 `frontend/dist` 一致；`mvn clean package` 后无旧 bundle 残留。 |
| 5. 页面能不能跑通？ | **能**。Edge 无头浏览器 16 项全过、0 个 JS 错误；覆盖 `/chat`、`/models`、`/mcp`、新对话、上传、预览、绑定/解绑真实目录、刷新深链。 |
| 6. 工作区 API 是否安全边界正确？ | **是**。29 项 HTTP 冒烟全过：`../`、绝对路径、上传越界都被 400 拒绝，错误信息明确。 |
| 7. 现在可以进入桌面打包吗？ | **可以**。流程建议见 `docs/DESKTOP-PACKAGING.md`；本轮未做 Electron 封装、未提交 Git。 |
| 8. 还有哪些没验收？ | 真实模型任务、真实外部 MCP、原生文件夹选择弹窗的真人点击、`frontend/e2e/workspace-groups.spec.js`（已过期，见 §5）。 |

---

## 1. 本轮范围与基线

| 项 | 值 |
|---|---|
| 回归对象 | 15:24–16:56 的全部改动：`DirectoryPickerService`、`WorkspaceService`/`Controller`、`workspace.js`、`ChatView.vue`、`TaskExecution`、`TaskExecutionAuxiliaryShareTest`、`PlanExecutionEvidenceTest` |
| 上一轮基线 | 1548 run / 0 fail / 0 error / 9 skipped（15:21 报告） |
| 用户改动后 | 1550 run（新增 `TaskExecutionAuxiliaryShareTest` 4 项，`PlanExecutionEvidenceTest` 调整 2 项） |
| 本轮新增测试 | 3 项（`WorkspaceControllerUploadTest`）→ **1553** |
| 数据隔离 | 后端 18200/18201、18186 全部使用独立数据根；未读写 `<LEGACY_PROJECT_ROOT>` 与正式 8082 |

---

## 2. 回归发现的 4 个问题与修复

### 2.1 `directoryPathFromFiles` 把 `D:\myproj` 算成 `D:`（前端 node 测试 2 项失败）

- 现象：`workspaceUpload.node.test.mjs` 第 1、2 项失败，`expected 'D:\myproj'`、`actual 'D:'`。
- 根因：`webkitRelativePath` 的第一段就是被选中的文件夹名（`myproj/src/a.js`），
  原实现从绝对路径里把整段 `rel` 切掉，等于把 `myproj` 也切掉了，只剩 `D:\`。
- 修复：`frontend/src/utils/workspaceUpload.js` 切掉 `rel` 后把第一段文件夹名接回来；
  「路径对不上不猜」的校验保留。
- 验证：4/4 通过（含正斜杠、拿不到 path、路径不匹配三种边界）。
- 备注：该函数目前未被 `ChatView` 调用（“上传文件夹”已改为绑定真实目录），
  属于为 Electron 预留的能力；**桌面端 `File.path` 可用时可以直接复用**（见打包文档）。

### 2.2 上传成功但文件树不刷新（浏览器冒烟发现）

- 现象：`POST /ai/workspace/upload` 返回 `{"uploaded":1}`，面板仍显示“还没有文件”，
  且之后不再发 `/tree` 请求。
- 根因：`loadWorkspaceTree` 新增了按 `conversationId+workspaceId+dir` 的缓存，
  `lastTreeKey` 命中上传前的空列表；`onFilesPicked` 上传后直接 `loadWorkspaceTree()`，拿到的是旧缓存。
- 修复：上传成功后 `invalidateTreeCache()` 再刷新；清空工作区同样先作废缓存。
- 验证：浏览器冒烟“上传后文件树出现 hello.txt”通过。

### 2.3 上传路径越界返回 500

- 现象：`paths=../escape.txt` 时接口 500，日志 `java.lang.SecurityException: 路径越界已拦截`。
- 根因：`/ai/workspace/file` 捕获了 `SecurityException` 返回 400，`/upload` 没有捕获。
- 修复：`WorkspaceController.upload` 改为 `ResponseEntity<?>`，捕获
  `SecurityException | IllegalArgumentException` → 400 + `{"error": 原因}`；
  前端上传失败时优先展示 `err.response.data.error`。
- 补测：`WorkspaceControllerUploadTest` 3 项（正常 200、越界 400、绝对路径 400）。
- 验证：`curl` 实测 `HTTP=400` + `路径越界已拦截: ../escape.txt`。

### 2.4 Agent 跑完后刷新文件树仍命中旧缓存

- 现象：与 2.2 同源；`sendMessage` 的 `finally` 里只调 `loadWorkspaceTree()`，缓存不失效。
- 修复：新增 `refreshWorkspaceTree()`（清缓存但停留在当前目录），跑完一轮后调用；
  绑定/解绑仍用 `invalidateTreeCache()`（回到根目录）。
- 说明：这一条不影响离线测试，只有浏览器链路能暴露；已由 16 项冒烟覆盖“绑定后树显示真实文件”。

> 四条修复都没有放宽判定：路径校验、证据核对、交付校验、权限策略一行未动。

---

## 3. 回归结果

### 3.1 后端全量（最终，`mvn -o test`）

```
Tests run: 1553, Failures: 0, Errors: 0, Skipped: 9
BUILD SUCCESS
Total time: 01:29 min
```

| 轮次 | 结果 |
|---|---|
| 上一轮基线 | 1548 / 0 / 0 / 9 |
| 用户改动后 | 1550 / 0 / 0 / 9 |
| 本轮最终 | **1553 / 0 / 0 / 9**（+3 上传越界用例） |

日志：`evidence/bearcoding-final-final-regression-20261114/backend-full-test-final.log`

新增/相关模块：

```
TaskExecutionAuxiliaryShareTest      4 / 0
WorkspaceControllerUploadTest        3 / 0
WorkspaceControllerPickDirectoryTest 5 / 0
WorkspaceServiceTest                15 / 0
WorkspacePathsTest                   4 / 0
```

> 复现提醒：Maven 必须能写 `backend/target`。在受限沙箱里首次执行时 surefire 无法写报告，
> 汇总会显示 `Tests run: 0`（本次已遇到，切到可写环境后正常），不要把这个 0 当成“没测试”。

### 3.2 前端 node 测试

```
6 + 13 + 21 + 3 + 4 = 47 项，0 失败
```

日志：`frontend-node-tests.log`（逐文件执行，结果同上）。

> 说明：`npm run test:group` 用 `node --test` 会为每个文件起子进程；在受限沙箱里会
> `spawn EPERM`。本轮改用 `node <file>` 逐个在进程内执行，测的是同一批用例。
> 正常桌面环境直接 `npm run test:group` 即可。

### 3.3 lint 与生产构建

```
DONE  No lint errors found!
DONE  Build complete. The dist directory is ready to be deployed.
```

产物：`frontend/dist` 13 个文件 / 9,141,792 字节，入口 `js/app.384c163a.js`、`css/app.1e72d626.css`。
日志：`frontend-lint.log`、`frontend-build.log`。

### 3.4 打包进 jar

```
mvn -o clean package -DskipTests   → BUILD SUCCESS
target/bailianAI-3.0.0.jar         56,971,913 字节
SHA-256                            300C7AC11145EE0CF287F62A18A1052B864543EDFF4B36316B28525302922737
BOOT-INF/classes/static/index.html → app.384c163a.js / app.1e72d626.css
```

- 使用 `clean` 是必要的：不 clean 时 `target/classes/static` 会累积旧 bundle
  （本轮实测残留 `app.c24a773f.js`、`app.e647dcfc.js` 等 3 份），jar 体积虚增、排障容易看错版本。
- 打包时若旧后端仍从 `target\*.jar` 运行，`spring-boot:repackage` 会因文件被占用而
  `Unable to rename ... .jar.original` 失败；本轮已改为“先停旧进程/跑副本”再打包。

### 3.5 HTTP 冒烟（全新数据根，端口 18201）

29 / 29 通过：

| 组 | 覆盖 |
|---|---|
| 静态/SPA | `/`、`/chat`、`/models`、`/mcp` 200；未知 `/ai/*` 返回 404 JSON，不是首页 |
| 空库 | `/ai/models`、`/ai/conversations` 均为 `[]` |
| 会话 | 创建、列表、删除 |
| 上传 | 两个文件（含 `src/` 子目录）、根/子目录树、预览、下载字节一致 |
| 路径安全 | `../secret.txt` 400、`C:\Windows\win.ini` 400、上传 `../escape.txt` 400 |
| 绑定 | bind → `mode=bound` + 正确 root、树显示真实文件、预览真实内容 |
| 分组 | `/ai/workspaces` 出现绑定组、PATCH 改名、DELETE 删除 |
| 解绑/清空 | `mode=default`、清空后树为 `[]` |

日志：`smoke-http-fresh.log`、`smoke-http-results.json`、`smoke-http.ps1`。

### 3.6 浏览器冒烟（Edge 无头，端口 18200，最终 jar）

16 / 16 通过，**0 个 JS 错误 / 0 个 pageerror**：

| # | 检查 |
|---|---|
| 1–3 | `/chat` 标题 BearCoding、输入框、侧边栏 |
| 4–6 | 新对话、默认工作区 chip、工作区面板 |
| 7–9 | 上传 `hello.txt` → 树出现 → 预览内容正确 |
| 10–12 | 手填路径绑定真实目录 → 树显示 `real.txt` → 解绑 |
| 13–14 | `/models`、`/mcp` 渲染 |
| 15–16 | `/chat` 刷新深链仍可用；无 JS 错误 |

截图：`evidence/bearcoding-final-final-regression-20261114/screenshots/01-chat.png` … `09-dev-18190.png`
日志：`smoke-browser.log`、`smoke-browser-results.json`。

### 3.7 开发服务器（18190，最新源码）

`/chat`、`/models`、`/mcp` 均 200（带 `Accept: text/html`），80 条会话正常渲染，工作区面板可打开，0 JS 错误。
说明：dev-server 的 history fallback 只对 `Accept: text/html` 生效，用 `curl` 裸测会看到 404，这是预期行为，不是页面故障。

---

## 4. 当前服务与端口（本轮结束时）

| 角色 | 地址 | PID | 说明 |
|---|---|---|---|
| 手动验收后端 | `http://127.0.0.1:18186` | 25416 | 最终 jar 的副本，数据根 `%TEMP%\BearCoding-Final-M4\data`（80 会话、2 模型） |
| 手动验收前端 | `http://127.0.0.1:18190/chat` | 30172 | dev server，代理 → 18186，已 HMR 到最新 ChatView |
| 回归后端（有状态） | `http://127.0.0.1:18200` | 7160 | 浏览器冒烟用，数据根 `…\BearCoding-Final-regression-20261114\data` |
| 回归后端（干净库） | `http://127.0.0.1:18201` | 25748 | HTTP 冒烟用，数据根 `…\fresh-data` |
| 正式 8082 | — | 未占用 | 全程未触碰 |

> 18200/18201 是回归临时实例，确认报告后可直接结束进程；18186/18190 保留给用户手动验收。

---

## 5. 未验证项与已知限制（不阻断打包）

1. **真实模型任务**：本轮 0 次付费调用；SSE、工具审批、计划、子代理的“真模型”链路沿用上一轮证据。
2. **真实外部 MCP**：本轮未新增真实服务；18186 上的 `github` 是既有配置，未做新验收。
3. **原生文件夹选择弹窗**：`/ai/workspace/pick-directory` 的 Swing 弹窗无法自动化，只验证了
   “远程地址拒绝 / 未登录拒绝 / 取消 / 错误透传”四条单元测试；真人点击仍需手动验收。
4. **`frontend/e2e/workspace-groups.spec.js` 已过期**：脚本假设 8082 + 5173 + 登录页，
   当前是 `app.auth.mode=local` 且无登录墙，直接跑会在登录步骤失败。
   本轮用新的 `smoke-browser.js` 覆盖了同类路径；建议后续重写或删除该脚本。
5. **`directoryPathFromFiles` 目前无调用方**：函数与测试都正确，但 UI 未使用；
   桌面端接入 `File.path` 时应把它接到“上传文件夹”，否则建议连同测试一起删除，避免死代码。
6. **交付校验/命令证据的既有宽松点**（上一轮 §4.4 已登记）未在本轮改动。

---

## 6. 本轮文件变更清单

| 文件 | 变更 |
|---|---|
| `frontend/src/utils/workspaceUpload.js` | 修复文件夹路径反推（`D:` → `D:\myproj`） |
| `frontend/src/views/ChatView.vue` | 上传/清空后作废文件树缓存；新增 `refreshWorkspaceTree()`；上传失败展示后端原因 |
| `backend/.../controller/WorkspaceController.java` | `upload` 返回 `ResponseEntity`，越界/绝对路径 → 400 |
| `backend/.../controller/WorkspaceControllerUploadTest.java` | 新增 3 项测试 |
| `backend/src/main/resources/static/**` | 前端构建产物（打包用，建议不提交，见打包文档） |
| `docs/DESKTOP-PACKAGING.md` | 新增：桌面打包流程建议 |
| `README.md`、`ACCEPTANCE.md` | 更新测试数字与验证范围 |
| `evidence/bearcoding-final-final-regression-20261114/**` | 日志、脚本、截图、结果 JSON |

Git 状态：仍为“无提交、全部未跟踪”。本轮**没有**执行 `git add / commit / push`。

---

## 7. 结论

- 用户 15:24–16:56 的改动已经完整进入回归：**后端 1553 全绿、前端 47 全绿、lint/构建通过、jar 可运行、页面 16 项冒烟全过**。
- 回归额外抓到并修掉 4 个真实问题，其中 2 个（路径反推、文件树缓存）会直接影响用户体验，
  1 个（上传越界 500）影响 API 一致性。
- 当前代码与构建产物可以作为桌面打包的输入；下一步按 `docs/DESKTOP-PACKAGING.md` 执行，
  先冻结 Git 版本，再做 Electron + 精简 JRE 的封装。
