# BearCoding

本地运行的 Java AI Coding Agent。在你自己机器的工作区里，用自然语言让模型读代码、定计划、调用工具、改文件、执行命令；每次工具调用都经过权限检查，对话变长时由自研的上下文管理决定发给模型什么。

- 版本：`3.0.0`
- 形态：Spring Boot 后端 + Vue 3 前端 + 可选 Electron 桌面外壳（Windows x64）
- 状态：源码可用；Windows 便携包**尚未通过完整运行态验收**，不作为本版发布物
- 许可证：[MIT](LICENSE)

---

## 这是什么

BearCoding 不是聊天接口的包装层。Spring AI 只负责按 OpenAI 兼容协议调用模型；任务循环、计划、子代理、上下文投影、工具权限和 MCP 接入都在本仓库里实现。

页面发起任务后，后端把当前对话、系统提示和必要历史交给模型。模型可以只回答，也可以调用文件、终端、计划和检索工具。需要确认的操作会停下来，等你在页面上点允许或拒绝。

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

### Windows 便携包（尚未作为发布物）

`desktop/` 是独立的 Electron 外壳源码，可从本仓库独立构建：

```powershell
cd desktop
npm ci
npm run build:portable     # Vue → Spring Boot JAR → jlink Runtime → portable EXE
```

产物在 `desktop/release/BearCoding-3.0.0-portable.exe`（约 192 MiB）。

**但本版不发布这个 EXE**：完整 Electron 窗口从未成功运行（本机 Electron 45.3.2 启动即 `0xc0000005`，最小 Electron 应用同样复现），因此窗口创建、令牌注入、单实例、退出清理都没有运行态验收。已完成的只有 JAR/JRE 启动、页面渲染、`/chat` 200、受保护 API 的 401/200 和数据隔离。细节见 [desktop/README.md](desktop/README.md)。

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
| 真实模型完整任务 | 通过（一次） | 读文件 → 改文件 → `javac` → 运行，输出正确 |
| 上下文摘要 / 退休 / 恢复 | 通过（受控阈值 5000） | 默认 64000 预算下未触发，见下 |
| 便携包构建 | 通过 | Vue → JAR → jlink → EXE，退出码 0 |
| 便携包运行态（非窗口） | 部分通过 | JAR/JRE 启动、`/chat` 200、401/200、仅回环、数据隔离 |
| 完整 Electron 窗口 | **未验证** | `0xc0000005`，最小 Electron 应用同样复现 |

证据原文在 `evidence/` 目录（只保留 Markdown 报告；截图与原始日志属于本机产物，未随仓库发布）。

## 已知限制

1. **完整 Electron 窗口未验收**。令牌注入、单实例、退出清理、导航白名单都没有在运行态确认过。不要把「EXE 能构建」当成「桌面版能用」。
2. **内置 Java Runtime 是 Oracle JDK 17.0.12**，属 NFTC 许可，可免费再分发（不得收费），但它是 Java 17 最后一个 NFTC 免费版本；2024-10 之后的安全补丁只走 My Oracle Support / OTN，因此该运行时缺少此后全部关键补丁。换成仍在维护的 OpenJDK 17 发行版是二进制发布前的必做项。
3. **真实外部 MCP 未验证**。只用过本机自建的 MCP 服务，外部服务的兼容性没有结论。
4. **默认 64000 token 预算下，工具结果退休不一定触发**。已验证的退休发生在受控阈值（5000）下；默认预算通常没有预算压力，不能推断它总会触发。
5. **外部网页内容不触发污点升级**。MCP 工具结果会把会话标记为「读过不可信内容」，`webSearch` 抓回的网页正文不会；因此读过网页后，免审批的 `saveMemory` 仍可写入工作区记忆。需要更严的边界时请关闭 `webSearch` 或收紧权限配置。
6. **桌面外壳仅 Windows x64**。源码本身可在任意能跑 JDK 17 的系统上构建，但 Electron 外壳只有 Windows 打包配置，没有 macOS / Linux 版本，也没有安装版、自动更新和代码签名。
7. **权限是应用内规则**，不是容器或操作系统级隔离。路径检查和命令检查都在进程内完成。
8. **只支持 Java 的自动编译校验**（`javac`），其他语言没有等价检查。
9. **聊天页顶部头像引用的是外部图片 URL**，离线环境下不显示。素材授权状态见 [ASSETS.md](ASSETS.md)。

## 文档

- [架构与 Agent 循环](docs/ARCHITECTURE.md)
- [上下文管理](docs/CONTEXT-MANAGEMENT.md)
- [MCP、权限与安全边界](docs/MCP-AND-SECURITY.md)
- [桌面打包流程](docs/DESKTOP-PACKAGING.md)
- [桌面外壳说明与已知限制](desktop/README.md)
- [后端说明](backend/README.md) · [前端说明](frontend/README.md)
- [验收清单](ACCEPTANCE.md)
- [v3.0.0 Release Notes 草稿](docs/RELEASE-NOTES-v3.0.0.md)

## 许可证

BearCoding 自研源码采用 [MIT License](LICENSE)，版权年份 2026。

第三方组件、字体（SIL OFL 1.1）、Electron / Chromium 与 Oracle JDK 运行时各有自己的许可证和保留声明，见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。图片与图标资源的授权状态另见 [ASSETS.md](ASSETS.md)。
