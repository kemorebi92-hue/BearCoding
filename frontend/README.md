# 前端

BearCoding 的页面。它负责聊天、展示工具过程、确认审批、配置模型和接入外部工具。Agent 循环在后端。产品说明见 [根 README](../README.md)。

## 路由

| 路由 | 作用 |
|---|---|
| `/chat` | 对话、计划、工具活动、工作区文件和目录绑定 |
| `/models` | 新增、切换和删除模型配置。列表不回显完整 Key |
| `/mcp` | 外部工具页。当前路由表把它指到聊天页组件 |
| `/login`、`/register` | 重定向到 `/chat`，没有登录表单 |

侧栏按工作区分组。默认工作区是上传后的副本；绑定本机目录后，Agent 读写那个目录。

## 技术栈

Vue 3.2，Vue CLI 5，Vue Router 4.6，Naive UI 2.43，axios，marked。仓库没有 `engines` 字段，Node 版本只要能跑 Vue CLI 5。

## 命令

```powershell
npm ci
npm run serve
npm run build
```

`npm run serve` 使用 5173，并把 `/ai`、`/api`、`/user` 代理到 `http://localhost:8082`。代理关闭了压缩，避免 SSE 被缓冲成一整段。

`npm run build` 产出 `dist/`。要打进后端 jar 时，把 `dist` 的内容复制到 `backend/src/main/resources/static`。不要提交 `dist/` 或 `node_modules/`。

另外有 `npm run test:group`（`src/utils` 下的 node 测试）和 `npm run test:e2e`（Playwright）。本文不把这些检查写成已经全部通过。`e2e/workspace-groups.spec.js` 已经过期（对应的页面流程改过），保留为 legacy，不作为本版的有效测试入口。

## 和后端的配合

聊天走 `POST /ai/stream`（SSE）。模型、会话、工作区和 MCP 管理走各自的 HTTP API。身份由后端的本机模式注入，前端不保存 JWT。

审批卡片展示工具名、参数和风险说明。用户记忆的保存和删除也会走到这张卡片，不批准则后端不写库。
