# BearCoding

BearCoding 是一个用 Java 开发的本地 AI 编程助手。选好工作区、配好模型后，你可以用自然语言让它读代码、改文件、运行命令，并在页面上看到每一步的结果。

Java 后端负责运行任务循环。模型提出工具请求，后端核对权限，在需要时等你批准，执行后再把结果交回模型。对话变长时，后端有条件地缩短旧材料，模型需要时可以查回旧聊天或有快照的工具结果。

[下载 Windows x64 便携版 v3.0.0](https://github.com/kemorebi92-hue/BearCoding/releases/tag/v3.0.0) · [从源码运行](#从源码运行推荐) · [架构说明](docs/ARCHITECTURE.md)

## 快速体验

1. 启动 BearCoding，在「模型配置」里填好一个支持工具调用的模型。MCP 外部工具和联网搜索均可选。
2. 把 [Java 样例目录](backend/docs/eval/fixtures/T02) 复制到临时位置，保留 `src/App.java`，再把这个副本绑定为工作区。
3. 在聊天里发送下面的任务，按需确认文件修改和命令执行。示例中的 `javac` 和 `java` 需要能在命令工具里运行，从源码启动时可用已安装的 JDK 17。

> 修复 `src/App.java`。用 `javac -encoding UTF-8 -d out src/App.java` 编译，再用 `java -cp out App` 运行。确认工具结果里实际输出了 `ok`，然后告诉我改了哪里。

上面是可自行尝试的样例。在一次有记录的真实模型验收中，BearCoding 读取并修复了 `Calc.java`，编译后运行得到 `2 + 3 = 5`。同份报告也记录了本机自建 MCP 工具从申请批准到收到返回值的过程。 [验收报告](evidence/bearcoding-final-real-model-browser-e2e-acceptance-20261114.md#L64)

## 主要功能

- **任务编排与审批。** Spring AI 提供模型接入和工具接口，Java 后端负责循环。模型可以列计划，后端会核对相关步骤的执行记录，并在工具需要确认时等你批准。 [架构与流程](docs/ARCHITECTURE.md)
- **整理材料与找回原文。** 按条件摘要旧聊天，每轮少带符合条件的旧材料。聊天原话可按编号读取，长工具结果可凭有效快照引用读回。 [上下文管理](docs/CONTEXT-MANAGEMENT.md)
- **外部工具与子代理。** 通过 MCP（模型上下文协议）接入本地进程或网络服务，调用前检查权限。也可以把只读调研交给使用独立上下文的 Explore 子代理，由主代理接收报告。 [MCP、权限与安全边界](docs/MCP-AND-SECURITY.md)
- **工作区与记忆。** 项目约定跟目录走，个人偏好跟用户走。后者保存或删除要经过批准。会话和配置保存在本机 SQLite 数据库。 [数据与隐私](#数据与隐私)

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
| 历史摘要与原话查回 | 通过（默认配置） | 真实模型生成旧聊天摘要后按消息编号查回原话 |
| 工具结果退休与快照读回 | 通过（受控阈值 5000） | 测试中临时调低软线以触发退休。默认 64000 的那组未触发 |
| 本机自建 MCP | 通过（一次） | 真实模型发现 `echo-local`，经审批调用并收到回声 |
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
