# BearCoding 桌面打包流程建议

日期：2026-11-14　适用版本：`bailianAI-3.0.0.jar`（57,001,126 字节，SHA-256 `1250FFDE…BDE9D2`）
前置报告：`evidence/bearcoding-final-final-regression-20261114.md`

---

## 0. 推荐路线（先给结论）

**Electron 外壳 + 精简 JRE（jlink）+ 现有 fat jar + NSIS 安装包。**

理由：

1. 后端已经是“自带 Tomcat + 静态 SPA”的 Spring Boot fat jar，桌面端只需要
   “拉起 java 进程 + 等端口就绪 + 用窗口加载 `http://127.0.0.1:<port>`”。
2. 前端是 Vue 3 + Naive UI，Electron 直接复用，不需要重写 UI。
3. 团队已有 Node/npm 工具链（Vue CLI、Playwright），Electron 的学习成本最低。
4. 不采用 `jpackage` 单打：jpackage 能生成带 JRE 的启动器，但不提供现代 WebView；
   配 JavaFX WebView 等于把页面塞回老 Chromium，Naive UI 的兼容风险不值得。
5. 不采用 Tauri：安装包更小，但要多维护一套 Rust 工具链，收益不足以抵消成本。

目标产物：`BearCoding-Setup-3.0.0.exe`（每用户安装，约 120–160 MB），
安装后双击即用，机器上**不需要预装 JDK**。

---

## 1. 现状与约束（打包前必须知道）

| 项 | 现状 | 对打包的影响 |
|---|---|---|
| 后端 | Spring Boot 3.5，内嵌 Tomcat，默认 `127.0.0.1:8082` | 端口要可配置；只绑回环 |
| 前端 | `frontend/dist` 打进 `BOOT-INF/classes/static` | 每次前端改动都要重新 copy + `mvn clean package` |
| 数据 | `app.data.mode=desktop`，`JINGJING_DATA_DIR` 或 `%LOCALAPPDATA%\BearCoding` | 安装包不要写 Program Files；数据留在用户目录 |
| 密钥 | Windows DPAPI + AES-GCM，`secrets\*.key` | 同一 Windows 用户才能解密；换用户/换机器读不了旧库 |
| 身份 | `app.auth.mode=local`，本机固定 userId=1，无登录 | 本机任何进程都能调 API，桌面版建议加启动令牌（§6） |
| 文件夹选择 | 后端 Swing `JFileChooser`（`java.desktop`） | 精简 JRE 必须带 `java.desktop`；无图形会话要有降级提示 |
| MCP | stdio / Streamable HTTP（`java.net.http`） | 精简 JRE 要带 `java.net.http`；stdio 子进程随主进程退出要清理 |
| 构建 | Maven 3.9 + JDK 17 + Node 22 | CI 与开发机版本要固定 |

---

## 2. 建议的仓库/产物结构

```
BearCoding/
├─ backend/                     # 现有 Spring Boot
├─ frontend/                    # 现有 Vue
├─ desktop/                     # 新增：Electron 外壳
│  ├─ main.js                   # 主进程：端口、spawn java、窗口、退出
│  ├─ preload.js                # 只暴露最小 API（如版本号、日志路径）
│  ├─ package.json              # electron-builder 配置
│  └─ build/
│     ├─ icon.ico
│     └─ installer.nsh          # 可选：卸载时询问是否保留数据
└─ dist-release/                # 产物（不进 Git）
   ├─ BearCoding-Setup-3.0.0.exe
   └─ win-unpacked/             # 免安装目录，便于先做冒烟
```

安装后的目录（每用户安装，`%LOCALAPPDATA%\Programs\BearCoding`）：

```
BearCoding.exe
resources\
  app.asar
  runtime\jre\bin\java.exe      # jlink 精简 JRE
  runtime\app\bailianAI-3.0.0.jar
```

数据目录（不随安装包走，统一根 = `%LOCALAPPDATA%\BearCoding`）：

```
%LOCALAPPDATA%\BearCoding\
  data\jingjing.db
  secrets\mcp-credential.key
  secrets\model-aes.key
  secrets\artifact-ref.key
  secrets\search-credential.bin     # 搜索 API Key（DPAPI 密文）
  logs\backend.log
  workspaces\...
  mcp-output\...
```

> **不要传 `...\BearCoding\data` 给 `JINGJING_DATA_DIR`。** 这个变量是“数据根本身”；
> 传错会得到 `...\BearCoding\data\data\jingjing.db`。正确值是 `...\BearCoding`。

---

## 3. 打包流水线（从源码到安装包）

### 3.1 冻结版本（当前仓库还没有任何提交）

```powershell
cd D:\BearCoding-Final
git init
git add .
git commit -m "BearCoding 3.0.0: final regression baseline"
git tag v3.0.0
```

> `backend/src/main/resources/static/`、`frontend/dist/`、`backend/target/`、
> `frontend/node_modules/` 都是构建产物，提交前确认已在 `.gitignore` 里。

### 3.2 前端

```powershell
cd frontend
npm ci
npm run lint
npm run test:group          # 52 项 node 测试
npm run build               # 产物在 frontend/dist
```

### 3.3 前端进 jar

```powershell
$dst = "D:\BearCoding-Final\backend\src\main\resources\static"
Remove-Item "$dst\*" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "D:\BearCoding-Final\frontend\dist\*" $dst -Recurse -Force
```

### 3.4 后端打包（必须 clean）

```powershell
cd backend
mvn -o test                 # 1616 项，0 失败 / 0 错误 / 9 跳过
mvn -o clean package -DskipTests
```

验收 jar：

```powershell
# 1) 只应有一份 app.*.js / app.*.css
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead("target\bailianAI-3.0.0.jar")
$z.Entries | ? { $_.FullName -match 'static/(js|css)/app\.' } | % FullName
$z.Dispose()

# 2) 记录指纹
(Get-FileHash target\bailianAI-3.0.0.jar -Algorithm SHA256).Hash
```

### 3.5 精简 JRE

先看依赖（fat jar 的 `jdeps` 结果只能作参考，最终以模块清单 + 启动测试为准）：

```powershell
jdeps --multi-release 17 --ignore-missing-deps --print-module-deps `
  backend\target\bailianAI-3.0.0.jar
```

建议的稳妥模块集（含 Swing 选择器、SQLite、HTTP MCP、DPAPI/JNA、TLS）：

```powershell
jlink `
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,`
java.net.http,java.prefs,java.sql,java.transaction.xa,jdk.crypto.ec,jdk.unsupported `
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 `
  --output dist-release\runtime\jre
```

验证：

```powershell
dist-release\runtime\jre\bin\java.exe -version
dist-release\runtime\jre\bin\java.exe -jar backend\target\bailianAI-3.0.0.jar --server.port=18210
```

> 不要为了省 20 MB 去掉 `java.desktop`：`/ai/workspace/pick-directory` 会直接不可用。
> 也不要去掉 `jdk.crypto.ec`：部分模型/MCP 端点走 ECDHE 会握手失败。

### 3.6 Electron 外壳

`desktop/package.json` 关键字段：

```json
{
  "name": "bearcoding",
  "version": "3.0.0",
  "main": "main.js",
  "build": {
    "appId": "com.bearcoding.desktop",
    "productName": "BearCoding",
    "directories": { "output": "../dist-release" },
    "files": ["main.js", "preload.js", "package.json"],
    "extraResources": [
      { "from": "../backend/target/bailianAI-3.0.0.jar", "to": "app/bailianAI-3.0.0.jar" },
      { "from": "runtime/jre", "to": "jre" }
    ],
    "win": {
      "target": [{ "target": "nsis", "arch": ["x64"] }],
      "icon": "build/icon.ico",
      "signAndEditExecutable": true
    },
    "nsis": {
      "oneClick": false,
      "perMachine": false,
      "allowToChangeInstallationDirectory": true,
      "deleteAppDataOnUninstall": false
    }
  }
}
```

### 3.7 生成安装包

```powershell
cd desktop
npm ci
npx electron-builder --win nsis
```

先出 `dist-release\win-unpacked` 做冒烟，再签安装包（§7）。

---

## 4. Electron 主进程要点

`desktop/main.js` 的最小可靠骨架（伪代码，按现有 API 调整）：

```js
const { app, BrowserWindow, dialog, shell } = require('electron')
const { spawn } = require('child_process')
const net = require('net')
const path = require('path')
const fs = require('fs')

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) app.quit()

const crypto = require('crypto')
const JAVA = path.join(process.resourcesPath, 'jre', 'bin', 'java.exe')
const JAR  = path.join(process.resourcesPath, 'app', 'bailianAI-3.0.0.jar')
// 统一数据根：%LOCALAPPDATA%\BearCoding（不是 userData\data，也不是 data\data）
const DATA_ROOT = path.join(process.env.LOCALAPPDATA, 'BearCoding')
const LOGS = path.join(DATA_ROOT, 'logs')
// 每次启动生成新令牌；只通过环境变量交给 java 进程，不写文件、不进页面。
const LOCAL_TOKEN = crypto.randomBytes(32).toString('hex')
let backend = null
let win = null
let port = 0

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port
      s.close(() => resolve(p))
    })
    s.on('error', reject)
  })
}

async function waitHealthy(p, timeoutMs = 90000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      // 桌面鉴权模式下健康检查也要带令牌；否则会一直拿到 401。
      const r = await fetch(`http://127.0.0.1:${p}/ai/models`, {
        headers: { 'X-BearCoding-Token': LOCAL_TOKEN }
      })
      if (r.ok) return true
    } catch (_) {}
    await new Promise(r => setTimeout(r, 500))
  }
  return false
}

async function startBackend() {
  port = await freePort()
  fs.mkdirSync(DATA_ROOT, { recursive: true })
  fs.mkdirSync(LOGS, { recursive: true })
  const out = fs.openSync(path.join(LOGS, 'backend.log'), 'a')
  backend = spawn(JAVA, [
    '-Dfile.encoding=UTF-8',
    '-jar', JAR,
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    '--app.auth.mode=desktop',      // 正式启动必须显式启用，不能默认无鉴权
    '--app.data.mode=desktop'
  ], {
    cwd: DATA_ROOT,
    env: {
      ...process.env,
      JINGJING_DATA_DIR: DATA_ROOT,          // 数据根本身，不要再拼 data
      BEARCODING_LOCAL_TOKEN: LOCAL_TOKEN    // 只走环境变量，不落盘
    },
    windowsHide: true,
    stdio: ['ignore', out, out]
  })
  backend.on('exit', code => {
    if (!app.isQuitting) {
      dialog.showErrorBox('BearCoding 后端已退出', `退出码 ${code}\n日志：${path.join(LOGS, 'backend.log')}`)
      app.quit()
    }
  })
  return waitHealthy(port)
}

function createWindow() {
  win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 1024, minHeight: 680,
    backgroundColor: '#f7f2ea',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  // 只给“本应用后端 origin”的请求注入令牌。不要把令牌写进页面、
  // localStorage、preload 暴露对象或任何静态配置。
  const ORIGIN = `http://127.0.0.1:${port}`
  win.webContents.session.webRequest.onBeforeSendHeaders(
    { urls: [`${ORIGIN}/*`] },
    (details, callback) => {
      details.requestHeaders['X-BearCoding-Token'] = LOCAL_TOKEN
      callback({ requestHeaders: details.requestHeaders })
    }
  )

  win.loadURL(`${ORIGIN}/chat`)
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) shell.openExternal(url)
    return { action: 'deny' }
  })
  // 阻止导航到外部网页，避免认证头被带到非预期来源。
  // 必须比较 URL.origin；startsWith 会把 http://127.0.0.1:port.evil.com 也放过。
  const isAppOrigin = (url) => {
    try { return new URL(url).origin === ORIGIN } catch (_) { return false }
  }
  win.webContents.on('will-navigate', (e, url) => {
    if (!isAppOrigin(url)) e.preventDefault()
  })
  win.webContents.on('will-redirect', (e, url) => {
    if (!isAppOrigin(url)) e.preventDefault()
  })
}

app.whenReady().then(async () => {
  if (!(await startBackend())) {
    dialog.showErrorBox('启动失败', `后端未在 90 秒内就绪。\n日志：${path.join(LOGS, 'backend.log')}`)
    app.quit()
    return
  }
  createWindow()
})

app.isQuitting = false
app.on('before-quit', () => { app.isQuitting = true })
app.on('will-quit', () => {
  if (backend && !backend.killed) {
    // Windows 上直接 kill 可能留下 javac/MCP 子进程，优先 taskkill /T
    require('child_process').execFile('taskkill', ['/PID', String(backend.pid), '/T', '/F'])
  }
})
```

必须覆盖的细节：

| 细节 | 要求 |
|---|---|
| 单实例 | `requestSingleInstanceLock()`；第二次启动激活已有窗口，不新开后端 |
| 端口 | 动态选空闲端口，不要写死 8082（用户可能已占用） |
| 就绪判定 | 轮询 `/ai/models` 200，最多 90 s；失败弹窗给日志路径 |
| 日志 | `%LOCALAPPDATA%\BearCoding\logs\backend.log`，追加写、UTF-8 |
| 退出 | 先 `before-quit` 标记，再 `taskkill /T /F`；确保 javac/MCP 子进程不残留 |
| 崩溃 | 后端非正常退出要弹窗并退出，不要留白窗口 |
| 数据 | 用 `path.join(process.env.LOCALAPPDATA, 'BearCoding')`，不要用 `userData`（默认在 Roaming），更不要写安装目录 |
| 令牌 | 每次启动 `crypto.randomBytes(32).toString('hex')`；只经环境变量传给 java，再由 `onBeforeSendHeaders` 注入请求头 |
| 中文/空格路径 | 全部用 `spawn` 参数数组，不拼命令行字符串 |

---

## 5. 安全加固（桌面版已实现的部分）

`app.auth.mode=local` 只用于浏览器开发/自动化验收；正式 Electron 必须用
`--app.auth.mode=desktop`。

已实现的后端行为（`DesktopAuthProperties` + `DesktopTokenFilter`）：

1. 主进程每次启动生成 `crypto.randomBytes(32).toString('hex')`；
2. 通过环境变量 `BEARCODING_LOCAL_TOKEN` 传给 Spring Boot；
3. desktop 模式只保护 `/ai/**`、`/api/**`、`/user/**`，比对请求头
   `X-BearCoding-Token`；静态 Vue 页面、js/css/fonts/img 仍可匿名加载；
4. 无令牌、空令牌、错误令牌一律 401；`OPTIONS` 预检不执行业务，允许匿名；
5. **fail-closed**：desktop 模式下令牌缺失、空白或短于 32 字符，应用直接启动失败，
   不会退回无鉴权；
6. SSE（`POST /ai/stream`）和普通 JSON API 走同一个过滤器，带令牌即可正常流式输出；
7. 令牌不进日志、不进 401 响应、不进前端静态资源；后端拉起终端命令时会从子进程
   环境里摘掉 `BEARCODING_LOCAL_TOKEN`。

Electron 侧必须做：

- `session.webRequest.onBeforeSendHeaders` 只对 `http://127.0.0.1:<port>/*` 注入请求头；
- **不要**把令牌放进 preload、`window.*`、localStorage、Cookie 或页面脚本；
- `will-navigate` / `will-redirect` 只允许本应用 origin，外链 `shell.openExternal`；
- `--server.address=127.0.0.1` 显式传，避免配置漂移绑到 `0.0.0.0`；
- BrowserWindow 关闭 `nodeIntegration`、开启 `contextIsolation`/`sandbox`；
- 安装包用代码签名证书（EV 或 OV），否则 SmartScreen 首次运行会拦；
- 不要把任何真实 API Key 打进安装包或日志。

> 边界：启动令牌防的是“本机其它进程直接调本地 API”，不是防同权限恶意程序
> 读取进程环境/内存。个人自用版足够；公开分发版仍应保持最小权限与代码签名。

---

### 5.1 首次启动没有模型时（S2）

- 后端 `GET /ai/models/status` 返回 `{ systemModelAvailable, hasUserModels, modelConfigured }`；
- 系统模型是否可用只看 `spring.ai.openai.api-key` 是否真的配置了。`AiConfig` 里那个
  指向 `http://127.0.0.1` 的占位 ChatModel **不算**可用模型；
- 两者都没有时，`/chat` 顶部显示“请先配置模型”并给出“去配置模型”按钮，发送按钮禁用；
- 即使绕过前端直接 `POST /ai/chat` 或 `/ai/stream`，后端也返回可读的
  “请先在「模型配置」页添加并保存一个模型”，不会把请求发给占位地址；
- 用户到 `/models` 新增并保存后，回到 `/chat` 即可正常使用；重启后模型配置从 SQLite 恢复；
- 开发环境如果确实配置了系统模型 Key，仍保留原来的系统模型能力。

### 5.2 搜索 API Key 一次性导入（S3）

搜索 Key 不再要求写进 `application.yml` 或 `application-local.yml`。导入后以
Windows DPAPI（CurrentUser）密文保存在：

```
%LOCALAPPDATA%\BearCoding\secrets\search-credential.bin
```

导入命令（交互式隐藏输入，Key 不回显、不进命令行历史）：

```powershell
cd D:\BearCoding-Final\backend
$env:JINGJING_DATA_DIR = "$env:LOCALAPPDATA\BearCoding"
java -jar target\bailianAI-3.0.0.jar --spring.main.web-application-type=none --app.search.import-key=true
Remove-Item Env:JINGJING_DATA_DIR
```

也可以从临时环境变量或文件导入（不会覆盖已有密文，除非显式 `--app.search.import-force=true`）：

```powershell
# 方式 A：临时环境变量
$env:SEARCH_API_KEY = '<你的 key>'
java -jar target\bailianAI-3.0.0.jar --spring.main.web-application-type=none --app.search.import-key=true
Remove-Item Env:SEARCH_API_KEY

# 方式 B：只含一行 Key 的临时文件
java -jar target\bailianAI-3.0.0.jar --spring.main.web-application-type=none `
  --app.search.import-key=true --app.search.import-from-file="D:\temp\search-key.txt"
```

- 指定服务方：加 `--app.search.import-provider=tavily`（或 `serper` / `bocha`）；
- 替换：加 `--app.search.import-force=true`；
- 停用：`--app.search.remove-key=true`（删除密文文件）；
- 确认已保存但**不泄露明文**：看命令输出的“已保存加密搜索凭证：<路径>”，或检查
  `secrets\search-credential.bin` 存在、大小约几百字节；启动日志只会打印
  `[Search] 搜索已启用：provider=...，来源=本地加密存储`，不会打印 Key；
- 无法解密（换 Windows 用户/换机器/文件损坏）时，启动日志会明确提示“本地搜索凭证无法解密”，
  应用其它功能照常，`webSearch` 工具不下发；此时需要重新导入；
- 导入并确认搜索可用后，可以自己把私人 `application-local.yml` 里的 `SEARCH_API_KEY`
  删掉（本工具不会主动删除你的配置）。删之前先确认 `secrets\search-credential.bin` 已存在；
  不要把该文件或明文 Key 复制进仓库、报告或安装包；
- 没有搜索 Key 时应用其它功能完全正常，只是不向模型提供 `webSearch` 工具，提示词里也不提它。

> 安全边界：DPAPI 保护的是“本机落盘数据”，不保证抵御当前登录用户权限下的恶意程序。
> 在开发机加密的 Key 不能拿到另一台 Windows 电脑自动解密；个人自用 EXE 可以复用本机
> 已导入的 Key，公开分发的 EXE 不包含任何用户自己的真实搜索凭证。

## 6. 安装包与更新

- **NSIS 每用户安装**：不需要管理员权限，装到 `%LOCALAPPDATA%\Programs`。
- **卸载保留数据**：`deleteAppDataOnUninstall: false`；如需询问，用自定义 `installer.nsh`。
- **快捷方式**：桌面 + 开始菜单，图标用 `build/icon.ico`（256×256 多尺寸）。
- **自动更新（可选）**：`electron-updater` + GitHub Releases 或自有 HTTPS 静态目录；
  更新前必须确认后端已退出（否则 jar 被占用，替换失败——本轮已实测过这个坑）。
- **回滚**：保留上一个版本的 `win-unpacked` 或安装包，数据目录向后兼容由 Flyway/schema 保证。

---

## 7. CI（GitHub Actions 示例）

```yaml
name: release-windows
on:
  push:
    tags: ['v*']
jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: maven }
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci && npm run lint && npm run test:group && npm run build
        working-directory: frontend
      - run: |
          Remove-Item backend/src/main/resources/static/* -Recurse -Force -ErrorAction SilentlyContinue
          Copy-Item frontend/dist/* backend/src/main/resources/static -Recurse -Force
      - run: mvn -B -o test
        working-directory: backend
      - run: mvn -B -o clean package -DskipTests
        working-directory: backend
      - run: jlink --add-modules ... --output desktop/runtime/jre
      - run: npm ci && npx electron-builder --win nsis --publish never
        working-directory: desktop
        env:
          CSC_LINK: ${{ secrets.WIN_CERT_PFX_BASE64 }}
          CSC_KEY_PASSWORD: ${{ secrets.WIN_CERT_PASSWORD }}
      - uses: actions/upload-artifact@v4
        with: { name: BearCoding-Setup, path: dist-release/*.exe }
```

> `mvn -o` 依赖 runner 的本地缓存；首次跑或缓存未命中时去掉 `-o`。

---

## 8. 打包后的验收清单（在干净 Windows 虚拟机里做）

| # | 检查 | 通过标准 |
|---|---|---|
| 1 | 机器上没有 JDK | 双击能启动，不报“找不到 java” |
| 2 | 首次启动 | 生成 `%LOCALAPPDATA%\BearCoding`（不是 `data\data`），SQLite + 三把密钥齐全；没有模型时显示“请先配置模型”而不是连接失败 |
| 3 | 只监听回环 | `netstat -ano \| findstr :<port>` 只有 `127.0.0.1` |
| 3b | 桌面令牌 | 不带 `X-BearCoding-Token` 的 `/ai/models` 返回 401；带正确令牌返回 200；静态 `/chat` 仍能加载 |
| 4 | 数据隔离 | 安装目录只读也能用；数据不写 Program Files |
| 5 | 二次启动 | 不新开后端，聚焦已有窗口 |
| 6 | 退出 | 窗口关闭后 java 进程消失；无 javac/MCP 残留 |
| 7 | 崩溃恢复 | 手动结束 java，应用弹窗并退出；重启后数据仍在 |
| 8 | 端口占用 | 8082 被别的程序占用也能启动（动态端口） |
| 9 | 中文/空格路径 | 数据目录、工作区绑定中文路径正常 |
| 10 | 文件夹选择 | `+ → 上传文件夹` 弹出系统窗口；取消不报错 |
| 11 | 模型配置 | 保存/脱敏/重启后可用；错误 Key 有可读报错 |
| 11b | 搜索 Key | `secrets\search-credential.bin` 存在；启动日志显示“来源=本地加密存储”；日志/报告无明文 |
| 12 | 卸载 | 程序删除；数据目录保留（或按提示选择） |
| 13 | 签名 | 安装包与 exe 均有有效签名，SmartScreen 不拦 |
| 14 | 更新 | 旧版本升级后数据不丢，后端 jar 能替换 |

---

## 9. 已踩过的坑（务必保留在流程里）

1. **运行中重建 jar 会打崩后端**：`mvn package` 会原地替换 `target\*.jar`，
   正在运行的 JVM 懒加载类时 `ClassNotFoundException`/500。
   打包前先停进程，或像本轮一样“复制 jar 到 run 目录再启动”。
2. **不 clean 会残留旧前端**：`target/classes/static` 只增不减，jar 里会出现多份
   `app.*.js`。每次前端构建后执行 `mvn clean package`。
3. **jar 被占用时 repackage 失败**：报
   `Unable to rename '...jar' to '...jar.original'`。停掉占用进程再打包。
4. **dev-server 深链 404 是假象**：history fallback 只对 `Accept: text/html` 生效，
   `curl` 裸测 `/chat` 会 404；浏览器正常。
5. **受限沙箱里 `node --test` 会 `spawn EPERM`**：CI/本机正常；受限环境用
   `node <file>` 逐个跑，或给测试进程放开管道权限。
6. **DPAPI 绑定用户**：换 Windows 用户或换机器，旧 `secrets` 解不开，启动会明确失败；
   安装/升级流程不要“顺手重建密钥”。
7. **未签名 exe 的 SmartScreen**：内测阶段可接受，对外发布必须签名，否则下载即被拦。
8. **Electron 退出不彻底**：`child.kill()` 在 Windows 上不保证杀掉 java 的子进程，
   用 `taskkill /PID <pid> /T /F`，并在 `will-quit` 里执行。

---

## 10. 建议的推进顺序与工作量

| 阶段 | 内容 | 预估 |
|---|---|---|
| M1 | Git 冻结、CI 跑通（测试 + 构建 + 产物上传） | 0.5–1 天 |
| M2 | jlink 精简 JRE + 命令行启动验证 | 0.5 天 |
| M3 | Electron 主进程（端口、spawn、健康检查、退出清理） | 1–2 天 |
| M4 | 窗口安全、启动令牌、日志与错误弹窗 | 1 天 |
| M5 | electron-builder NSIS + 图标 + 卸载策略 | 0.5–1 天 |
| M6 | 干净虚拟机验收（§8 全表） | 1 天 |
| M7 | 代码签名 + 自动更新（可选） | 1–2 天 |

总计约 **5–8 个工作日**（不含证书采购与真实模型回归）。
建议 M1–M3 先出 `win-unpacked` 版本给内部试用，M6 通过后再对外发安装包。
