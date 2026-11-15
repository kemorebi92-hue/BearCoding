# 前端启动（release/workspace-ui）

## 开发

```bash
npm.cmd run serve
```

默认端口 **5173**，通过 `vue.config.js` 把 `/ai`、`/api`、`/user` 代理到 **8082**。

要连别的后端，启动前设置 `VUE_APP_PROXY_TARGET`（例如 `http://127.0.0.1:18083`）。不设置时目标仍是 `http://localhost:8082`。这个变量只影响开发服务器，不改变构建产物。**不要把密钥写进仓库**。

## 指向本机后端

浏览器打开 `http://localhost:5173`，用与后端同一套账号登录。

## 检查

```bash
npm.cmd run test:group
npm.cmd run build
```

E2E（需后端已起）：

```bash
set E2E_BASE=http://localhost:5173
set E2E_API=http://localhost:8082
set E2E_CHANNEL=msedge
npm.cmd run test:e2e
```

PowerShell 请用 `$env:E2E_BASE=...` 形式。
