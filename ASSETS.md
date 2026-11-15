# 图片、图标与字体资源说明

本文件记录 BearCoding 仓库里实际使用的图片资源、它们被谁引用、格式与体积，以及**授权状态的确认结论**。

> 结论先行：**没有任何一项图片资源能在本轮被确认为 BearCoding 原创或已获授权**。仓库没有 Git 历史，也没有素材来源记录，因此全部标为「待用户确认」。本文件不把文件名当作原创证据。

---

## 1. 实际被引用的资源

| 文件 | 体积 | 实际格式 | 尺寸 | 被谁引用 | 用途 |
|---|---|---|---|---|---|
| `frontend/src/assets/image.png` | 343,779 B | PNG | 690×680 | `frontend/src/components/ChatMessage.vue:73`（`assistantAvatar`） | 消息列表里的助手头像 |
| `frontend/src/assets/ims3.jpg` | 96,131 B | JPEG (JFIF) | 未解析 | `frontend/src/components/ChatMessage.vue:74`（`userFallback`） | 用户消息头像的回退图 |
| `frontend/src/assets/ims.webp` | 64,044 B | WebP | 未解析 | `frontend/src/App.vue:57`（`background-image: url('assets/ims.webp')`） | 页面背景 |
| `frontend/public/favicon.ico` | 4,286 B | ICO | — | `frontend/public/index.html`（`<link rel="icon">`） | 浏览器标签图标 |
| `desktop/build/icon.png` | 1,703,584 B | PNG | 1254×1254 | `desktop/package.json`（`build.icon`、`win.icon`）、`paths.cjs` | 窗口图标与 EXE 图标 |

构建后进入 `backend/src/main/resources/static/` 的只有 `image.*.png`、`ims.*.webp`、`ims3.*.jpg` 和 `favicon.ico`，与上表一致。

## 2. 没有被引用的资源

| 文件 | 体积 | 实际格式 | 尺寸 | 说明 |
|---|---|---|---|---|
| `frontend/src/assets/image copy.png` | 421,507 B | PNG | 734×410 | 文件名带 ` copy`，全仓库无任何引用；不在构建产物里 |
| `frontend/src/assets/ims2.png` | 10,524 B | **WebP**（扩展名写成了 `.png`） | 未解析 | 无引用；扩展名与实际格式不符，若将来被 import 会出问题 |
| `frontend/src/assets/logo.png` | 6,849 B | PNG | 200×200 | 无引用；当前界面没有用到它 |

这三个文件可以视为历史遗留素材。**本轮没有删除它们**（按任务约定，未确认的文件不删除）。若希望精简发布候选，可自行决定删除，或先用 `.gitignore` 排除。

## 3. 需要用户人工确认的授权问题

### 3.1 所有本地图片的来源未知

仓库没有 Git 提交历史，`frontend/src/assets/`、`frontend/public/`、`desktop/build/` 下都没有附带来源说明、授权文件或许可证。因此：

- **不能**在 README、Release 说明或任何对外文案里声称这些图片是「原创设计」；
- **不能**把它们的版权并进 BearCoding 的 MIT 声明（MIT 只覆盖 BearCoding 自己有权授权的部分）；
- 需要你本人确认：每一张图是自绘、委托设计、AI 生成、还是来自网络。若来自网络，需要能说明来源和授权；说明不了就必须替换。

小熊图标（`desktop/build/icon.png`）按项目描述是专门设计的产品资源，但**本轮无法从文件本身验证这一点**（PNG 内只有 `IHDR`/`caBX`/`IDAT` 块，没有可读的作者或软件元数据）。请在发布前确认它的产出方式与使用权。

### 3.2 聊天页头像硬编码了第三方图片 URL（**发布阻塞项**）

`frontend/src/views/ChatView.vue:514`：

```js
const aiAvatar = 'https://kkimgs.yisou.com/ims?kt=url&at=smstruct&key=aHR0cHM6...&sign=...&tv=400_400'
```

该 URL 的 `key` 参数是 Base64，解码后指向 `https://c-ssl.duitang.com/uploads/item/202003/05/20200305172626_gbolv.thumb.1000_0.jpeg`，即**堆糖（duitang.com）上的一张第三方图片**，通过第三方图片代理（`kkimgs.yisou.com`）加载。

由此产生三个独立问题：

1. **版权**：这张图不属于 BearCoding 作者，也没有可见的授权声明。它不会被 MIT 覆盖，仓库里保留这个 URL 等于把「展示一张来源不明的第三方图片」写进源码。
2. **隐私与网络行为**：应用启动后聊天页会向 `kkimgs.yisou.com` 发起请求，把使用者的 IP、UA 和时间暴露给该第三方；离线环境下该头像直接加载失败。这与 README 里「本地运行、数据在自己目录」的描述不一致。
3. **可用性**：该 URL 带 `sign` 参数，属于临时签名链接，随时可能失效。

**建议的最小修复**（本轮未执行，需要你决定）：把 `aiAvatar` 改成仓库内已有的本地资源，例如 `import aiAvatar from '@/assets/image.png'`（与 `ChatMessage.vue` 用同一张图），或者换成你确认过授权的新图。这是一行改动，不涉及 Agent 逻辑。

在你确认或替换之前，本仓库**不应**作为「素材授权无冲突」的发布候选。

## 4. 字体

字体的许可证与 OFL 全文见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 第 4 节。字体来自 npm 包 `vfonts`（MIT），字体本身是 SIL OFL 1.1，不属于 BearCoding 自研素材。

## 5. 截图

`evidence/` 下有 59 张 PNG 验收截图（约 14 MB）：

| 目录 | 数量 |
|---|---|
| `evidence/bearcoding-final-real-model-20261114/screenshots/` | 47 |
| `evidence/bearcoding-final-final-regression-20261114/screenshots/` | 9 |
| `evidence/bearcoding-final-manual-readiness-20261114/`（散放） | 3 |

**本轮没有完成视觉检查**：当前会话使用的模型不支持图像输入，无法打开这些 PNG。因此按任务约定，**未经视觉检查的截图一律排除在发布候选之外**（`.gitignore` 已排除 `evidence/` 下的图片）。

这些截图里很可能出现 Windows 用户目录（如 `C:\Users\<用户名>\AppData\Local\Temp\...`）、测试会话正文和本机路径。若你希望公开发布其中一部分，请先自己逐张看过，再单独放开对应的 `.gitignore` 规则。
