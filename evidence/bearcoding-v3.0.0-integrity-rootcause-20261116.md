# BearCoding v3.0.0 便携版 NSIS / Electron 启动失败根因报告

日期：2026-11-16

范围：`D:\BearCoding-Final`、`D:\BearCoding-Desktop-Lab\dist`、GitHub 公开源码

约束：未访问 `D:\JingJingAI-V3`；未全盘搜索；未执行 reset / clean / commit / push / tag / Release / 上传。

> **2026-11-16 发布时更新**：本报告写于发布动作之前，第 6、8、10 节当时留下的「发布前必须由用户本人提交推送」「公开 `main` 不能复现本 EXE」等临时阻断语句已经解决。相关改动随 `v3.0.0` 发布提交进入 `main`，Release 附件与 GitHub 自动生成的 Source code 来自同一个提交。本文件其余内容保持原样，作为当时的原始记录。

---

## 1. 结论摘要

当前 `BearCoding-3.0.0-portable.exe` 的 NSIS 错误 **不是 NSIS、Electron 45.3.2、`%TEMP%` 不可写或应用代码的问题**，而是：

> `D:\BearCoding-Final` 及其子目录带 NTFS **Low Mandatory Level** 完整性标签；在该目录内构建的 EXE 继承该标签。Windows 以 **Low integrity (0x1000)** 启动它，低完整性进程不能写普通用户的 `%TEMP%`（Medium integrity），NSIS 在创建临时文件时立即报：
>
> `Error writing temporary file. Make sure your temp folder is valid.`

同一份 EXE 复制到普通目录后，文件不再带 Low 标签，进程变为 **Medium (0x2000)**；NSIS 正常自解包，Electron 正常开窗，Java 后端正常启动。之前的 `0x80000003` 也是同一 Low integrity 条件下的 Electron 启动现象，不是 Electron 版本回归。

最小修复：构建时用 `build-portable.ps1 -OutputDir <普通目录>` 输出，脚本会拒绝低完整性产物；发布验收使用无参数双击，不使用 `--no-sandbox`。

---

## 2. 1.0 EXE 确认

用户确认的目录为 `D:\BearCoding-Desktop-Lab\dist`，该目录下只有一个 EXE：

```text
D:\BearCoding-Desktop-Lab\dist\BearCoding 1.0.0.exe
```

| 项 | 值 |
|---|---|
| 大小 | 200,913,075 字节（191.6 MiB） |
| SHA-256 | `0DF874851180E3853215240F7F58F8687EB6EFC4C66FFAE320E0A32FC5898D83` |
| 创建 / 修改 | 2026-11-14 10:46:50 / 2026-11-15 15:45:53 |
| PE 版本 | ProductVersion 1.0.0 / FileVersion 1.0.0 |
| 描述 / 作者 | BearCoding desktop practice / Local Developer |
| appId | `com.bearcoding.desktop.lab` |
| 签名 | NotSigned |
| 完整性标签 | 无 Low 标签（普通 Medium） |

1.0 的 `app.asar` 内是 `bearcoding-desktop-lab` 1.0.0 外壳：`main.cjs`（7,453 B）、`index.html`、`package.json`、`build/icon.png`；没有 `paths.cjs`。

---

## 3. 3.0 EXE 与构建产物

### 3.1 原始低完整性产物

```text
D:\BearCoding-Final\desktop\release\BearCoding-3.0.0-portable.exe
```

| 项 | 值 |
|---|---|
| 大小 | 209,526,735 字节（199.8 MiB） |
| SHA-256 | `E26636B6B9F2F5AA5A011AE0D2B17CAEDE0780B0E2332664D3BB6BC816F77515` |
| 构建时间 | 2026-11-16T16:31:41+08:00 |
| PE 版本 | 3.0.0 |
| appId | `com.bearcoding.desktop` |
| 完整性标签 | `Mandatory Label\Low Mandatory Level:(I)(NW)` |
| 签名 | NotSigned |

### 3.2 干净目录重建产物

```text
D:\BearCoding-Release-Candidate\build\BearCoding-3.0.0-portable.exe
```

| 项 | 值 |
|---|---|
| 大小 | 209,525,573 字节（199.8 MiB） |
| SHA-256 | `EFF3154031D13A1403CBCA5B1DEBEB0B9741A355FD512AAF02B4DF4F1082F4A5` |
| 构建时间 | 2026-11-16T18:03:48+08:00 |
| 内置 JAR SHA-256 | `41FED9A09746443B562875B5F56314CDAC1D47C824B8779BEC52139A402CA997` |
| 完整性标签 | 无 Low 标签（普通 Medium） |
| 签名 | NotSigned |

### 3.3 内嵌归档完整性

用 electron-builder 缓存中的 7-Zip 24.09 测试：

```text
1.0: Everything is Ok / 248 files / 488,555,437 bytes
3.0: Everything is Ok / 285 files / 501,867,074 bytes
```

两个 portable EXE 的 7z 载荷均完整，NSIS 错误不是下载截断或归档损坏。

---

## 4. 失败分层与根因证据

### 4.1 现象复现

3.0 原 EXE 在 C: 默认 `%TEMP%` 下启动，约 0.1 秒内出现：

```text
窗口类：#32770
标题：NSIS Error
文本：Error writing temporary file. Make sure your temp folder is valid.
按钮：确定
```

同时 `%TEMP%` 没有创建任何新的临时目录/文件（FileSystemWatcher 0 个事件）。

### 4.2 进程完整性级别

用 `OpenProcessToken` + `GetTokenInformation(TokenIntegrityLevel)` 读取：

| 进程 | 完整性 RID | 级别 |
|---|---:|---|
| 3.0 NSIS（原 EXE） | `0x1000` | Low |
| 1.0 portable | `0x2000` | Medium |
| 当前 PowerShell | `0x2000` | Medium |

### 4.3 文件/目录标签

```text
icacls D:\BearCoding-Final\desktop\release\BearCoding-3.0.0-portable.exe
  ... Mandatory Label\Low Mandatory Level:(I)(NW)

icacls D:\BearCoding-Final
  ... Mandatory Label\Low Mandatory Level:(OI)(CI)(NW)

icacls D:\BearCoding-Desktop-Lab\dist\BearCoding 1.0.0.exe
  （无 Low 标签）
```

### 4.4 TEMP 矩阵

| 被测 EXE | TEMP | 结果 |
|---|---|---|
| 3.0 原 EXE | `C:\Users\29643\AppData\Local\Temp` | NSIS 错误，0 个临时条目 |
| 3.0 原 EXE | `C:\bc-temp-test` | NSIS 错误，0 个临时条目 |
| 3.0 原 EXE | `C:\Users\29643\bc-temp-test` | NSIS 错误，0 个临时条目 |
| 3.0 原 EXE | `D:\BearCoding-Final\...\temp-D`（Low 标签） | NSIS 成功解包；Electron 随后 `0x80000003` |
| 3.0 复制到 C: 普通目录 | 默认 C: TEMP | NSIS 成功；窗口 `BearCoding`；后端启动；数据落盘 |
| 1.0 原 EXE | 默认 C: TEMP | 正常窗口（Medium） |

### 4.5 关键对照

- 把 3.0 EXE 复制到 `C:\Users\29643\AppData\Local\Temp\bc-release-test` 后，`icacls` 不再有 Low 标签；无参数启动成功，NSIS 解包到 `C:\Users\29643\AppData\Local\Temp\3MBUPD...`，Electron 窗口 PID 37712，标题 `BearCoding`，1200×800；Java 后端（Tomcat 38562）启动；隔离数据目录生成 `jingjing.db`、`logs`、`secrets`、`workspaces`。
- 3.0 `win-unpacked` 原目录同样带 Low 标签，因此直接启动也不正常；复制到普通目录后正常。
- 这不是 `%TEMP%` 权限问题：C: 用户 TEMP 的 ACL 允许当前用户 FullControl，写 4 字节探针成功，32 位进程可创建 209,037,191 字节文件，C: 剩余约 52 GB。
- 这不是安全软件拦截：Defender 实时保护/行为监控为关闭，无威胁检测记录；未改任何安全软件设置。

### 4.6 `ELECTRON_RUN_AS_NODE` 干扰

当前 agent 进程环境含：

```text
ELECTRON_RUN_AS_NODE=1
```

它会让 Electron 按 Node 运行，导致“启动即退出/0 退出码”的假象。所有正式启动测试均显式移除该变量；这是测试环境因素，不是产品问题。

---

## 5. 1.0 演示包评估

1.0 **不是**当前 GitHub v3.0.0 源码构建，而是历史练习外壳 + 3.0.0 后端快照：

| 项 | 1.0 演示包 | 3.0 候选 |
|---|---|---|
| 外壳版本 | `bearcoding-desktop-lab` 1.0.0 | `bearcoding-desktop` 3.0.0 |
| appId | `com.bearcoding.desktop.lab` | `com.bearcoding.desktop` |
| 外壳源码 | `D:\BearCoding-Desktop-Lab\main.cjs` | `desktop\main.cjs` + `paths.cjs` |
| 内置 JAR | `bailianAI-3.0.0.jar`，SHA `1250FFDE...`（2026-11-14 23:13） | `bearcoding-backend.jar`，SHA `41FED9A0...`（2026-11-16 17:58） |
| JAR 版本 | Manifest Implementation-Version 3.0.0 | 同 |
| 前端 | 旧 `app.daede8d5.js`，仍含 `kkimgs.yisou.com`/堆糖外链 | 新 `app.575480a1.js`，已改为本地 `avatar-bear.svg` |
| JRE | Oracle JDK 17.0.12（NFTC），无 `javac` 模块 | Temurin 17.0.21+9（GPLv2+CE），含 `javac` |
| 许可证 | 仅 Electron/Chromium + Oracle `legal/`；无 BearCoding MIT / THIRD-PARTY-NOTICES | 含 `resources/licenses/`、Electron/Chromium、Temurin `legal/` |
| 数据/密钥 | JAR 内无 `.db`/`.key`/`secrets`/`logs`；`application.yml` 只有 `${...:}` 占位符；未发现高置信度密钥 | 同；另有完整许可证 |
| 公开源码一致性 | 否 | 否（见第 6 节） |

1.0 可以启动，但只适合作为 **“BearCoding 1.0.0 历史演示版（2026-11-14 后端快照，非 GitHub v3.0.0 构建）”**，不能作为 v3.0.0 的 Release 附件，也不能声称与当前源码一致。

---

## 6. 公开源码对照

GitHub 当前 `main`：

- commit `7df27a3ddbca7a17db0715b0e306befd53f4a618`
- tree `a4e044dd983ce3751d0aadd4b5b53af377ea5cf4`
- 提交时间 2026-11-15T22:12:04+08:00
- API：<https://api.github.com/repos/kemorebi92-hue/BearCoding/commits/main>

本地：

- HEAD `33c2c8f286c39b8cbcba742ca51f1721bc71b5a5`
- 本地 HEAD 与 `origin/main` 的 tree 相同（`git diff HEAD origin/main` 为空）
- 但工作区有 9 个已修改文件、若干未跟踪文件；3.0 EXE 包含这些未提交改动。

关键差异：

| 文件/行为 | 公开 `main` | 本地工作区 / 3.0 EXE |
|---|---|---|
| `desktop/package.json` | 无 `licenses/` extraResources | 有 LICENSE / THIRD-PARTY-NOTICES |
| `desktop/build-portable.ps1` | 无 `javac` 模块与许可证校验 | 有 `java.compiler,jdk.compiler` 与许可证校验 |
| `frontend/src/views/ChatView.vue` | 仍使用 `kkimgs.yisou.com` 外链 | 改为本地 `avatar-bear.svg` |
| `frontend/src/assets/avatar-bear.svg` | 不存在 | 未跟踪的新文件，已打进 JAR |
| 1.0 历史包 | 无 | `D:\BearCoding-Desktop-Lab`，独立目录 |

**结论（发布时已解决）**：3.0 EXE 不能从当时的 GitHub 公开 `main` 复现，因为相关改动还在本地工作区。这些改动已随 `v3.0.0` 发布提交推送到 `main`，`v3.0.0` 标签指向该提交，因此 Release 的 Source code 与附件一致。

---

## 7. 最终构建与验收

### 7.1 构建

```powershell
$env:JAVA_HOME='D:\_bc-build-tools\temurin17\jdk\jdk-17.0.21+9'
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1 `
  -OutputDir D:\BearCoding-Release-Candidate\build
```

结果：

- 前端构建通过（webpack 仅有体积 warning）
- Maven `clean package -DskipTests` 通过（265 主源码 / 249 测试源码编译）
- jlink Temurin 17.0.21+9 通过，`java.exe`/`javac.exe` 均存在
- electron-builder 26.18.0 生成 portable EXE
- 构建脚本内置资源/许可证/JAR 哈希校验通过
- 完整性标签检查通过（输出目录为普通目录）

### 7.2 无参数启动

```text
EXE: D:\BearCoding-Release-Candidate\build\BearCoding-3.0.0-portable.exe
参数: 无
--no-sandbox: 未使用
--user-data-dir: 未使用
TEMP: 默认 C:\Users\29643\AppData\Local\Temp
```

结果：

- NSIS 正常解包到 `C:\Users\29643\AppData\Local\Temp\3MBUPDk9HbB7j9azkTIGfxqr74N`
- Electron 窗口：PID 37712，类 `Chrome_WidgetWin_1`，标题 `BearCoding`，1200×800，可见
- Java 后端：Tomcat 38562，随包 `java.exe`
- 隔离数据目录：`jingjing.db`、`logs\backend.log`、`secrets\{model-aes,mcp-credential,artifact-ref}.key`、`workspaces\1\...`
- 关闭窗口后，3.0 的 Electron/Java/NSIS 进程全部退出，临时解包目录清理；1.0 进程未受影响

截图：`evidence/final-window-20261116.png`（211,202 B，1200×800）

另外用 `Start-Process`（ShellExecute，等价于资源管理器双击）无参数启动同一 EXE，窗口 PID 14376、标题 `BearCoding` 出现；测试结束后已清理该次解包目录。

最终交付副本 `D:\BearCoding-Release-Candidate\BearCoding-3.0.0-portable.exe` 与构建产物 SHA-256 相同；再次无参数启动，窗口 PID 27940，数据库与三份 secrets 正常创建。

### 7.3 一次基本任务

用干净构建的 `win-unpacked` 中的 JRE/JAR + 受控 OpenAI 兼容测试服务：

```text
fileChanged           = True
oldTextGone           = True
readToolUsed          = True
autoCompileVerified   = True
verifyCompileLogLine  = [VerifyCompile] ran=true success=true elapsed=566ms
bundledJavacExit      = 0
bundledJavaRunOutput  = Hello, BearCoding v3.0.0
tokenInLogs           = False
```

摘要：`D:\BearCoding-Release-Candidate\core-task-data\core-task-summary.json`

日志：`evidence/core-task-clean-20261116.log`

### 7.4 完整窗口验收（干净 EXE，默认 profile）

```text
exeArgs              = --remote-debugging-port=33407
sandboxDisabled      = false
defaultProfileMode   = true
windowTitle          = BearCoding
windowHref           = http://127.0.0.1:33452/chat
tokenInjectedApi     = 200 ([])
noTokenApi           = 401
noTokenUser          = 401
navBlocked           = true
windowOpenDenied     = true
secondInstanceExited = true
javaCountAfterSecond = 1
appExitedAfterClose  = true
javaExitedAfterClose = true
sqliteCreated        = true
secretsCreated       = true
tokenLeakInLogs      = false
```

日志：`evidence/window-verify-clean-20261116.log`

截图：`D:\BearCoding-Release-Candidate\window-verify-data\window-chat.png`（211,558 B）

---

## 8. 推荐发布

**推荐发布 3.0 候选，不发布 1.0 作为 v3.0.0。**

- 版本名称：`BearCoding v3.0.0`（Windows x64 Portable）
- 文件名：`BearCoding-3.0.0-portable.exe`
- 发布文件：`D:\BearCoding-Release-Candidate\build\BearCoding-3.0.0-portable.exe`
- SHA-256：`EFF3154031D13A1403CBCA5B1DEBEB0B9741A355FD512AAF02B4DF4F1082F4A5`
- 不要上传 `desktop/release` 里的低完整性旧产物。
- 不要用 `--no-sandbox` 作为发布说明中的运行方式。

发布前动作（本次发布已全部完成）：

1. ~~提交并推送本地未提交改动~~ —— 已完成：发布提交进入 `main`，包含 `desktop/package.json`、`desktop/build-portable.ps1`、`desktop/README.md`、`docs/RELEASE-NOTES-v3.0.0.md`、`README.md`、`frontend/src/views/ChatView.vue`、`THIRD-PARTY-NOTICES.md`、`ASSETS.md`、`docs/ARCHITECTURE.md`，以及 `frontend/src/assets/avatar-bear.svg`、`desktop/test/*`、`evidence/*.md`。
2. ~~用公开源码核对同一 SHA，或明确标注 EXE 来自哪个 commit~~ —— 已完成：`v3.0.0` 标签指向的提交就是构建所用工作区内容。
3. 复核图片素材授权（`ASSETS.md` 仍列 5 个位图待确认）—— 用户已决定本轮保留现状，作为已知限制写入 Release 说明。
4. 如需 1.0 演示包，单独发布并明确标注历史版本，不与 v3.0.0 混用 —— 未作为本 Release 附件。

---

## 9. 需要修改的文件

已修改（本地工作区，未提交）：

- `desktop/build-portable.ps1`：新增 `-OutputDir`；electron-builder 输出覆盖；Low integrity 产物拒绝。
- `desktop/README.md`：根因、构建方式、验收矩阵、1.0 说明。
- `README.md`：状态、候选身份、双击验收、已知限制。
- `docs/RELEASE-NOTES-v3.0.0.md`：双击结论、限制、SHA/大小/JAR 哈希、公开源码一致性。
- `desktop/test/packaged-window-verify.ps1`：移除错误的 Chromium 沙箱归因，改为 Low integrity 说明。
- `desktop/test/portable-core-task-verify.ps1`：新增 `-UnpackedDir`，可对干净构建运行核心任务。

新增证据：

- `evidence/bearcoding-v3.0.0-integrity-rootcause-20261116.md`（本文件）
- `evidence/build-clean-20261116.log`
- `evidence/core-task-clean-20261116.log`
- `evidence/final-window-20261116.png`
- `evidence/one-jar-inspect/`、`evidence/three-jar-inspect/`、`evidence/new-jar-inspect/`

未执行：commit、push、tag、GitHub Release、上传附件。

---

## 10. Release 文案

发布时使用的标题与正文以仓库内的 [docs/RELEASE-NOTES-v3.0.0.md](../docs/RELEASE-NOTES-v3.0.0.md) 为准（该文件就是 GitHub Release 正文），本报告不再保留早期的重复草稿。

- 标题：**BearCoding v3.0.0 — Windows x64 便携版**
- 正文：`docs/RELEASE-NOTES-v3.0.0.md`
- 附件：`BearCoding-3.0.0-portable.exe`、`SHA256SUMS.txt`
- 不上传：1.0 历史演示包、`desktop/release` 里的低完整性旧产物、`win-unpacked/`、日志与截图。
