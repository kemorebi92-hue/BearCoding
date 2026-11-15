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
| Windows | Windows 11 22631 x64 |
| Node.js | 22.20.0（Electron 45 要求 Node >= 22.12） |
| npm | 10.9.3 |
| JDK | Oracle JDK 17.0.12（`JAVA_HOME` 指向 JDK 17 根目录） |
| Maven | Maven Wrapper 3.9.14（`backend/mvnw.cmd`） |
| Electron | 45.3.2 |
| electron-builder | 26.18.0 |

需要：

- Windows 10/11 x64；
- 可用的 `JAVA_HOME`（包含 `bin\java.exe` 和 `bin\jlink.exe`）；
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
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1
```

或：

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
8. 校验 `jre/bin/java.exe`、`jre/legal/`，并执行 `java -version`；
9. `desktop`：`npm ci`；
10. 从 Electron 官方缓存压缩包提取二进制并校验 SHA-256；缓存不存在时回退到 `node_modules/electron/install.js`；
11. `electron-builder --win portable --publish never`；
12. 校验 EXE 体积、内置 JAR 哈希、JRE、图标和发布目录，并拒绝任何 `.db`、`.key`、`logs`、`secrets`、`test-user-data`。

### 构建产物

```text
desktop/release/BearCoding-3.0.0-portable.exe   portable EXE
desktop/release/win-unpacked/                   未打包目录（便于冒烟）
desktop/.build/bearcoding-backend.jar           本次构建的 JAR staging
desktop/.build/jre/                             本次构建的 jlink Runtime
desktop/.build/build-info.json                  本次构建的版本与 SHA-256
```

`desktop/.build/`、`desktop/release/`、`desktop/test-user-data/` 均被 `.gitignore` 忽略。

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
- 某些无交互桌面或受限宿主会让 Electron 进程在启动阶段崩溃（本机验证时出现 `0xc0000005`，最小 Electron 应用同样复现）。这属于运行宿主限制，不是应用 JS 异常；完整窗口验证应在普通 Windows 交互桌面执行。

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

# 路径解析单测（不启动 Electron）
npm test

# 打包后 JRE + JAR + Vue 页面 + 401/200 + 回环 + 数据隔离
npm run test:runtime

# 完整 Electron 窗口、CDP、令牌注入、单实例、退出清理
# 需要 Electron 能在当前桌面会话正常启动
npm run test:electron
```

`test:runtime` 使用 `release/win-unpacked/resources` 中的 JRE/JAR，数据目录在 `desktop/test-user-data/` 下新建；使用 Edge headless 渲染 `/chat`，不会调用真实模型 API。

---

## 8. 已知限制

1. 只支持 Windows x64 portable；没有 NSIS 安装版、自动更新、macOS/Linux。
2. EXE 未签名，首次运行可能触发 SmartScreen；代码签名属于后续发布工作。
3. jlink Runtime 来自 Oracle JDK 17.0.12。`legal/` 目录随 Runtime 保留，已确认打包到 `resources/jre/legal/`。Phase C 复核结论：17.0.12 属 Oracle NFTC 许可，Oracle 官方 FAQ 明确「runtime images produced by the jlink tool」可以免费再分发（不得收费），因此**当前免费分发在许可上是允许的**。但 17.0.12 是 Java 17 最后一个 NFTC 免费版本，2024-10 之后的安全补丁只通过 My Oracle Support / OTN 提供，所以随包运行时**缺少 2024-10 起的 Oracle 关键补丁更新**。是否换成仍在维护的 OpenJDK 17 发行版，是二进制公开发布前的独立决策。详见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 第 1.3 节。
4. Electron 二进制不进入 Git；首次构建需要 npm/Electron 缓存或网络。构建脚本会优先使用带官方 SHA-256 校验的缓存压缩包。
5. Maven Wrapper 首次运行可能下载 Maven 3.9.14；离线构建需预先准备本地仓库。
6. 如果 `%TEMP%` 对当前用户不可写，JNA/DPAPI 初始化会失败。正常 Windows 用户临时目录可写；测试脚本会把 `TEMP`/`TMP` 指向隔离目录。
7. 本目录的源码与发布包不包含用户数据库、聊天历史、真实 API Key、MCP 凭证或正式用户数据。验证生成的测试 SQLite、测试密钥与日志只存在于被忽略的 `desktop/test-user-data/`，不会进入 Git 或 EXE。
8. `main.cjs` 仍把父进程环境传给 Java（与迁移前一致）。如果启动 Electron 的父进程带有无关敏感环境变量，Java 会继承；公开分发时应保持最小启动环境。这是既有行为，Phase B 未改变。
9. 完整 Electron 窗口的运行态验收**没有完成**。本机 Electron 45.3.2 启动即崩溃（`0xc0000005`），最小 Electron 应用同样复现，因此 `npm run test:electron` 覆盖的窗口创建、CDP 令牌注入、单实例和退出清理在新构建产物上仍属未验证。已完成的只有 `npm run test:runtime` 覆盖的 JAR/JRE 启动、Vue 页面渲染、`/chat` 200、无令牌 401、带令牌 200 和数据隔离。不要把「EXE 能构建出来」当成「窗口能正常跑」。
10. EXE 内目前只有 Electron（`LICENSE.electron.txt`）和 Chromium（`LICENSES.chromium.html`）的声明，以及 Oracle JDK 的 `legal/`。**没有** BearCoding 自己的 MIT 许可证文本、前端依赖声明和字体（SIL OFL 1.1）声明。正式分发前应把根目录的 `LICENSE` 与 `THIRD-PARTY-NOTICES.md` 加进 `extraResources`。

---

## 9. 与历史练习目录的关系

本目录是正式源码，不依赖原 Electron 练习项目目录。迁移时只复制了：

- `main.cjs`（按 Final 目录结构修正路径与错误提示）；
- `package.json` / `package-lock.json`（更新应用身份、输出目录、staging 资源）；
- `build/icon.png`。

没有复制 `node_modules/`、`dist/`、`runtime/jre/`、`test-user-data/`、`secrets/`、`data/`、`logs/`、`workspaces/`、`mcp-output/`、任何 SQLite 数据库或原 EXE。
