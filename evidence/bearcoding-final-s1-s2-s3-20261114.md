# BearCoding Final —— 打包前三项必要修复（S1/S2/S3）

日期：2026-11-14　项目：`D:\BearCoding-Final`
范围：只做了 S1 桌面本地 API 启动令牌、S2 首次无模型引导、S3 数据根统一与搜索 Key 本地加密。
未做：Electron 集成、EXE 打包、真实模型调用、真实付费搜索调用、Git 提交/分支操作。
未触碰：V3、V1/V2、正式 8082、正式数据库、用户原有数据目录。

---

## 1. 结论

| 项 | 结论 | 证据 |
|---|---|---|
| S1 启动令牌 | 完成 | `DesktopTokenFilter`、`DesktopTokenFilterTest`、HTTP 冒烟 401/200/SSE |
| S2 首次无模型引导 | 完成 | `/ai/models/status`、`ChatControllerNoModelTest`、前端 52 项、HTTP 冒烟 |
| S3 搜索 Key 本地加密 | 完成 | `SearchCredentialStore`、`SearchKeyImportRunner`、DPAPI 冒烟与日志检查 |
| 数据根统一 | 完成（复用现有解析） | `DesktopDataRootContractTest`、冒烟目录结构 |
| 后端全量回归 | 1591 run / 0 fail / 0 error / 9 skipped | `s1s2s3-backend-full-test.log` |
| 前端 | 52/52 node、lint 0 错误、build 成功 | `s1s2s3-frontend-*.log` |
| HTTP 冒烟 | 25/25（全新隔离数据根） | `s1s2s3-http-smoke-results.json` |
| 密钥泄漏 | 日志/响应/构建资源未发现真实 Key | 冒烟日志检查 + 源码扫描 |
| 真实 EXE 鉴权验收 | **未做**（本轮不开始 Electron） | 需用户集成后自己验 |

---

## 2. S1：桌面本地 API 启动令牌

### 2.1 模式区分

- `app.auth.mode=local`（默认）：浏览器开发/自动化验收，行为与加鉴权前一致。
- `app.auth.mode=desktop`：Electron 正式启动，所有本地 API 强制令牌。

### 2.2 保护范围

`DesktopTokenFilter` 保护：

```
/ai       /ai/**      （chat、stream、models、conversations、workspace、mcp、approvals …）
/api      /api/**
/user     /user/**
```

不保护：`/`、`/chat`、`/models`、`/mcp`、`/js/**`、`/css/**`、`/fonts/**`、`/img/**`、`/favicon.ico`。
这样 Vue 静态页面可以正常加载，但页面发出的 API 请求必须带令牌。

### 2.3 令牌生成与传递（Electron 侧尚未实现，接口已定）

```js
const LOCAL_TOKEN = crypto.randomBytes(32).toString('hex') // 64 位十六进制
spawn(JAVA, ['-jar', JAR, '--app.auth.mode=desktop'], {
  env: {
    ...process.env,
    JINGJING_DATA_DIR: path.join(process.env.LOCALAPPDATA, 'BearCoding'),
    BEARCODING_LOCAL_TOKEN: LOCAL_TOKEN
  }
})
```

`session.webRequest.onBeforeSendHeaders` 只对本应用后端 origin 注入：

```js
win.webContents.session.webRequest.onBeforeSendHeaders(
  { urls: [`http://127.0.0.1:${port}/*`] },
  (details, callback) => {
    details.requestHeaders['X-BearCoding-Token'] = LOCAL_TOKEN
    callback({ requestHeaders: details.requestHeaders })
  }
)
```

禁止把令牌写入 Vue 页面、localStorage、Cookie、preload 暴露对象或静态配置。

### 2.4 fail-closed

- desktop 模式令牌缺失、空白、短于 32 字符 → 应用启动失败，**不会**退回无鉴权。
- 无令牌、空令牌、错误令牌 → 401，响应体 `{"error":"未授权：缺少或无效的桌面启动令牌"}`，不回显令牌。
- `OPTIONS` 预检不执行业务，允许匿名；真正的 GET/POST 仍校验。
- SSE `POST /ai/stream` 与普通 API 走同一过滤器，带令牌正常流式返回。
- 终端命令子进程环境会摘掉 `BEARCODING_LOCAL_TOKEN`，避免模型执行命令读取令牌。
- 只从请求头读取，不接受查询参数（避免进访问日志）。

### 2.5 测试

`DesktopTokenFilterTest` 8 项：local 放行、正确令牌、缺失/空/错误令牌、所有 API 前缀、静态资源、矩阵参数绕过、OPTIONS、fail-closed、日志不含令牌、子进程环境清理。

---

## 3. S2：首次启动没有模型

### 3.1 判定

`GET /ai/models/status` 返回：

```json
{ "systemModelAvailable": false, "hasUserModels": false, "modelConfigured": false }
```

- `systemModelAvailable`：只看 `spring.ai.openai.api-key` 是否真的非空。
- `hasUserModels`：当前用户 `user_model_config` 是否至少一条。
- `modelConfigured`：两者任一。

`AiConfig` 在未配置 Key 时仍会创建一个指向 `http://127.0.0.1` 的占位 ChatModel（为了依赖注入），它**不算**可用模型。

### 3.2 行为

- `/chat` 顶部显示“请先配置模型”，按钮进入 `/models`；发送按钮禁用，`Enter` 也会被拦下。
- 下拉里不再出现“系统兜底”选项（除非系统 Key 真的配置了）。
- 后端 `resolveModel` 在 `useSystemModel=true` 但系统 Key 为空时抛 `ModelNotConfiguredException`；
  `/ai/chat` 原样返回“请先在「模型配置」页添加并保存一个模型”，`/ai/stream` 通过 SSE `event:error` 返回同一句话。
- 不会把请求发给 `http://127.0.0.1` 占位模型。
- 用户在 `/models` 新增并保存后，回到 `/chat` 正常；重启后模型配置从 SQLite 恢复。
- 开发环境如果配置了系统模型 Key，保留原来的系统模型能力。

### 3.3 测试

- `ModelAvailabilityServiceTest` 5 项：空库、系统 Key、用户模型、无身份、mapper 返回 null。
- `ChatControllerNoModelTest` 4 项：`/chat`、默认模型路径、`/stream`、无搜索 Key 时 webSearch 隐藏。
- HTTP 冒烟：空库 status=false → 添加模型 → status=true → 重启后模型仍在。
- 前端 `modelAvailability.node.test.mjs` 5 项：空库、仅用户模型、仅系统模型、字段缺失、snake_case。

---

## 4. S3：数据根与搜索 Key 本地加密

### 4.1 数据根

统一为 `%LOCALAPPDATA%\BearCoding`：

```
data\jingjing.db
secrets\mcp-credential.key
secrets\model-aes.key
secrets\artifact-ref.key
secrets\search-credential.bin
logs\
workspaces\
mcp-output\
```

`JINGJING_DATA_DIR` 传的是**数据根本身**，不能再拼 `data`。`DesktopDataRootContractTest` 断言：

- `JINGJING_DATA_DIR=<root>` → `<root>\data\jingjing.db`
- 不出现 `data\data`
- `secrets`、`workspaces`、`mcp-output`、`logs` 都在同一根下
- 搜索凭证默认在 `<root>\secrets\search-credential.bin`

### 4.2 搜索 Key 存储

`SearchCredentialStore`：

- 文件格式：`JJSK` + 版本 + DPAPI（CurrentUser）密文；
- 明文载荷：`provider + "\n" + apiKey`；
- 文件里没有明文、没有可跨机器复用的密钥；
- 写入用临时文件 + ACL 收紧 + 回读校验 + 原子替换；
- 默认拒绝覆盖已有密文，`replace=true` 才替换；
- 解密失败（换用户/换机器/损坏）明确抛错，不静默生成新密钥；
- `toString()` 不输出 Key。

优先级：本地密文 → `app.search.api-key` 配置/`SEARCH_API_KEY` 环境变量。没有 Key 时应用其它功能正常，`webSearch` 工具与提示词整段不下发。

### 4.3 一次性导入

```powershell
cd D:\BearCoding-Final\backend
$env:JINGJING_DATA_DIR = "$env:LOCALAPPDATA\BearCoding"
java -jar target\bailianAI-3.0.0.jar --spring.main.web-application-type=none --app.search.import-key=true
Remove-Item Env:JINGJING_DATA_DIR
```

- 交互式控制台隐藏输入，也可用 `SEARCH_API_KEY` 环境变量或 `--app.search.import-from-file=<绝对路径>`；
- 指定 provider：`--app.search.import-provider=tavily`；
- 替换：`--app.search.import-force=true`；
- 停用：`--app.search.remove-key=true`；
- 输出只给路径和“已保存/已删除”，不显示 Key。

### 4.4 安全边界（如实说明）

Windows DPAPI 保护的是本机落盘数据，不保证抵御当前登录用户权限下的恶意程序。
开发机上加密的 Key 不能拿到另一台 Windows 电脑自动解密。
个人自用 EXE 可以复用本机已导入的 Key；未来公开分发的 EXE 不包含任何用户自己的真实搜索凭证。

### 4.5 测试

- `SearchCredentialStoreTest` 7 项：密文不含明文、回读、拒绝覆盖、替换、删除、换密钥失败、损坏文件、路径解析、非法输入。
- `SearchServiceCredentialTest` 7 项：密文优先、配置回退、解密失败明确提示、无开发者路径、启动日志不含 Key、provider 优先、错误体回显 Key 时替换为 `<已隐藏>`。
- `SearchKeyImportServiceTest` 4 项：导入、拒绝覆盖、空 Key、停用。
- HTTP 冒烟：导入退出 0、文件存在、输出/文件/日志不含假 Key、重启后 `来源=本地加密存储`。

---

## 5. 修改文件

### 后端新增

- `backend/src/main/java/com/example/jingjingai2/security/DesktopAuthProperties.java`
- `backend/src/main/java/com/example/jingjingai2/security/DesktopTokenFilter.java`
- `backend/src/main/java/com/example/jingjingai2/security/DesktopTokenSecrets.java`
- `backend/src/main/java/com/example/jingjingai2/security/SearchCredentialStore.java`
- `backend/src/main/java/com/example/jingjingai2/search/SearchKeyImportService.java`
- `backend/src/main/java/com/example/jingjingai2/search/SearchKeyImportRunner.java`
- `backend/src/main/java/com/example/jingjingai2/service/ModelAvailabilityService.java`
- `backend/src/main/java/com/example/jingjingai2/service/ModelNotConfiguredException.java`

### 后端修改

- `backend/src/main/resources/application.yml`：`app.auth.mode/local-token`、搜索凭证/导入配置。
- `backend/src/main/java/com/example/jingjingai2/controller/ChatController.java`：模型守卫、无模型异常、webSearch 条件下发、提示词摘除。
- `backend/src/main/java/com/example/jingjingai2/controller/ModelConfigController.java`：`GET /ai/models/status`。
- `backend/src/main/java/com/example/jingjingai2/search/SearchService.java`：本地密文优先、启动状态日志、无 Key 提示。
- `backend/src/main/java/com/example/jingjingai2/utils/WebSearchTool.java`：`isEnabled()`。
- `backend/src/main/java/com/example/jingjingai2/agent/SubagentRunner.java`：无 Key 时不把 webSearch 放进子代理工具。
- `backend/src/main/java/com/example/jingjingai2/config/AgentPrompts.java`：`WEB_SEARCH_RULES` / `WEB_SEARCH_TOOL_LINE` / `withoutWebSearch`。
- `backend/src/main/java/com/example/jingjingai2/sandbox/ApprovedCommandLauncher.java`：子进程环境摘令牌。
- `backend/src/main/java/com/example/jingjingai2/service/CodeVerifier.java`、`eval/ExpectationJudge.java`：自动编译/评测子进程同样摘令牌。

### 前端

- `frontend/src/api/model.js`：`getModelStatus`。
- `frontend/src/utils/modelAvailability.js`（新增）+ `.node.test.mjs`。
- `frontend/src/views/ChatView.vue`：首屏引导、发送禁用、系统模型选项条件、模型状态加载。
- `frontend/src/views/ModelSettings.vue`：动态状态提示。

### 测试新增/修改

- 新增：`DesktopTokenFilterTest`、`SearchCredentialStoreTest`、`SearchServiceCredentialTest`、`SearchKeyImportServiceTest`、`ModelAvailabilityServiceTest`、`DesktopDataRootContractTest`、`ChatControllerNoModelTest`。
- 修改：`SearchServiceTest`（新提示语）、`AgentPromptsTest`（摘除 webSearch）、`ChatEntryPromptViewTest` / `M4CombinedContextTest` / `WorkingSetProductionConfigTest`（测试装配补系统模型 Key）。

### 文档/证据

- `docs/DESKTOP-PACKAGING.md`：数据根、令牌、S2、S3 导入、验收表。
- `README.md`、`ACCEPTANCE.md`、`docs/HANDOFF-NEXT-DSH.md`：测试数字与状态。
- `.gitignore`：`secrets/`、`search-credential.bin`。
- `evidence/s1s2s3-http-smoke.mjs`、`s1s2s3-http-smoke-results.json`、日志。

---

## 6. 回归结果

| 项 | 结果 |
|---|---|
| 后端 `mvn -o test` | **1591 run / 0 failures / 0 errors / 9 skipped**，BUILD SUCCESS |
| 前端 node | **52 / 52** |
| 前端 lint | **0 错误** |
| 前端 build | 成功，入口 `js/app.daede8d5.js`、`css/app.4e5f7345.css` |
| jar | 57,001,126 字节，SHA-256 `1250FFDE9668300DE3216E9128F07927E2535C2AEF641B8F7783785075BDE9D2` |
| HTTP 冒烟 | **25 / 25**（全新隔离数据根，端口动态，未访问 8082/正式库） |
| 密钥泄漏 | 日志、401 响应、模型响应、搜索导入输出、构建静态资源均未发现假 Key/令牌 |

历史基线：1553 / 47 / 29 / 16；本轮新增 38 项后端、5 项前端，未关闭任何安全控制。

---

## 7. 未解决/未验收（不阻断用户自己打包）

1. **真实 Electron EXE 鉴权未验收**：本轮只做了离线/HTTP 模拟；用户集成后需在真实窗口里确认
   `onBeforeSendHeaders` 注入、导航限制、退出清理。
2. **真实模型未调用**：S2 只验证到“无模型拒绝”和“添加模型后重启仍在”，没有付费调用。
3. **真实搜索未调用**：只验证了 DPAPI 保存/读取和 provider 选择，没有请求 Tavily/Serper/博查。
4. **真实外部 MCP、原生文件夹选择弹窗**仍沿用上一轮未验证项。
5. `frontend/e2e/workspace-groups.spec.js` 仍过期（非本轮范围）。

---

## 8. 下一步（用户自己动手）

1. 按 `docs/DESKTOP-PACKAGING.md` §3 构建前端并 `mvn clean package`。
2. 用 jlink 生成精简 JRE。
3. 按 §4 写 `desktop/main.cjs`：生成令牌、传 `JINGJING_DATA_DIR`/`BEARCODING_LOCAL_TOKEN`、
   `--app.auth.mode=desktop`、`session.webRequest` 注入、`will-navigate` 限制、`taskkill /T`。
4. 用 §5.2 命令导入一次搜索 Key（可选）。
5. 先出 `win-unpacked` 冒烟，再出 NSIS 安装包；干净虚拟机跑 §8 验收表。
