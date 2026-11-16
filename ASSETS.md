# 图片、图标与字体资源说明

本文件记录 BearCoding 仓库里实际使用的图片资源、它们被谁引用、格式与体积，以及**授权状态的确认结论**。

> 结论先行：**图片资源中只有本轮新增的 `frontend/src/assets/avatar-bear.svg` 能确认来源与授权**（本仓库自绘、随 MIT 授权）。其余位图（`image.png`、`ims3.jpg`、`ims.webp`、`favicon.ico`、`desktop/build/icon.png`）仍然**无法从仓库内证据确认**是原创或已获授权，继续标为「待用户确认」。本文件不把文件名当作原创证据。

---

## 1. 实际被引用的资源

| 文件 | 体积 | 实际格式 | 尺寸 | 被谁引用 | 用途 | 授权状态 |
|---|---|---|---|---|---|---|
| `frontend/src/assets/avatar-bear.svg` | 见文件 | SVG（纯手写，无外部引用） | 96×96 viewBox | `frontend/src/views/ChatView.vue`（`aiAvatar`） | 聊天页顶部头像 | **已确认**：v3.0.0 为替换第三方 URL 专门绘制，无第三方素材，随根目录 MIT 授权 |
| `frontend/src/assets/image.png` | 343,779 B | PNG | 690×680 | `frontend/src/components/ChatMessage.vue:73`（`assistantAvatar`） | 消息列表里的助手头像 | 待用户确认 |
| `frontend/src/assets/ims3.jpg` | 96,131 B | JPEG (JFIF) | 未解析 | `frontend/src/components/ChatMessage.vue:74`（`userFallback`） | 用户消息头像的回退图 | 待用户确认 |
| `frontend/src/assets/ims.webp` | 64,044 B | WebP | 未解析 | `frontend/src/App.vue:57`（`background-image: url('assets/ims.webp')`） | 页面背景 | 待用户确认 |
| `frontend/public/favicon.ico` | 4,286 B | ICO | — | `frontend/public/index.html`（`<link rel="icon">`） | 浏览器标签图标 | 待用户确认 |
| `desktop/build/icon.png` | 1,703,584 B | PNG | 1254×1254 | `desktop/package.json`（`build.icon`、`win.icon`）、`paths.cjs` | 窗口图标与 EXE 图标 | 待用户确认 |

构建后进入 `backend/src/main/resources/static/` 的是 `avatar-bear.*.svg`、`image.*.png`、`ims.*.webp`、`ims3.*.jpg` 和 `favicon.ico`，与上表一致（已从成品 JAR / EXE 内核对）。


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

### 3.2 聊天页头像的第三方图片 URL（**已在本轮修复**）

修复前 `frontend/src/views/ChatView.vue:514` 是：

```js
const aiAvatar = 'https://kkimgs.yisou.com/ims?kt=url&at=smstruct&key=aHR0cHM6...&sign=...&tv=400_400'
```

该 URL 的 `key` 参数是 Base64，解码后指向 `https://c-ssl.duitang.com/uploads/item/202003/05/20200305172626_gbolv.thumb.1000_0.jpeg`，即**堆糖（duitang.com）上的一张第三方图片**，通过第三方图片代理（`kkimgs.yisou.com`）加载。它带来三个独立问题：

1. **版权**：这张图不属于 BearCoding 作者，也没有可见的授权声明。
2. **隐私与网络行为**：应用启动后聊天页会向 `kkimgs.yisou.com` 发起请求，把使用者的 IP、UA 和时间暴露给该第三方；离线环境下该头像直接加载失败。
3. **可用性**：该 URL 带 `sign` 参数，属于临时签名链接，随时可能失效。

**本轮的处理**：新增 `frontend/src/assets/avatar-bear.svg`（纯手写 SVG，无任何外部引用或位图嵌入），`ChatView.vue` 改为 `import localAvatar from '@/assets/avatar-bear.svg'`，并把 `aiAvatar` 指向它。该文件由本项目为 v3.0.0 专门绘制，随根目录 MIT 授权，因此这一项**可以确认来源与授权**。

已核实：构建产物 `frontend/dist` 与后端 JAR 内都不再包含 `kkimgs` / `yisou` / `duitang` 字符串（对 `static/js/*.js` 做过全文检索，0 命中），成品 EXE 的 JAR 里存在 `BOOT-INF/classes/static/img/avatar-bear.*.svg`。

**仍未处理**：消息气泡里的 `image.png` / `ims3.jpg`、页面背景 `ims.webp`、`favicon.ico`、EXE 图标 `desktop/build/icon.png` 依旧是来源未知的位图，见 3.1 节。


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
