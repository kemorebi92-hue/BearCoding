# 验收证据（公开部分）

本目录保存 BearCoding Final 阶段的验收报告。**只保留人工撰写的 Markdown 报告**。

## 关于数字

每份报告都是**带日期的快照**，里面的测试数字反映的是那一天的状态，不是当前基线。当前基线写在根 [README](../README.md) 的「测试与证据」一节（Phase A 之后：1616 run / 0 fail / 0 error / 9 skipped）。看到 `1553`、`1591` 这类数字时，请对照报告开头的日期理解。

## 未随仓库发布的内容

以下属于本机运行产物，已由 `.gitignore` 排除，只在本地保留：

- `screenshots/` 下的界面截图（含测试会话正文与本机临时目录路径）；
- `logs/` 下的构建、测试、冒烟日志；
- 冒烟脚本与它们的 JSON 结果（含本机用户目录路径）；
- 运行时数据库与测试数据目录。

因此报告正文里以行内代码形式提到的截图、日志、JSON 文件名，在本仓库中并不存在。要复现，请按根 README 与 [桌面打包流程](../docs/DESKTOP-PACKAGING.md) 自己跑一遍。

## 目录

| 报告 | 内容 |
|---|---|
| `bearcoding-final-s1-s2-s3-20261114.md` | S1/S2/S3 修复与全量回归 |
| `bearcoding-final-final-regression-20261114.md` | 最终回归（工作区绑定、分组、上传越界） |
| `bearcoding-final-real-model-browser-e2e-acceptance-20261114.md` | 真实模型浏览器端到端验收 |
| `bearcoding-final-f1-f2-fix-and-context-acceptance-20261114.md` | F1/F2 修复与上下文管理验收 |
| `bearcoding-final-last-p2-fix-and-manual-readiness-20261114.md` | 最后一个 P2 修复与人工验收就绪度 |
| `phase-b-desktop-migration-20261115.md` | Electron 源码迁移到 `desktop/` 与便携包构建 |
