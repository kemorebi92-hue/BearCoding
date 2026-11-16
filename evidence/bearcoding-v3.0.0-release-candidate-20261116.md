# BearCoding v3.0.0 Windows 便携版发布候选验收（2026-11-16）

> **2026-11-16 18:10 更正**：本文件第 3 节的 `0x80000003` / NSIS 失败结论已被后续根因报告取代。根因是 `D:\BearCoding-Final` 的 **Low Mandatory Level** 被 EXE 继承，不是 Electron 版本或 `%TEMP%` 不可写。干净目录重建后的无参数双击与核心任务已通过。请以 [bearcoding-v3.0.0-integrity-rootcause-20261116.md](bearcoding-v3.0.0-integrity-rootcause-20261116.md) 为准；本文件保留为当时的原始记录。

对象：`desktop/release/BearCoding-3.0.0-portable.exe`
SHA-256：`E26636B6B9F2F5AA5A011AE0D2B17CAEDE0780B0E2332664D3BB6BC816F77515`
大小：209,526,735 字节（约 199.8 MiB）
构建时间：2026-11-16T16:31:41+08:00

本文件只记录本轮**实际执行过**的检查与结果。未执行的项一律写「未验证」，不按经验推断。


---

## 1. 构建来源

| 项 | 值 |
|---|---|
| 构建用 JDK | Eclipse Adoptium Temurin 17.0.21+9（`IMPLEMENTOR="Eclipse Adoptium"`，`JAVA_VERSION_DATE=2026-10-20`） |
| JDK 安装包 | `OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip`，SHA-256 `98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b`（与 Adoptium API 公布值一致） |
| JDK 来源 | Adoptium API `assets/latest/17/hotspot` → GitHub `adoptium/temurin17-binaries` release `jdk-17.0.21+9` |
| jlink 模块 | `java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.instrument,java.logging,java.management,java.security.sasl,java.naming,java.net.http,java.rmi,java.scripting,java.security.jgss,java.transaction.xa,java.sql,java.compiler,jdk.compiler,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.unsupported` |
| Electron | 45.3.2（官方缓存 zip，SHA-256 与 `node_modules/electron/checksums.json` 一致） |
| electron-builder | 26.18.0 |
| Node / npm | 22.20.0 / 10.9.3 |
| Maven | Maven Wrapper 3.9.14 |
| 内置 JAR SHA-256 | `178EACA14F5E06333EE00D0955065FBA8DE3DF7B89E8AC0E4677C38237583AF0` |
| 构建记录 | `desktop/.build/build-info.json` |

---

## 2. 通过项

| # | 检查 | 证据 |
|---|---|---|
| 1 | 便携运行时自带 `javac` | `resources/jre/bin/javac.exe`（17.0.21.0），`jre\bin\javac.exe -version` → `javac 17.0.21` |
| 2 | `CodeVerifier` 在便携模式下真的跑起来了 | 核心任务日志 `[VerifyCompile] ran=true success=true elapsed=542ms` |
| 3 | Java 文件修改后自动编译校验通过 | 同上；且改后文件用随包 `javac` 独立编译退出码 0，`java` 运行输出 `Hello, BearCoding v3.0.0` |
| 4 | 路径解析单测 | `npm test`：9 项通过 / 0 失败 |
| 5 | 便携包运行态（非窗口） | `test/packaged-runtime-verify.ps1`：`/chat` 200、无令牌 `/ai/models` 401、带令牌 200、仅回环、SQLite 与 secrets 落盘、令牌不在 stdout、Vue 页面渲染、无 console/page error |
| 6 | 成品 EXE 窗口显示 | `windowTitle=BearCoding`、`windowHref=http://127.0.0.1:<port>/chat`，截图 1483×954（211 KB，非纯色） |
| 7 | 模型配置页 | 页面文本含「请先配置模型」「去配置模型」「配置模型」「选择模型」；`/ai/models/status` 返回 `modelConfigured=false` |
| 8 | 桌面令牌注入 | 渲染进程同源 `fetch('/ai/models')` → 200，body `[]` |
| 9 | 无令牌拒绝 | 外部 `GET /ai/models` → 401，`GET /user/current` → 401 |
| 10 | 单实例 | 第二个实例启动后退出；Java 进程数仍为 1 |
| 11 | 关闭后 Java 子进程清理 | 关窗后 app 进程与 Java 子进程都消失 |
| 12 | 数据目录 | 隔离 `LOCALAPPDATA` 下生成 `BearCoding\{data\jingjing.db, secrets\, logs\, workspaces\}` |
| 13 | 导航限制 | `location.href='https://example.com/'` 后 URL 未变；`window.open` 返回 `null` |
| 14 | 令牌不泄露 | `backend.log` / app stdout / stderr 中无令牌明文；日志含 `[Auth] 桌面鉴权已启用` |
| 15 | 包内许可证 | `resources\licenses\LICENSE.txt`、`resources\licenses\THIRD-PARTY-NOTICES.md`、`LICENSE.electron.txt`、`LICENSES.chromium.html`、`resources\jre\legal\java.base\{LICENSE,ADDITIONAL_LICENSE_INFO,ASSEMBLY_EXCEPTION}` 均在 EXE 内；两份 licenses 副本与仓库版本 SHA-256 完全一致 |
| 16 | 第三方头像外链已移除 | 构建产物 `static/js/*.js` 中 `kkimgs`/`yisou`/`duitang` 0 命中；JAR 内含 `static/img/avatar-bear.4b4f5226.svg` |
| 17 | 包内无用户数据 | 解包 `app-64.7z` 后无 `.db` / `.key` / `secrets` / `test-user-data` / `logs` 条目 |
| 18 | 包内无凭证 | JAR 内 `static/js`、`application.yml` 无 `sk-` 字面量；`apiKey` 命中项为 `apiKey:""` 与 `apiKey:i.apiKey` 表单绑定；`local-token: ${BEARCODING_LOCAL_TOKEN:}` 只是占位符 |

窗口与核心任务的执行脚本：`desktop/test/packaged-window-verify.ps1`、`desktop/test/portable-core-task-verify.ps1`、`desktop/test/mock-model-server.cjs`、`desktop/test/window-cdp.cjs`。日志与截图在 `desktop/test-user-data/`（被 `.gitignore` 忽略）。

---

## 3. 失败项 / 未验收项

| # | 项 | 结果 |
|---|---|---|
| 1 | **成品 EXE 双击（默认参数）直接启动** | **失败**：退出码 `0x80000003`（STATUS_BREAKPOINT），窗口不出现，数据目录不创建 |
| 2 | 默认参数下的 Chromium 沙箱 | 失败：加 `--no-sandbox` 后变成 `0xC0000005`（`electron.exe+0x7f1e34b`，每次相同） |
| 3 | Electron 版本回退能否修复 | 否：44.4.0（asar 与非 asar 两种）成品 EXE 全部 `0x80000003`，已回退到 45.3.2 |
| 4 | 真实外部 MCP 服务 | 未验证（沿用上一轮结论） |
| 5 | 真实第三方模型服务 | 未验证（本轮用受控 OpenAI 兼容测试服务；上一轮 2026-11-14 有过一次真实模型通过记录） |
| 6 | 截图视觉检查 | 未做（当前模型不支持图像输入；只做了「非纯色、尺寸合理」的程序化校验） |
| 7 | 代码签名 | 未做 |

窗口验收因此是在 **`--no-sandbox` + 显式 `--user-data-dir`（指向已存在目录）** 两个前提下完成的。这两点不改变包内代码，但确实偏离了默认启动方式。

已排除的归因（详见 `desktop/README.md` 第 9 节）：不是本 agent 会话的进程环境（`schtasks /it` 同样复现）、不是「Chromium 整体不可用」（同机 Edge 正常）、不是单纯 Electron 版本（空应用 44.4.0/37.2.0 能默认启动，但同一份成品应用在 44.4.0 下不行）。**根因未定位。**

---

## 4. 仍需人工确认

1. **在一台普通 Windows 11 上双击同一份 EXE 能否正常开窗。** 这是本轮唯一没通过的核心验收项。
2. **5 个位图素材的来源与授权**：`frontend/src/assets/image.png`、`ims3.jpg`、`ims.webp`、`frontend/public/favicon.ico`、`desktop/build/icon.png`。第三方头像外链已替换为自绘 SVG，但这 5 项仍无来源记录，见 `ASSETS.md`。
3. **是否接受当前 Electron 45.3.2 + 已知启动限制**，或改走「换 Electron 版本 / 非 asar 打包」路线并重跑第 2 节全部脚本。
4. **Release 附件清单与发布说明**是否按 `docs/RELEASE-NOTES-v3.0.0.md` 的草稿发布。

---

## 5. 本轮未执行的正式发布动作

按约定：没有 `git commit`、`git push`、`git tag`，没有创建 GitHub Release，没有上传任何附件。本地 `main` 与 `origin/main` 各有一个提交，两者 **tree 完全相同**（`a4e044dd983ce3751d0aadd4b5b53af377ea5cf4`），差异仅在作者/提交时间；未做任何重置、覆盖或强推。
