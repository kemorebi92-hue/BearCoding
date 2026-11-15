# Phase B：Electron 正式源码迁移与可复现构建证据

日期：2026-11-15  
项目：`D:\BearCoding-Final`  
范围：Electron 桌面源码迁移、路径修复、Windows portable 构建、隔离运行验证、安全复查。  
未执行：`git add`、`git commit`、`git push`、`git tag`、分支/历史操作、GitHub Release、真实模型 API 调用。

---

## 1. 结论

- **源码迁移：PASS**。正式源码已进入 `desktop/`，不依赖原 原练习目录 目录。
- **可复现构建：PASS**。从 Final 目录完成 Vue → Spring Boot JAR → jlink Runtime → portable EXE，构建脚本退出码 0。
- **打包资源与后端链路：PASS**。打包后的 JRE + JAR 可启动，`/chat` 200，无 Token 401，带 Token 200，仅监听回环，测试数据隔离，Java 可退出。
- **Vue 页面渲染：PASS**。Edge headless 加载 `/chat`，标题 BearCoding，显示“请先配置模型”，同源 API 带 Token 200，无 console/page error。
- **完整 Electron 窗口启动：UNVERIFIED**。本机 Electron 45.3.2 在启动阶段以 `0xc0000005` 崩溃，最小 Electron 应用同样复现；未把该宿主限制误报为应用 PASS。未启动正式 portable EXE，未接触正式用户数据。
- **总体：CONDITIONAL**。构建与打包资源可交付；完整 Electron 窗口、令牌注入、单实例和退出清理仍需在普通 Windows 交互桌面做一次人工冒烟。

---

## 2. 环境

| 项 | 值 |
|---|---|
| OS | Windows 11 家庭中文版 22631 x64 |
| Node.js | v22.20.0 |
| npm | 10.9.3 |
| JDK | Oracle JDK 17.0.12 |
| Maven | Maven Wrapper 3.9.14（`backend/mvnw.cmd`） |
| Electron | 45.3.2 |
| electron-builder | 26.18.0 |
| 构建入口 | `desktop/build-portable.ps1` |

---

## 3. 迁移清单

### 3.1 从 原练习目录 读取并迁移

| 原文件 | Final 位置 | 处理 |
|---|---|---|
| `main.cjs` | `desktop/main.cjs` | 保留启动/窗口/令牌/清理逻辑；修正 JAR/JAVA/数据目录路径解析；增加错误提示 |
| `package.json` | `desktop/package.json` | 应用身份、版本 3.0.0、输出目录、staging 资源、electronDist |
| `package-lock.json` | `desktop/package-lock.json` | 更新 name/version 与精确 devDependencies；`npm ci` 验证 |
| `build/icon.png` | `desktop/build/icon.png` | 原样复制；SHA-256 与源一致 |
| `index.html` | 未迁移 | 原文件未被 `main.cjs` 或打包配置引用，属于练习占位页 |
| `dist/BearCoding 1.0.0.exe` | 未迁移 | 原构建产物，保留在 原练习目录 |

### 3.2 明确未迁移

`node_modules/`、`dist/`、`win-unpacked/`、`runtime/jre/`、`test-user-data/`、`secrets/`、`data/`、`logs/`、`workspaces/`、`mcp-output/`、任何 SQLite 数据库、原 EXE、打包临时文件、个人开发缓存。

### 3.3 原练习目录 未修改证据

| 文件 | 迁移前 SHA-256 | 迁移后 SHA-256 |
|---|---|---|
| `main.cjs` | `E08ADDDFC0E3D1583EBA3FFBD16978FBD4B3CC62CC341AB23B8A4CAC57E23ABD` | 同左 |
| `package.json` | `3B241A043847A5D99253FA8A72999716E4E9D916CD567F47DC8C20DEA4CDDF71` | 同左 |
| `package-lock.json` | `7A80D0BC8BF846FA70B5B172285958BE6781AE4BEE1CBD2444A35D763F4BE7DA` | 同左 |
| `index.html` | `137E578777FB3DE240F6505BD157ED6F2CAFE2E1D4DA389D18EBFFE11BC8448B` | 同左 |
| `build/icon.png` | `DA4A0AAB39C548C296AB066F885E284C1E1CEECCCA6B3A5036B5663DB947B7F3` | 同左 |
| `dist/BearCoding 1.0.0.exe` | `0DF874851180E3853215240F7F58F8687EB6EFC4C66FFAE320E0A32FC5898D83` | 同左 |

原练习目录 目录最后修改时间仍为 `2026-11-15 15:03:44`。

---

## 4. 路径修复

| 项目 | 原实现 | Final 实现 |
|---|---|---|
| 开发 JAR | 硬编码 `D:\BearCoding-Final\backend\target\bailianAI-3.0.0.jar` | `backend/target` 中唯一主 JAR；忽略 `.jar.original`/sources/javadoc；多个 JAR 拒绝猜测 |
| 打包 JAR | `resources/app/bailianAI-3.0.0.jar` | `resources/app/bearcoding-backend.jar`（稳定 staging 名，构建时从 Maven 产物复制） |
| 开发 Java | `desktop/runtime/jre/bin/java.exe` | `BEARCODING_JAVA` → `JAVA_HOME/bin/java.exe` → PATH 中 `java` |
| 打包 Java | `resources/jre/bin/java.exe` | 不变，`process.resourcesPath` 解析 |
| 开发数据根 | `desktop/test-user-data` | 不变；支持 `BEARCODING_DEV_DATA_DIR` 绝对路径隔离 |
| 打包数据根 | `%LOCALAPPDATA%\BearCoding` | 不变；`LOCALAPPDATA` 缺失时 fail-closed |
| 图标 | `__dirname/build/icon.png` | 不变；`paths.cjs` 校验存在 |
| 输出目录 | 原练习目录 `dist/` | Final `desktop/release/`（Git 忽略） |
| 构建资源 | `../BearCoding-Final/...` | `desktop/.build/bearcoding-backend.jar` 与 `desktop/.build/jre`（相对 `desktop/`） |

源码中已无 `D:\`、`C:\`、`BearCoding-Final`、`原练习目录` 等运行期硬编码路径（测试夹具中的示例盘符除外）。

---

## 5. 构建结果

### 5.1 命令

```powershell
cd <repo>\desktop
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1
```

本次最终构建退出码：`0`。

### 5.2 产物

| 项 | 值 |
|---|---|
| Portable EXE | `desktop/release/BearCoding-3.0.0-portable.exe` |
| EXE 大小 | 200,998,035 bytes |
| EXE SHA-256 | `04CD93CFE6AC617A1F8698F14CAF55E4476349094794BD1DDC7DE76C6881F529` |
| 后端 JAR | `backend/target/bailianAI-3.0.0.jar` |
| JAR SHA-256 | `62F67462DE7DFD1BE5C83BAABA45E5D4CC306A07A19DB8C9C2BFA05037461684` |
| 打包 JAR | `resources/app/bearcoding-backend.jar`，哈希与本次 JAR 一致 |
| jlink Runtime | `desktop/.build/jre` → `resources/jre` |
| Runtime 文件数/大小 | 174 files / 55,371,463 bytes |
| Runtime 版本 | Java 17.0.12 |
| 构建信息 | `desktop/.build/build-info.json` |

### 5.3 JAR 内容校验

构建脚本确认 JAR 内存在：

- `BOOT-INF/classes/static/index.html`
- `BOOT-INF/classes/static/js/app.*.js`

### 5.4 jlink 模块

```text
java.base java.datatransfer java.xml java.prefs java.desktop java.instrument
java.logging java.management java.security.sasl java.naming java.net.http
java.rmi java.scripting java.security.jgss java.transaction.xa java.sql
jdk.charsets jdk.crypto.ec jdk.localedata jdk.management jdk.unsupported
```

`jre/legal/` 保留 21 个模块的许可证目录。

### 5.5 app.asar 内容

```text
\build
\build\icon.png
\main.cjs
\package.json
\paths.cjs
```

未包含 `test/`、`node_modules/`、用户数据或构建日志。

---

## 6. 验证结果

| 检查项目 | 结果 | 证据 |
|---|---|---|
| 源码迁移 | PASS | `desktop/` 白名单文件；原练习目录 哈希不变 |
| npm 安装（frontend） | PASS | `npm ci` 成功，957 packages |
| npm 安装（desktop） | PASS | `npm ci` 成功，283 packages |
| Vue 构建 | PASS | `vue-cli-service build` 成功；仅有体积警告 |
| Spring Boot 构建 | PASS | `mvnw clean package -DskipTests` BUILD SUCCESS |
| JAR 静态资源 | PASS | JAR 内 `static/index.html`、`static/js/app.*.js` |
| jlink | PASS | `java -version` 成功；174 files；`legal/` 存在 |
| Electron portable 构建 | PASS | `electron-builder` 成功；EXE 200,998,035 bytes；exit 0 |
| 打包资源完整性 | PASS | `app.asar`、`app/bearcoding-backend.jar`、`jre/bin/java.exe`、`jre/release` 均存在；JAR 哈希一致 |
| 打包 JRE/JAR 启动 | PASS | 隔离数据目录；`/chat` 200；Tomcat 启动；Java 退出 |
| 动态端口 | PASS | 本次 `--server.port=29444`（每次不同） |
| 无 Token API | PASS | `/ai/models` 返回 401 |
| 带 Token API | PASS | `/ai/models` 返回 200，隔离目录返回 `[]` |
| 仅回环监听 | PASS | `Get-NetTCPConnection` 仅 127.0.0.1/::1 |
| 无模型提示 | PASS | Edge headless 渲染 `/chat`，页面文本含“请先配置模型” |
| Vue 页面 | PASS | 标题 BearCoding；无 console/page error；截图 `desktop/test-user-data/phaseb-packaged-runtime-20261115-205938/frontend-chat.png` |
| 数据目录隔离 | PASS | 测试数据在 `desktop/test-user-data/...`；SQLite/secrets 生成在隔离目录 |
| 令牌未进 Java 标准输出 | PASS | 验证脚本比对 stdout，未出现本次令牌 |
| 完整 Electron 窗口启动 | UNVERIFIED | `electron.exe` 启动阶段崩溃；最小 Electron 应用同样复现 `0xc0000005`；无应用日志 |
| Electron 令牌注入运行态 | UNVERIFIED | 依赖 Electron 窗口；静态代码保留并审查 |
| 单实例运行态 | UNVERIFIED | 依赖 Electron 窗口；代码保留 |
| Electron 退出清理运行态 | UNVERIFIED | 依赖 Electron 窗口；Java 进程手工停止验证通过 |
| 构建输出 Git 忽略 | PASS | `git check-ignore` 命中 `.build`、`release`、`test-user-data` |
| 发布目录敏感文件扫描 | PASS | 无 `.db`、`.key`、`.log`、`secrets`、`logs`、`test-user-data` |
| 无 Git 提交/推送 | PASS | `git status` 仍为 `No commits yet on main` |

### 6.1 打包运行时验证输出（摘要）

```json
{
  "chatStatus": 200,
  "noTokenApiStatus": 401,
  "tokenApiStatus": 200,
  "modelConfigured": false,
  "systemModelAvailable": false,
  "hasUserModels": false,
  "loopbackOnly": true,
  "sqliteCreated": true,
  "secretsCreated": true,
  "tokenNotInStdout": true,
  "frontendTitle": "BearCoding",
  "frontendPromptFound": true,
  "frontendApiStatus": 200,
  "frontendConsoleErrors": [],
  "frontendPageErrors": [],
  "javaExited": true
}
```

### 6.2 Electron 启动限制证据

- 启动 `desktop/node_modules/electron/dist/electron.exe` 运行最小 Electron 应用时，应用在 `whenReady()` 前退出。
- Windows 应用程序日志记录：`electron.exe 45.3.2.0`，异常代码 `0xc0000005`，故障偏移 `0x0000000007f1e34b`。
- 使用 `--no-sandbox --disable-gpu --headless --single-process` 仍复现；说明不是 BearCoding `main.cjs` 的路径/JS 异常。
- 因此未启动 `desktop/release/BearCoding-3.0.0-portable.exe`，避免它使用正式 `%LOCALAPPDATA%\BearCoding` 数据目录。
- 该限制需要在普通 Windows 交互桌面复核；若在普通桌面仍复现，则升级为 P0 产品问题。

---

## 7. 安全复查

### 7.1 Desktop Token

- 每次启动 `crypto.randomBytes(32).toString("hex")`，64 字符。
- 只通过 `BEARCODING_LOCAL_TOKEN` 环境变量传给 Java。
- 只对 `http://127.0.0.1:<动态端口>/*` 且 `webContentsId` 匹配的请求注入 `X-BearCoding-Token`。
- 未写入日志、数据库、配置文件、localStorage、Cookie 或页面。
- 后端 desktop 模式令牌缺失/空白/短于 32 字符时 fail-closed。
- 打包运行时验证：无 Token 401，带 Token 200，Java stdout 未出现本次令牌。

### 7.2 Electron 安全选项

```text
nodeIntegration: false
contextIsolation: true
sandbox: true
```

- `will-navigate` / `will-redirect` 只允许本应用 Origin。
- `setWindowOpenHandler` 返回 `deny`。
- 未引入 preload、webview 或外部页面。

### 7.3 Java 子进程

- 以 `--server.address=127.0.0.1` 启动。
- `windowsHide: true`，stdio 重定向到 `logs/backend.log`。
- 退出时 `taskkill /PID <pid> /T /F`，清理 Java 及子进程。
- 仍继承父进程环境（迁移前行为）。若父进程带无关敏感变量，Java 会继承；列为 P2，Phase B 未改变。

### 7.4 用户数据

- 正式数据根保持 `%LOCALAPPDATA%\BearCoding`。
- SQLite、模型配置、DPAPI 密钥、聊天历史、MCP 凭证、搜索 Key 的路径语义未改变。
- 所有验证使用 `desktop/test-user-data/` 下的隔离目录；未读取、写入或删除正式用户数据。正式数据根目录元数据在验证后仍为 `LastWriteTime=2026-11-15 16:12:37`（早于本轮验证）。
- 未删除任何 SQLite 数据库（包括测试库）。

### 7.5 发布候选

- `git ls-files --others --exclude-standard` 检查 725 个未跟踪文件。
- 过滤 `.exe`、`.db`、`.key`、`.pem`、`.log`、`node_modules`、`dist`、`target`、`runtime`、`release`、`.build`、`test-user-data`、`secrets`、`logs`、`data`：无命中。
- `desktop/` 未跟踪源码仅 12 个白名单文件（迁移源码、测试、README、图标）。
- 未执行 Git 暂存/提交/推送。

---

## 8. 仍存在的问题

### P0

- 本次迁移未证明引入新的 P0 构建/路径问题；构建、JAR/JRE、Vue 页面和鉴权均通过。
- **未决验证**：完整 Electron 窗口启动在本宿主崩溃（`0xc0000005`），无法完成 PASS 标准中的“本地启动链路”与 Electron 令牌注入/退出清理运行态验证。若普通交互桌面同样复现，应升级为 P0。

### P1

- EXE 未签名，公开下载会触发 SmartScreen；发布前需要代码签名。
- Oracle JDK 17.0.12 的再分发许可未在 Phase B 做法律结论；`legal/` 已保留，Phase C 必须复核。
- 未在干净 Windows 虚拟机验证“无 JDK 机器”启动；本机验证使用了打包 JRE，但没有做干净 VM 验收。
- 前端构建产物哈希在多次构建间变化（本会话曾出现 `app.73867873.js` 与 `app.daede8d5.js`；最终 JAR 使用 `app.daede8d5.js`），说明当前前端构建不是逐字节可复现；构建流程可重复，但发布哈希需以每次 `build-info.json` 为准。

### P2

- Java 子进程继承父进程全部环境变量（既有行为），可能继承无关敏感变量。
- `%TEMP%` 对当前用户不可写时，JNA/DPAPI 初始化失败；正常用户临时目录可写，测试脚本已使用隔离 TEMP。
- `backend.log` 只追加、不轮转（既有行为）。
- 未做代码签名、自动更新、NSIS、macOS/Linux、GitHub Actions（本轮明确不做）。

---

## 9. 发布准备判断

**Q1：只克隆 Final 源码，是否已可重新构建 Windows portable EXE？**  
是。需要 Windows x64、Node 22.12+、JDK 17、Maven Wrapper/npm 网络或缓存；`desktop/build-portable.ps1` 使用项目相对路径，已从 Final 独立构建成功。

**Q2：构建流程是否完全不依赖 原练习目录？**  
是。`desktop/` 源码、`package.json`、`build-portable.ps1`、`paths.cjs` 均不引用 原练习目录；构建输入来自 Final 的 `frontend/`、`backend/`、`desktop/build/` 和 Electron 依赖缓存。

**Q3：是否有私人数据库、日志、密钥、正式用户数据被迁入 Final？**  
没有。未复制 原练习目录 或正式 `%LOCALAPPDATA%\BearCoding` 的任何数据。`desktop/test-user-data/` 下存在验证过程生成的测试 SQLite、测试密钥和日志，但该目录被 `.gitignore` 忽略，不进入 Git 候选或发布包。

**Q4：桌面 Token、动态端口和 Java 进程管理是否保持原有行为？**  
代码层面保持：随机令牌、环境变量传递、仅本应用 Origin 注入、动态空闲端口、`taskkill /T /F` 清理、单实例锁均保留。打包后端已验证 401/200 与动态端口；Electron 运行态的令牌注入、单实例和退出清理因本宿主 Electron 崩溃标记 UNVERIFIED。

**Q5：当前是否可以进入 Phase C？**  
可以进入 Phase C 的准备与清理（许可证、素材授权、公开文件范围、文档），但不应宣布发布就绪。建议先在普通 Windows 交互桌面完成一次 `test:electron` 冒烟，再关闭 Phase B 的 CONDITIONAL 项。

---

## 10. 复现命令

```powershell
cd <repo>\desktop
npm test
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-portable.ps1
npm run test:runtime
# 需要可正常启动 Electron 的交互桌面：
npm run test:electron
```
