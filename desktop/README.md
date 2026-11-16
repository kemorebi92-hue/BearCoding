# BearCoding Desktop（Electron 外壳）

本目录是 BearCoding 的 Windows 桌面启动与打包源码。它只负责：

- 启动随包携带的 Java Runtime 与 Spring Boot JAR；
- 选择本地空闲端口并等待后端就绪；
- 创建 Electron 窗口并加载 Spring Boot 提供的 Vue 页面；
- 每次启动生成桌面令牌，仅向本应用 Origin 注入；
- 退出时清理 Java 子进程。

它不包含 Agent、Plan、上下文、MCP 或权限系统的业务实现；这些仍在 `backend/`。

---

## 1. 环境要求

已在以下环境完成构建与打包验证：

| 组件 | 版本 |
|---|---|
| Windows | Windows 11 22631 x64（中文，`王哥的第一台电\29643`，中等完整性、交互式会话 5） |
| Node.js | 22.20.0 |
| npm | 10.9.3 |
| JDK | **Eclipse Temurin 17.0.21+9**（`JAVA_HOME=D:\_bc-build-tools\temurin17\jdk\jdk-17.0.21+9`） |
| Maven | Maven Wrapper 3.9.14（`backend/mvnw.cmd`） |
| Electron | 45.3.2（见第 9 节的启动问题与实测结论） |
| electron-builder | 26.18.0 |

需要：

- Windows 10/11 x64；
- 可用的 `JAVA_HOME`（包含 `bin\java.exe`、`bin\jlink.exe`，且必须是 **JDK 17**；运行时由 jlink 生成，必须带 `jdk.compiler` 才有 `javac`）；
- Node.js 22.12+；
- 首次安装依赖时的 npm 网络或本地 npm/Electron 缓存；
- 打包时不需要全局安装 Electron，版本由 `package-lock.json` 锁定。

---

## 2. 目录关系

```text
BearCoding-Final/
├─ backend/                 Spring Boot 后端；Maven 产物在 backend/target/
├─ frontend/                Vue 3 前端；npm run build 产物在 frontend/dist/
├─ desktop/
│  ├─ main.cjs              Electron 主进程
│  ├─ paths.cjs             开发/打包路径解析（可单测）
│  ├─ package.json          Electron 与 electron-builder 配置
│  ├─ package-lock.json     锁定的 npm 依赖
│  ├─ build/icon.png        窗口与 EXE 图标
│  ├─ build-portable.ps1    一键构建入口
│  ├─ test/                 路径单测与运行时冒烟脚本
│  ├─ .build/               构建暂存（忽略，不提交）
│  └─ release/              portable EXE 与 win-unpacked（忽略，不提交）
└─ docs/ evidence/
```

构建时：

1. Vue 产物复制到 `backend/src/main/resources/static/`；
2. Maven 重新打 Spring Boot fat JAR；
3. JAR 复制为 `desktop/.build/bearcoding-backend.jar`（稳定 staging 名）；
4. jlink 生成 `desktop/.build/jre`；
5. electron-builder 把 JAR、JRE、`app.asar` 组装成 portable EXE。

---

## 3. 一键构建

在 `<repo>\desktop` 下执行：

```powershell
# 发布构建：输出到普通目录，避免继承工作区的 Low integrity 标签
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1 `
  -OutputDir D:\BearCoding-Release-Candidate\build
```

或（默认输出到 `desktop/release`，只适合低完整性工作区内的冒烟，不适合直接双击）：

```powershell
npm run build:portable
```

脚本会实际执行以下步骤，任一步失败都会以非 0 退出，不会把旧 JAR/JRE 当成成功产物：

1. `frontend`：`npm ci --prefer-offline --no-audit --no-fund`；
2. `frontend`：`npm run build`；
3. 清空并复制 `frontend/dist/*` 到 `backend/src/main/resources/static/`；
4. `backend`：`mvnw.cmd clean package -DskipTests`；
5. 校验 JAR 内存在 `BOOT-INF/classes/static/index.html` 和 `static/js/app.*.js`；
6. 复制 JAR 到 `desktop/.build/bearcoding-backend.jar`；
7. `jlink` 生成 `desktop/.build/jre`；
8. 校验 `jre/bin/java.exe`、**`jre/bin/javac.exe`**、`jre/legal/java.base/LICENSE`，并分别执行 `java -version` / `javac -version`（缺 `jdk.compiler` 会直接失败，防止自动编译校验静默失效）；
9. `desktop`：`npm ci`；
10. 从 Electron 官方缓存压缩包提取二进制并校验 SHA-256；缓存不存在时回退到 `node_modules/electron/install.js`；
11. `electron-builder --win portable --publish never --config.directories.output=<OutputDir>`；
12. 校验 EXE 体积、内置 JAR 哈希、JRE、图标、包内 `licenses/`（并与仓库 `LICENSE`、`THIRD-PARTY-NOTICES.md` 逐字节比对哈希）、Electron/Chromium 声明，并拒绝任何 `.db`、`.key`、`logs`、`secrets`、`test-user-data`；
13. 校验最终 EXE 的完整性标签；如果是 Low Mandatory Level，直接失败并提示改用普通 `-OutputDir`。

### 构建产物

```text
<OutputDir>/BearCoding-3.0.0-portable.exe   portable EXE（发布用）
<OutputDir>/win-unpacked/                   未打包目录（便于冒烟）
desktop/.build/bearcoding-backend.jar       本次构建的 JAR staging
desktop/.build/jre/                         本次构建的 jlink Runtime
desktop/.build/build-info.json              本次构建的版本、JDK 发行版与 SHA-256
```

`desktop/.build/`、`desktop/release/`、`desktop/test-user-data/` 均被 `.gitignore` 忽略。默认的 `desktop/release/` 在本机低完整性工作区内，产物不能直接双击；发布产物应放在普通 `-OutputDir`。

---

## 4. 路径解析

### 开发模式（`electron .` / `npm start`）

| 项目 | 解析方式 |
|---|---|
| 项目根 | `main.cjs` 所在目录的上一级 |
| Java | `BEARCODING_JAVA`（绝对路径）→ `JAVA_HOME\bin\java.exe` → PATH 中的 `java` |
| 后端 JAR | `backend/target/*.jar`，忽略 `*.jar.original`、`-sources.jar`、`-javadoc.jar`；存在多个 JAR 时拒绝猜测 |
| 数据根 | 默认 `desktop/test-user-data`；可用 `BEARCODING_DEV_DATA_DIR` 指定绝对路径 |
| 图标 | `desktop/build/icon.png` |

### 打包模式（portable EXE）

| 项目 | 解析方式 |
|---|---|
| Java | `process.resourcesPath\jre\bin\java.exe` |
| 后端 JAR | `process.resourcesPath\app\bearcoding-backend.jar` |
| 数据根 | `%LOCALAPPDATA%\BearCoding` |
| 图标 | `app.asar` 内的 `build/icon.png` |

正式数据根固定为 `%LOCALAPPDATA%\BearCoding`，子目录约定不变：

```text
%LOCALAPPDATA%\BearCoding\
  data\jingjing.db
  secrets\mcp-credential.key
  secrets\model-aes.key
  secrets\artifact-ref.key
  secrets\search-credential.bin
  logs\backend.log
  workspaces\
  mcp-output\
```

不要用 `JINGJING_DATA_DIR` 指向 `...\BearCoding\data`；它表示数据根本身。

---

## 5. 开发模式

```powershell
cd <repo>\desktop
npm ci
npm start
```

开发模式要求 `backend/target` 中已经存在唯一的主 JAR。可先执行：

```powershell
cd <repo>\backend
.\mvnw.cmd clean package -DskipTests
```

隔离测试数据：

```powershell
$env:BEARCODING_DEV_DATA_DIR = "<repo>\desktop\test-user-data\manual-run"
npm start
Remove-Item Env:BEARCODING_DEV_DATA_DIR
```

注意：

- 开发模式默认不会写正式 `%LOCALAPPDATA%\BearCoding`；
- 如果宿主设置了 `ELECTRON_RUN_AS_NODE=1`，Electron 会以 Node 模式启动并报“Electron failed to install correctly”。先执行 `Remove-Item Env:ELECTRON_RUN_AS_NODE`；
- 如果开发目录或成品位于带 `Low Mandatory Level` 的受限工作区，Windows 会以 Low integrity 启动 Electron/NSIS，可能出现临时文件错误或初始化退出。把成品放到普通目录，或用 `-OutputDir` 输出；这不是应用 JS 异常。

---

## 6. 安全机制

- 每次启动 `crypto.randomBytes(32).toString("hex")` 生成 64 字符令牌；
- 令牌只通过环境变量 `BEARCODING_LOCAL_TOKEN` 传给 Java 进程；
- Electron 只对 `http://127.0.0.1:<动态端口>/*` 且属于当前 `webContents` 的请求注入 `X-BearCoding-Token`；
- 不把令牌写入日志、数据库、配置文件、localStorage、Cookie 或页面；
- `nodeIntegration: false`、`contextIsolation: true`、`sandbox: true`；
- `will-navigate` / `will-redirect` 只允许本应用 Origin；`setWindowOpenHandler` 拒绝新窗口；
- Spring Boot 以 `--app.auth.mode=desktop` 启动，desktop 模式令牌缺失/过短时 fail-closed；
- `/ai/**`、`/api/**`、`/user/**` 无令牌返回 401；静态 Vue 资源允许匿名加载；
- Java 以 `--server.address=127.0.0.1` 绑定回环；
- 退出时使用 `taskkill /PID <pid> /T /F` 清理 Java 及子进程。

---

## 7. 验证命令

```powershell
cd <repo>\desktop

# 1. 路径解析单测（不启动 Electron）——9 项
npm test

# 2. 打包后 JRE + JAR + Vue 页面 + 401/200 + 回环 + 数据隔离
npm run test:runtime

# 3. 直接启动成品 EXE（release\win-unpacked\BearCoding.exe），验证窗口、
#    模型配置页、令牌注入、无令牌 401、单实例、退出清理、数据目录、导航白名单
powershell -NoProfile -ExecutionPolicy Bypass -File .\test\packaged-window-verify.ps1

# 3b. 同上，但换成干净目录里的最终 portable EXE，并用「默认启动」参数
#     （不加 --no-sandbox、不预建 Electron 用户数据目录），最接近用户双击
powershell -NoProfile -ExecutionPolicy Bypass -File .\test\packaged-window-verify.ps1 `
    -Exe D:\BearCoding-Release-Candidate\build\BearCoding-3.0.0-portable.exe -DefaultProfile

# 4. 便携版核心任务：隔离数据目录 + 受控 OpenAI 兼容测试模型
#    读文件 → 改 Java 文件 → 自动 javac 校验 → 结果回显
powershell -NoProfile -ExecutionPolicy Bypass -File .\test\portable-core-task-verify.ps1 `
    -UnpackedDir D:\BearCoding-Release-Candidate\build\win-unpacked

# 5. 开发态 Electron（历史脚本，仍可用；不是成品 EXE 的验收替代）
npm run test:electron
```

`test:runtime` 使用 `release/win-unpacked/resources` 中的 JRE/JAR，数据目录在 `desktop/test-user-data/` 下新建；使用 Edge headless 渲染 `/chat`，不会调用真实模型 API。

`packaged-window-verify.ps1` 把 `APPDATA` / `LOCALAPPDATA` / `TEMP` 全部指向本次测试目录，因此不会读写真实的 `%LOCALAPPDATA%\BearCoding`；`-DefaultProfile` 只额外传一个 `--remote-debugging-port`，其余保持默认。

`portable-core-task-verify.ps1` 会启动 `test/mock-model-server.cjs`（一个脚本化的 OpenAI 兼容 SSE/JSON 服务，只监听回环、不联网），用隔离工作区里的 `Demo.java` 跑完整链路，并断言后端日志出现 `[VerifyCompile] ran=true success=true`。

---

## 8. 已知限制

1. 只支持 Windows x64 portable；没有 NSIS 安装版、自动更新、macOS/Linux。
2. EXE 未签名，首次运行可能触发 SmartScreen；代码签名属于后续发布工作。
3. jlink Runtime 已换成 **Eclipse Temurin 17.0.21+9**（GPLv2+CE），`resources/jre/legal/` 随包保留，包内另有 `resources/licenses/`。来源、SHA-256、模块集见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 第 1.3 节。
4. Electron 二进制不进入 Git；首次构建需要 npm/Electron 缓存或网络。构建脚本会优先使用带官方 SHA-256 校验的缓存压缩包。**本机注意**：Node 的 `fetch` 直接下载 Electron 压缩包会 `fetch failed`（网络/代理限制），本轮的构建方式是先用 PowerShell 下载官方 zip、放进 `%LOCALAPPDATA%\electron\Cache` 下任意子目录，再用 `ELECTRON_SKIP_BINARY_DOWNLOAD=1` 跑 `build-portable.ps1`，让脚本按文件名找到缓存并校验 SHA-256。
5. Maven Wrapper 首次运行可能下载 Maven 3.9.14；离线构建需预先准备本地仓库。
6. 如果 `%TEMP%` 对当前用户不可写，JNA/DPAPI 初始化会失败。正常 Windows 用户临时目录可写；测试脚本会把 `TEMP`/`TMP` 指向隔离目录。
7. 本目录的源码与发布包不包含用户数据库、聊天历史、真实 API Key、MCP 凭证或正式用户数据。验证生成的测试 SQLite、测试密钥与日志只存在于被忽略的 `desktop/test-user-data/`，不会进入 Git 或 EXE。
8. `main.cjs` 仍把父进程环境传给 Java（与迁移前一致）。如果启动 Electron 的父进程带有无关敏感环境变量，Java 会继承；公开分发时应保持最小启动环境。
9. **成品 EXE 不能在低完整性目录内直接双击**。如果构建输出目录（例如某些沙箱工作区）带 `Low Mandatory Level`，EXE 会继承该标签，Windows 以 Low integrity 启动它；NSIS 无法写普通 `%TEMP%`，Electron 也可能在初始化阶段退出。用 `-OutputDir` 输出到普通目录，或把成品复制到普通目录后再双击/上传。根因与证据见第 9 节。
10. EXE 内现在有：`resources\licenses\LICENSE.txt`（BearCoding MIT）、`resources\licenses\THIRD-PARTY-NOTICES.md`（前端依赖 + 字体 OFL + Electron/Chromium + Temurin）、`LICENSE.electron.txt`、`LICENSES.chromium.html`、`resources\jre\legal\`。构建脚本会校验包内副本与仓库版本的 SHA-256 一致。

---

## 9. 本机「双击失败」的根因：产物继承了工作区的 Low integrity 标签

### 9.1 结论

不是 NSIS 版本、不是 Electron 45.3.2、不是 `%TEMP%` 不可写，也不是应用 JS 逻辑。

`D:\BearCoding-Final` 及其子目录带有 NTFS 完整性标签：

```text
Mandatory Label\Low Mandatory Level:(OI)(CI)(NW)
```

在该目录内构建出的 `BearCoding-3.0.0-portable.exe` 继承了这个标签。Windows 启动它时，进程令牌完整性级别是 **Low (0x1000)**，而不是普通用户的 **Medium (0x2000)**。Low integrity 进程不能写普通 `%TEMP%`（Medium integrity），所以 NSIS 在创建临时文件时立即报：

```text
Error writing temporary file. Make sure your temp folder is valid.
```

同一份 EXE 复制到普通目录后，文件不再带 Low 标签，进程变为 Medium，NSIS 正常自解包，Electron 正常开窗。1.0 的 EXE 位于 `D:\BearCoding-Desktop-Lab\dist`，不在低完整性工作区内，所以能正常启动；这不是因为它的 NSIS 或 Electron 版本不同。

### 9.2 证据

| 检查 | 结果 |
|---|---|
| `icacls` 原 3.0 EXE | `Mandatory Label\Low Mandatory Level:(I)(NW)` |
| `icacls` 1.0 EXE | 无 Low 标签 |
| 3.0 NSIS 进程令牌 | `0x1000`（Low） |
| 1.0 进程令牌 | `0x2000`（Medium） |
| 3.0 原 EXE + C: 用户 TEMP | 立即 NSIS 错误；临时目录 0 个新条目 |
| 3.0 原 EXE + D: 普通 TEMP | NSIS 成功解包；Electron 随后 `0x80000003` 退出（进程仍是 Low） |
| 3.0 复制到 C: 普通目录 + 默认 C: TEMP | NSIS 成功；出现 `Chrome_WidgetWin_1\|BearCoding` 窗口；Java 后端启动；数据目录落盘 |
| 3.0 `win-unpacked` 原目录 | 同样因 Low integrity 无法正常启动 |
| 7-Zip 完整性测试 | 1.0/3.0 内嵌 7z 均 `Everything is Ok` |

当前 agent 进程还带有 `ELECTRON_RUN_AS_NODE=1`，会让 Electron 按 Node 运行；上述启动测试已显式移除该变量。它不是产品问题，但会污染自动化测试。

### 9.3 最小修复

构建时把输出目录指向普通目录（不要用低完整性工作区内的 `desktop/release`）：

```powershell
cd desktop
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1 `
  -OutputDir D:\BearCoding-Release-Candidate\build
```

`build-portable.ps1` 现在会检查最终 EXE 的完整性标签；如果仍是 Low，会直接失败并提示改用 `-OutputDir`。构建后确认：

```powershell
icacls D:\BearCoding-Release-Candidate\build\BearCoding-3.0.0-portable.exe
# 不应出现 Low Mandatory Level
```

`--no-sandbox` 不是修复，只用于定位；发布验收必须使用无参数双击。

### 9.4 1.0 历史演示包

1.0 的 EXE 位于 `D:\BearCoding-Desktop-Lab\dist`，不在低完整性工作区内，因此能正常启动。但它不是当前 v3.0.0 源码构建：外壳是 `bearcoding-desktop-lab` 1.0.0，内置的是 2026-11-14 的 3.0.0 后端快照，JRE 为 Oracle 17.0.12，且没有当前包的许可证/第三方声明。它只能作为明确标注的历史演示版，不能当作 v3.0.0 发布。

---
## 10. 与历史练习目录的关系

本目录是正式源码，不依赖原 Electron 练习项目目录。迁移时只复制了：

- `main.cjs`（按 Final 目录结构修正路径与错误提示）；
- `package.json` / `package-lock.json`（更新应用身份、输出目录、staging 资源）；
- `build/icon.png`。

没有复制 `node_modules/`、`dist/`、`runtime/jre/`、`test-user-data/`、`secrets/`、`data/`、`logs/`、`workspaces/`、`mcp-output/`、任何 SQLite 数据库或原 EXE。
