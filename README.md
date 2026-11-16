# BearCoding

BearCoding 是一个面向本地代码工作区的 Java AI Coding Agent。用户提出任务后，Java 后端组织对话历史、记忆和计划，调用用户配置的大模型。模型可以直接回答，也可以请求读取文件、修改代码、执行命令或调用其他工具。

- 版本：`3.0.0`
- 形态：Spring Boot 后端 + Vue 3 前端 + 可选 Electron 桌面外壳（Windows x64）
- 状态：源码可用；**Windows x64 便携版 v3.0.0 已发布**（[Releases](https://github.com/kemorebi92-hue/BearCoding/releases/tag/v3.0.0)）。成品在普通目录、无参数双击已通过窗口、后端、数据目录和一次完整核心任务；只有从带 `Low Mandatory Level` 标签的构建目录产出的 EXE 不能直接双击，见「已知限制」第 1 条
- 许可证：[MIT](LICENSE)

---

## 这是什么

BearCoding 自己实现 Agent 循环：接收模型的工具请求，检查执行权限，必要时等待用户审批，将执行结果送回模型，再决定是否进入下一轮。Spring AI 提供模型接入以及消息、工具调用的基础接口；循环编排、权限审批、上下文管理、子代理和 MCP 工具接入由本项目实现。Vue 页面通过 SSE 展示执行过程，SQLite 保存会话与配置。

模型、MCP 服务器、联网搜索全部由使用者自行配置。没有配置也能启动，只是不能聊天。

## 主要功能

- **Agent 循环**：多轮把工具结果送回模型，直到给出最终回答，或触及轮次 / 窗口 / 任务预算上限。
- **计划**：模型可以列出步骤并更新状态；计划注入主循环，不是另一套无人值守执行器。
- **子代理**：Explore（只读调研）可并行，Implement（改文件）同时只允许一个；子房间用更短上下文，回来只交摘要。
- **工具与审批**：文件读写、搜索、命令执行先过权限网关；写文件、删除和高危命令默认每次确认。
- **上下文管理**：超预算时摘要历史；有界工作集把更早的助手回复投影掉；带签名快照的工具结果换成仍可用 `readFile` 读回的省略标记。
- **按需检索**：`searchContext` 查本会话消息、当前用户长期记忆和当前计划，再按定位读取。
- **记忆**：工作区里的 Markdown 每轮注入该工作区的对话；跨会话用户记忆必须经你批准才写入或删除。
- **MCP Host**：连接 stdio 或 Streamable HTTP 的外部工具，不配置也能启动。
- **本地存储**：会话、消息、模型配置、MCP 配置都存在使用者本机的 SQLite 里。
- **SSE**：正文、工具过程、计划与审批卡片随流推送到页面。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17，Spring Boot 3.5.0，Spring AI 1.0.0，MyBatis 3.0.3，sqlite-jdbc 3.47.1.0 |
| 外部工具 | MCP Java SDK 1.1.0（stdio 与 Streamable HTTP） |
| 前端 | Vue 3，Vue CLI 5，Vue Router 4，Naive UI 2，marked + DOMPurify |
| 桌面 | Electron 45.3.2，electron-builder 26.18.0，jlink 生成的 Java Runtime |
| 本机密钥 | Windows DPAPI（JNA）+ AES-GCM 密文 |

## 运行方式

### 从源码运行（推荐）

前置：JDK 17（含 `javac`）、Node.js（能跑 Vue CLI 5）、Maven 3.9 或仓库自带的 `backend/mvnw`。

```powershell
# 1. 前端：装依赖并构建
cd frontend
npm ci
npm run build
cd ..

# 2. 把前端产物放进后端（只跑 jar 时需要；开发时用 npm run serve 代替）
Copy-Item frontend\dist\* backend\src\main\resources\static\ -Recurse -Force

# 3. 后端：测试并打包
cd backend
mvn -o test
mvn -DskipTests package
java -jar target/bailianAI-3.0.0.jar
```

打开 `http://127.0.0.1:8082/chat`。进程只监听回环地址。

`mvn -o` 只用本机 Maven 缓存。缓存不完整时去掉 `-o` 重跑一次。

前端热更新开发：

```powershell
cd frontend
npm run serve        # 5173，把 /ai、/api、/user 代理到 8082
```

要连别的后端，启动前设置 `VUE_APP_PROXY_TARGET`（例如 `http://127.0.0.1:18083`）。它只影响开发服务器。

### 配置模型

在页面 `/models` 里填写模型服务地址与 API Key，保存后进入 SQLite。没有 Key 时后端仍能启动，聊天会提示先配置模型。不要把 Key 写进 `application.yml` 或提交进 Git。

联网搜索是可选的：设置 `SEARCH_API_KEY`，或一次性导入到数据根的 `secrets\search-credential.bin`。不设置时 `webSearch` 不可用，应用照常启动。

### Windows 便携包（v3.0.0 已发布）

已发布成品在 [Releases](https://github.com/kemorebi92-hue/BearCoding/releases/tag/v3.0.0)：下载 `BearCoding-3.0.0-portable.exe`，放在任意普通目录（下载目录、桌面均可）双击即可，不需要安装 Java。

`desktop/` 是独立的 Electron 外壳源码，可从本仓库独立构建：

```powershell
cd desktop
npm ci
npm run build:portable     # Vue → Spring Boot JAR → jlink Runtime → portable EXE
```

**构建注意事项（只影响自己构建的人，不影响下载到的成品）**：`build-portable.ps1` 默认输出到 `desktop/release`。如果构建工作区带 NTFS `Low Mandatory Level` 标签（本机 `D:\BearCoding-Final` 就是），生成的 EXE 会继承该标签，双击时 Windows 以 Low integrity 启动它，NSIS 无法写普通 `%TEMP%`。构建脚本会直接拒绝这种产物，请把输出目录指向普通目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1 `
  -OutputDir D:\BearCoding-Release-Candidate\build
```

`--no-sandbox` 只是排查参数，不是修复方案。根因与证据见 [desktop/README.md](desktop/README.md) 第 9 节。

已发布产物的身份（干净目录构建）：

```text
文件名    BearCoding-3.0.0-portable.exe
路径      D:\BearCoding-Release-Candidate\BearCoding-3.0.0-portable.exe（构建输出在 .\build\）
大小      209,525,573 字节（199.8 MiB）
SHA-256   EFF3154031D13A1403CBCA5B1DEBEB0B9741A355FD512AAF02B4DF4F1082F4A5
JAR SHA-256 41FED9A09746443B562875B5F56314CDAC1D47C824B8779BEC52139A402CA997
构建时间  2026-11-16T18:03:48+08:00
构建来源  Temurin 17.0.21+9 + Electron 45.3.2 + electron-builder 26.18.0（详见 .build/build-info.json）
```

**这份 EXE 的验收状态**：

- 无参数启动（不传 `--no-sandbox`、不传 `--user-data-dir`），在默认 C: `%TEMP%` 下 NSIS 正常自解包；窗口 `Chrome_WidgetWin_1|BearCoding` 出现（1200×800），Java 后端启动，隔离数据目录生成 `jingjing.db`、`logs`、`secrets`、`workspaces`。
- 用 `desktop/test/portable-core-task-verify.ps1` + 受控 OpenAI 兼容测试服务，在干净构建的 JRE/JAR 上跑通了一次完整核心任务：读文件 → 改 Java 文件 → 自动 `javac` 校验（`[VerifyCompile] ran=true success=true`）→ 输出 `Hello, BearCoding v3.0.0`。
- 该 EXE 已作为 `v3.0.0` Release 附件发布，源码与附件来自同一个提交。

## 数据与隐私

数据在使用者自己的目录。源码默认 `app.data.mode=desktop`：设置了 `JINGJING_DATA_DIR` 就用该绝对路径（它表示数据根本身，不要再拼一层 `data`），否则用 `%LOCALAPPDATA%\BearCoding`。首次启动执行 `schema-sqlite.sql` 建库。

- 模型地址与 Key 存本地 SQLite；MCP 凭证是 AES-GCM 密文，密钥材料在数据根 `secrets` 下并由 Windows DPAPI 保护。
- 密钥文件丢失或损坏时启动失败，不会生成新密钥去读旧密文。
- 仓库不包含任何数据库、日志、真实 Key 或个人配置。
- Electron 模式（`app.auth.mode=desktop`）每次启动生成随机令牌，经 `BEARCODING_LOCAL_TOKEN` 传给后端；`/ai/**`、`/api/**`、`/user/**` 必须带 `X-BearCoding-Token`，令牌缺失直接启动失败，不退回无鉴权。

## 测试与证据

| 项目 | 结果 | 说明 |
|---|---|---|
| 后端离线测试 | **1616 项运行 / 0 失败 / 0 错误 / 9 跳过** | `mvn -o test`，不连真实模型、不连外部 MCP（Phase A 全量回归） |
| 前端 node 测试 | **52 项通过** | `npm run test:group` |
| 前端生产构建 | 通过 | `npm run build`，产物进 jar 后页面可加载 |
| 真实模型完整任务 | 通过（一次） | 读文件 → 改文件 → `javac` → 运行，输出正确（2026-11-14，真实模型） |
| 上下文摘要 / 退休 / 恢复 | 通过（受控阈值 5000） | 默认 64000 预算下未触发，见下 |
| 便携包构建 | 通过 | Vue → JAR → jlink(Temurin 17.0.21) → EXE，退出码 0，构建脚本自检全绿 |
| `desktop` 路径单测 | **9 项通过** | `npm test`（`test/paths.test.cjs`） |
| 便携包运行态（非窗口） | 通过 | `npm run test:runtime`：JAR/JRE 启动、`/chat` 200、401/200、仅回环、数据隔离、令牌不进 stdout |
| 成品 EXE 窗口验收 | **通过（普通目录）** | 未用 `--no-sandbox`、未用 `--user-data-dir`；CDP 检查仅额外加 `--remote-debugging-port`。窗口、模型配置页、令牌注入、无令牌 401、单实例、退出清理、数据目录、导航白名单全部实测 |
| 便携版核心任务（受控测试模型） | 通过 | 读文件 → 改 Java → 自动 `javac` 校验 `ran=true success=true` → 结果回显；隔离数据目录 |
| 便携版双击直接启动 | **通过（普通目录）** | 无参数启动、窗口出现；低完整性工作区内的产物除外，原因见下方第 1 条 |

证据原文在 `evidence/` 目录（只保留 Markdown 报告；截图与原始日志属于本机产物，未随仓库发布）。根因与最终验收见 [evidence/bearcoding-v3.0.0-integrity-rootcause-20261116.md](evidence/bearcoding-v3.0.0-integrity-rootcause-20261116.md)；旧候选报告保留为历史记录并已加更正说明。

## 已知限制

1. **从低完整性目录构建出的 EXE 不能直接双击**。如果构建工作区带 `Low Mandatory Level`（本机 `D:\BearCoding-Final` 就是），构建出的 EXE 会继承该标签；Windows 以 Low integrity 启动它，NSIS 无法写普通 `%TEMP%`，Electron 也可能在初始化阶段退出。构建脚本已会拒绝这种产物，请用 `-OutputDir` 指向普通目录；`--no-sandbox` 不是修复。这是构建目录属性，与 Release 里下载到的成品无关。详见 [desktop/README.md](desktop/README.md) 第 9 节。
2. **内置 Java Runtime 已换成 Eclipse Temurin 17.0.21+9**（GPLv2+CE，2026-10-20 发布，仍在维护的 LTS 线）。上一版的 Oracle JDK 17.0.12 属 NFTC 且 2024-10 后无免费安全补丁，已不再随包分发。构建来源、校验值与随包 `legal/` 文件见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 第 1.3 节。
3. **真实外部 MCP 未验证**。只用过本机自建的 MCP 服务，外部服务的兼容性没有结论。
4. **默认 64000 token 预算下，工具结果退休不一定触发**。已验证的退休发生在受控阈值（5000）下；默认预算通常没有预算压力，不能推断它总会触发。
5. **外部网页内容不触发污点升级**。MCP 工具结果会把会话标记为「读过不可信内容」，`webSearch` 抓回的网页正文不会；因此读过网页后，免审批的 `saveMemory` 仍可写入工作区记忆。需要更严的边界时请关闭 `webSearch` 或收紧权限配置。
6. **桌面外壳仅 Windows x64**。源码本身可在任意能跑 JDK 17 的系统上构建，但 Electron 外壳只有 Windows 打包配置，没有 macOS / Linux 版本，也没有安装版、自动更新和代码签名。
7. **权限是应用内规则**，不是容器或操作系统级隔离。路径检查和命令检查都在进程内完成。
8. **只支持 Java 的自动编译校验**（`javac`），其他语言没有等价检查。便携版内置运行时已包含 `jdk.compiler`，`jre\bin\javac.exe` 可用。
9. **素材授权仍未收口**：聊天页顶部的第三方头像 URL 已在本版删除，改为仓库内自绘的 `avatar-bear.svg`；但消息头像、页面背景、favicon 和 EXE 图标这 5 个位图仍无法确认来源与授权，需要你确认。见 [ASSETS.md](ASSETS.md)。
10. **EXE 未做代码签名**，首次运行会触发 SmartScreen；也没有自动更新。

## 文档

- [架构与 Agent 循环](docs/ARCHITECTURE.md)
- [上下文管理](docs/CONTEXT-MANAGEMENT.md)
- [MCP、权限与安全边界](docs/MCP-AND-SECURITY.md)
- [桌面打包流程](docs/DESKTOP-PACKAGING.md)
- [桌面外壳说明与已知限制](desktop/README.md)
- [后端说明](backend/README.md) · [前端说明](frontend/README.md)
- [验收清单](ACCEPTANCE.md)
- [v3.0.0 发布说明](docs/RELEASE-NOTES-v3.0.0.md)

## 许可证

BearCoding 自研源码采用 [MIT License](LICENSE)，版权年份 2026。

第三方组件、字体（SIL OFL 1.1）、Electron / Chromium 与 Eclipse Temurin 运行时各有自己的许可证和保留声明，见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。便携 EXE 内随包提供 `resources\licenses\LICENSE.txt`、`resources\licenses\THIRD-PARTY-NOTICES.md`、`LICENSE.electron.txt`、`LICENSES.chromium.html` 和 `resources\jre\legal\`。图片与图标资源的授权状态另见 [ASSETS.md](ASSETS.md)。
