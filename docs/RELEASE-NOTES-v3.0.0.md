# BearCoding v3.0.0

首个公开发布版本。**本次是源码发布，不附带 Windows 便携包**——便携包尚未通过完整运行态验收，见下文「未验收项」。

> 这份草稿用于直接粘贴到 GitHub Release 的说明框；发布前请把「发布日期」和「校验值」补齐。

- 标签：`v3.0.0`
- 发布日期：待定
- 发布形态：源码（`Source code (zip)` / `Source code (tar.gz)`）
- 许可证：[MIT](../LICENSE)

---

## 这是什么

BearCoding 是一个本地运行的 Java AI Coding Agent。在你自己的机器上，用自然语言让模型读代码、定计划、调用工具、改文件和执行命令。工具调用经过权限检查，对话变长时由自研的上下文管理决定发给模型什么。

Agent 循环、计划、子代理、上下文投影、工具权限和 MCP 接入都在本仓库实现；Spring AI 只负责按 OpenAI 兼容协议调用模型。

## 本版包含

- **后端**（`backend/`）：Java 17 + Spring Boot 3.5.0 + Spring AI 1.0.0 + MyBatis + SQLite，265 个主源码文件、249 个测试文件，带 Maven Wrapper。
- **前端**（`frontend/`）：Vue 3 + Vue CLI 5 + Naive UI，含 `package-lock.json` 与 52 项 node 测试。
- **桌面外壳**（`desktop/`）：Electron 45.3.2 + electron-builder 26.18.0 源码与 `build-portable.ps1` 一键构建脚本。可独立于其它项目构建，但产物未验收。
- **文档**（`docs/`）：架构、上下文管理、MCP 与安全边界、桌面打包流程。
- **验收证据**（`evidence/`）：6 份 Markdown 报告。截图与原始日志属于本机产物，未随仓库发布。
- **许可证**：[LICENSE](../LICENSE)、[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)、[ASSETS.md](../ASSETS.md)。

## 运行要求

| 项 | 要求 |
|---|---|
| 操作系统 | 源码可在任意能跑 JDK 17 的系统上构建；桌面外壳**仅 Windows x64** |
| JDK | 17（含 `javac`，用于 Agent 的自动编译校验） |
| Node.js | 能运行 Vue CLI 5（开发与前端构建） |
| Maven | 3.9，或使用仓库自带的 `backend/mvnw` |
| 模型 | **由使用者自行配置**。支持 OpenAI 兼容协议的服务；本仓库不附带任何 Key |
| 网络 | 除调用你自己配置的模型服务外不需要联网。MCP 与联网搜索都是可选项 |

## 快速开始

```powershell
cd frontend; npm ci; npm run build; cd ..
Copy-Item frontend\dist\* backend\src\main\resources\static\ -Recurse -Force
cd backend; mvn -o test; mvn -DskipTests package; java -jar target/bailianAI-3.0.0.jar
```

打开 `http://127.0.0.1:8082/chat`，在 `/models` 里填模型地址与 Key。

数据写在 `%LOCALAPPDATA%\BearCoding`（可用 `JINGJING_DATA_DIR` 指定数据根）。首次启动自动建库，不需要拷贝任何人的数据库。

## 已验证

| 项目 | 结果 |
|---|---|
| 后端离线测试 | 1616 项运行 / 0 失败 / 0 错误 / 9 跳过 |
| 前端 node 测试 | 52 项通过 |
| 前端生产构建 + 产物进 jar | 通过 |
| 真实模型完整任务 | 通过一次：读文件 → 改文件 → `javac` → 运行 |
| 上下文摘要 / 工具结果退休 / 快照恢复 | 在受控阈值（5000）下通过 |
| 便携包构建（Vue → JAR → jlink → EXE） | 通过，退出码 0 |
| 便携包运行态（JAR/JRE 启动、`/chat` 200、401/200、仅回环、数据隔离） | 通过 |

## 未验收项与已知限制

1. **Windows 便携包没有随本版发布。** 完整 Electron 窗口从未成功启动：Electron 45.3.2 在本机报 `0xc0000005`，最小 Electron 应用同样复现。因此窗口创建、令牌注入、单实例、退出清理、导航白名单都没有运行态结论。请不要把本版的源码构建脚本理解为「桌面版已就绪」。
2. **便携包内嵌的 Java Runtime 是 Oracle JDK 17.0.12。** 该版本属 NFTC 许可，允许免费再分发（不得收费），但它是 Java 17 最后一个 NFTC 免费版本；2024-10 之后的安全补丁只通过 My Oracle Support / OTN 提供，因此该运行时缺少此后全部关键补丁更新。正式发布二进制前应换成仍在维护的 OpenJDK 17 发行版。
3. **真实外部 MCP 服务未验证。** 只用过本机自建的 MCP，外部服务的兼容性没有结论。
4. **默认 64000 token 预算下，工具结果退休不一定触发。** 已验证的退休发生在受控阈值（5000）下；默认预算通常没有预算压力。
5. **外部网页内容不触发污点升级。** MCP 工具结果会把会话标记为「读过不可信内容」，`webSearch` 抓回的网页正文不会。因此读过网页后，免审批的 `saveMemory` 仍可写入工作区记忆。需要更严边界时请关闭 `webSearch` 或收紧权限配置。
6. **仅 Windows x64**（桌面外壳）。没有 macOS / Linux 版本，没有安装版和自动更新。
7. **没有代码签名。** 自行构建出的 EXE 未签名，Windows SmartScreen 会拦一次。
8. **权限是应用内规则**，不是容器或操作系统级隔离。
9. **自动编译校验只支持 Java**（`javac`）。
10. **聊天页顶部头像引用外部图片 URL**，离线时不显示。素材授权状态见 [ASSETS.md](../ASSETS.md)。

## 安全说明

- 仓库不包含任何数据库、日志、真实 API Key、MCP 凭证或个人配置。
- 模型 Key 存在使用者本机 SQLite；MCP 凭证是 AES-GCM 密文，密钥材料在数据根 `secrets` 下并由 Windows DPAPI 保护。
- 密钥文件丢失或损坏时启动失败，不会生成新密钥去读旧密文。
- Electron 模式每次启动生成随机令牌，只通过环境变量传给后端；令牌缺失直接启动失败，不退回无鉴权。
- 发现安全问题请通过 GitHub 的私密渠道报告，不要开公开 issue 贴出细节。

## 许可证

BearCoding 自研源码采用 MIT。第三方组件、字体、Electron / Chromium 与 Oracle JDK 运行时各有自己的许可证与保留声明，见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)。

## 校验

源码包校验值（发布时填写）：

```text
<SOURCE_ZIP_SHA256>  BearCoding-3.0.0-source.zip
```

本机构建产物参考值（**不随本版发布**，仅供复现时对照）：

```text
04CD93CFE6AC617A1F8698F14CAF55E4476349094794BD1DDC7DE76C6881F529  BearCoding-3.0.0-portable.exe
62F67462DE7DFD1BE5C83BAABA45E5D4CC306A07A19DB8C9C2BFA05037461684  bearcoding-backend.jar
```
