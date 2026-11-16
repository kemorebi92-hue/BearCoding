# BearCoding v3.0.0

首个公开发布版本，包含 **Windows x64 便携版 EXE** 与完整源码。

- 标签：`v3.0.0`
- 发布日期：2026-11-16
- 发布形态：源码（`Source code (zip)` / `Source code (tar.gz)`）+ Windows 便携 EXE
- 许可证：[MIT](https://github.com/kemorebi92-hue/BearCoding/blob/main/LICENSE)

---

## 这是什么

BearCoding 是一个本地运行的 Java AI Coding Agent。在你自己的机器上，用自然语言让模型读代码、定计划、调用工具、改文件和执行命令。工具调用经过权限检查，对话变长时由自研的上下文管理决定发给模型什么。

Spring AI 提供模型接入以及消息、工具调用的基础接口；Agent 循环、计划、子代理、上下文投影、工具权限和 MCP 接入由本项目实现。

## 本版包含

- **后端**（`backend/`）：Java 17 + Spring Boot 3.5.0 + Spring AI 1.0.0 + MyBatis + SQLite，265 个主源码文件、249 个测试文件，带 Maven Wrapper。
- **前端**（`frontend/`）：Vue 3 + Vue CLI 5 + Naive UI，含 `package-lock.json` 与 52 项 node 测试。
- **桌面外壳**（`desktop/`）：Electron 45.3.2 + electron-builder 26.18.0 源码与 `build-portable.ps1` 一键构建脚本。
- **文档**（`docs/`）：架构、上下文管理、MCP 与安全边界、桌面打包流程。
- **验收证据**（`evidence/`）：Markdown 报告记录已执行检查。截图与原始日志属于本机产物，未随仓库发布。
- **许可证**：[LICENSE](https://github.com/kemorebi92-hue/BearCoding/blob/main/LICENSE)、[THIRD-PARTY-NOTICES.md](https://github.com/kemorebi92-hue/BearCoding/blob/main/THIRD-PARTY-NOTICES.md)、[ASSETS.md](https://github.com/kemorebi92-hue/BearCoding/blob/main/ASSETS.md)。

## 便携版运行要求

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10/11 x64 |
| 额外依赖 | **不需要**安装 Java 或 Node.js：EXE 内自带 Temurin 17.0.21 运行时（含 `javac`）与后端 |
| 数据目录 | `%LOCALAPPDATA%\BearCoding`（首次启动自动建库，不需要拷贝任何人的数据库） |
| 模型 | **由使用者自行配置**。支持 OpenAI 兼容协议的服务；本包不附带任何 Key |
| 网络 | 除调用你自己配置的模型服务外不需要联网。MCP 与联网搜索都是可选项 |
| 磁盘 | 约 200 MiB 的 EXE；运行时会在临时目录解包，需要约 500 MiB 空闲空间 |

源码构建另需 JDK 17、Node.js 22.12+、Maven 3.9（或仓库自带 `backend/mvnw`）。

## 快速开始（便携版）

1. 下载 `BearCoding-3.0.0-portable.exe`，放到任意普通目录（下载目录、桌面均可），双击运行。首次运行可能被 SmartScreen 拦一次（未签名）。
2. 在左侧进入「模型配置」，填写模型服务地址、模型名与 API Key，保存。
3. 回到聊天页，绑定一个工作区目录，然后直接描述任务。

数据全部在本机：模型 Key 存在本地 SQLite，MCP 凭证是 AES-GCM 密文并由 Windows DPAPI 保护。

> 从源码自己构建时，请把输出目录指向普通目录（`-OutputDir`）。带 NTFS `Low Mandatory Level` 标签的工作区里构建出的 EXE 会继承该标签，导致 NSIS 无法写普通 `%TEMP%`。这只是构建目录属性，与下载到的成品无关；`--no-sandbox` 不是修复方案。详见 [desktop/README.md](https://github.com/kemorebi92-hue/BearCoding/blob/main/desktop/README.md) 第 9 节。

## 已验证

| 项目 | 结果 |
|---|---|
| 后端离线测试 | 1616 项运行 / 0 失败 / 0 错误 / 9 跳过 |
| 前端 node 测试 | 52 项通过 |
| 前端生产构建 + 产物进 jar | 通过 |
| 真实模型完整任务（2026-11-14） | 通过一次：读文件 → 改文件 → `javac` → 运行 |
| 上下文摘要 / 工具结果退休 / 快照恢复 | 在受控阈值（5000）下通过 |
| 便携包构建（Vue → JAR → jlink(Temurin 17.0.21) → EXE） | 通过，构建脚本自检全绿 |
| `desktop` 路径单测 | 9 项通过 |
| 便携包运行态（JAR/JRE 启动、`/chat` 200、401/200、仅回环、数据隔离） | 通过 |
| **成品 EXE 窗口验收** | 通过（普通目录；未用 `--no-sandbox`、未用 `--user-data-dir`；CDP 检查仅额外加 `--remote-debugging-port`）：窗口显示、模型配置页、令牌注入（同源 200 / 无令牌 401）、单实例、关闭后 Java 子进程清理、数据目录落盘、外部导航拦截、`window.open` 拒绝 |
| **便携版核心任务（受控测试模型）** | 通过：读文件 → 改 Java → 自动 `javac` 校验 `ran=true success=true` → 结果回显 |
| **便携版双击直接启动** | 通过（普通目录、无参数；不使用 `--no-sandbox`） |

窗口验收在隔离的 `APPDATA` / `LOCALAPPDATA` / `TEMP` 下进行，不读写真实用户数据目录；核心任务使用脚本化的 OpenAI 兼容测试服务，不联网、不使用真实 Key。

## 与上一轮候选版本相比的变化

1. **内置运行时换成仍在维护的 OpenJDK 17**：Eclipse Temurin 17.0.21+9（GPLv2+CE，2026-10-20 发布），替换原来的 Oracle JDK 17.0.12（NFTC，2024-10 后无免费安全补丁）。
2. **便携版现在能真正编译 Java**：jlink 模块集补上了 `java.compiler,jdk.compiler`，`resources\jre\bin\javac.exe` 随包存在。之前缺这个模块时，改完 Java 文件后的自动编译校验会静默退化成「未验证：未找到 javac」。
3. **包内补齐许可证**：`resources\licenses\LICENSE.txt`（MIT）与 `resources\licenses\THIRD-PARTY-NOTICES.md`（前端依赖、字体 OFL、Electron、Chromium、Temurin），加上原有的 `LICENSE.electron.txt`、`LICENSES.chromium.html` 和 `resources\jre\legal\`。
4. **删除第三方头像外链**：聊天页顶部头像不再请求 `kkimgs.yisou.com`（堆糖第三方图片），改为仓库内自绘的 `avatar-bear.svg`。离线也能显示，且不再向第三方暴露使用者 IP/UA。
5. **Electron 版本保持 45.3.2**。之前把默认启动失败误判为 Electron 版本问题；根因是产物继承了构建目录的 Low integrity 标签，已用普通目录的无参数启动复核。`--no-sandbox` 只是排查参数，不是发布修复。

## 未验收项与已知限制

1. **从低完整性目录构建出的 EXE 不能直接双击。** 如果工作区目录带 NTFS `Low Mandatory Level`，在其中生成的 EXE 会继承该标签；Windows 以 Low integrity 启动它，NSIS 无法写普通 `%TEMP%`，Electron 也可能在初始化阶段退出。把同一份 EXE 复制到普通目录后，无参数双击已通过（窗口、后端、数据目录均正常）。构建请使用 `-OutputDir` 指向普通目录；`--no-sandbox` 不是修复。**从本 Release 下载的成品不受影响。**
2. **真实外部 MCP 服务未验证。** 只用过本机自建的 MCP，外部服务的兼容性没有结论。
3. **默认 64000 token 预算下，工具结果退休不一定触发。** 已验证的退休发生在受控阈值（5000）下；默认预算通常没有预算压力，不能推断它总会触发。
4. **外部网页内容不触发污点升级。** MCP 工具结果会把会话标记为「读过不可信内容」，`webSearch` 抓回的网页正文不会。需要更严边界时请关闭 `webSearch` 或收紧权限配置。
5. **仅 Windows x64**。没有 macOS / Linux 版本，没有安装版和自动更新。
6. **没有代码签名。** EXE 未签名，SmartScreen 会拦一次。
7. **权限是应用内规则**，不是容器或操作系统级隔离。
8. **自动编译校验只支持 Java**（`javac`）。
9. **图片素材授权尚未全部确认。** 顶部头像已改为自绘 SVG；但消息头像、页面背景、favicon、EXE 图标这 5 个位图仍无法确认来源与授权，详见 [ASSETS.md](https://github.com/kemorebi92-hue/BearCoding/blob/main/ASSETS.md)。这些素材不在 MIT 覆盖范围内。
10. **EXE 未做逐文件二进制成分扫描**，只核对了文件级成分（JAR、JRE、licenses、Electron/Chromium 声明）。

## 关于 1.0 历史演示包

`BearCoding 1.0.0.exe` 是更早的一次桌面外壳练习产物，**不是本版本**：外壳是 `bearcoding-desktop-lab` 1.0.0，内置的是 2026-11-14 的 3.0.0 后端快照，JRE 为 Oracle 17.0.12（NFTC，且没有 `javac` 模块），前端仍含 `kkimgs.yisou.com` 第三方头像外链，包内也没有 BearCoding MIT 许可证与第三方声明。它不属于 v3.0.0 的发布物，**不作为本 Release 的附件**。

## 安全说明

- 仓库与发布包不包含任何数据库、日志、真实 API Key、MCP 凭证或个人配置。
- 模型 Key 存在使用者本机 SQLite；MCP 凭证是 AES-GCM 密文，密钥材料在数据根 `secrets` 下并由 Windows DPAPI 保护。
- 密钥文件丢失或损坏时启动失败，不会生成新密钥去读旧密文。
- Electron 模式每次启动生成随机令牌，只通过环境变量传给后端；令牌缺失直接启动失败，不退回无鉴权。后端只监听 `127.0.0.1`。
- 渲染进程 `nodeIntegration: false`、`contextIsolation: true`、`sandbox: true`；外部导航与新窗口都被拦截。
- 发现安全问题请通过 GitHub 的私密渠道报告，不要开公开 issue 贴出细节。

## 许可证

BearCoding 自研源码采用 MIT。第三方组件、字体、Electron / Chromium 与 Eclipse Temurin 运行时各有自己的许可证与保留声明，见 [THIRD-PARTY-NOTICES.md](https://github.com/kemorebi92-hue/BearCoding/blob/main/THIRD-PARTY-NOTICES.md)；便携 EXE 内随包提供这些文本。

## 校验

```text
EFF3154031D13A1403CBCA5B1DEBEB0B9741A355FD512AAF02B4DF4F1082F4A5  BearCoding-3.0.0-portable.exe
```

文件大小：209,525,573 字节（约 199.8 MiB）。

```text
41FED9A09746443B562875B5F56314CDAC1D47C824B8779BEC52139A402CA997  bearcoding-backend.jar（EXE 内 resources/app/）
```

同一份值也随附在 `SHA256SUMS.txt` 中，可直接用 `certutil -hashfile BearCoding-3.0.0-portable.exe SHA256` 或 `Get-FileHash` 复核。

## 构建来源（供复现对照）

| 项 | 值 |
|---|---|
| 源码 | 本 Release 的 `v3.0.0` 标签指向的提交 |
| 构建时间 | 2026-11-16T18:03:48+08:00 |
| 构建用 JDK | Eclipse Temurin 17.0.21+9（Adoptium 官方构建） |
| Temurin 安装包 SHA-256 | `98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b` |
| jlink 模块集 | 含 `java.compiler,jdk.compiler`（提供随包 `javac`），完整清单见 `desktop/build-portable.ps1` |
| Electron | 45.3.2（官方缓存 zip，SHA-256 与 `node_modules/electron/checksums.json` 一致） |
| electron-builder | 26.18.0 |
| Node.js / npm | 22.20.0 / 10.9.3 |
| Maven | Maven Wrapper 3.9.14 |

完整的排查过程、进程完整性级别矩阵与本机命令记录在 [evidence/bearcoding-v3.0.0-integrity-rootcause-20261116.md](https://github.com/kemorebi92-hue/BearCoding/blob/main/evidence/bearcoding-v3.0.0-integrity-rootcause-20261116.md)。
