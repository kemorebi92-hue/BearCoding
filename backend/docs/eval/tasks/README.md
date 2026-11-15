# 评测任务 T01–T11

本目录是尺子，不改产品行为。每个任务有一份说明、一份 `Txx.expect.json`，起始工作区在 `docs/eval/fixtures/Txx/`。

子代理的负结果不在这里重跑。对应的 subagent eval 报告没有随本仓库发布。

运行器复用 `scripts/eval/env.sh` 的隔离：`WORKSPACE_ROOT` 指到仓库外，`EVAL_PORT` 决定端口，会话用 `/ai/workspace/bind` 绑到任务目录。应用由 `scripts/eval/run_all.sh` 那种方式经 `run.sh` 拉起。基线运行 `SUBAGENT_EVAL_FORCE_MODE=off`（代码里是 `EVAL_FORCE_DISPATCH_MSG` / `EVAL_FORCE_NO_SPAWN_MSG` 两个文案，由这一个配置取值 `off`、`force_dispatch`、`force_no_spawn` 决定插不插）。用了非 off 必须写进运行记录。

> 注意：上面提到的 `scripts/eval/` 运行脚本没有随本仓库发布，这里保留的只是任务语料（prompt、fixture、expect.json）和任务语义说明。要重跑需要自己按这段描述搭隔离环境。

结果写到 `docs/eval/results/<日期>-<标签>/`（`report.md` 与 `runs.json`）。本阶段的干跑是假数据，不放进该目录冒充真实结果。

| 任务 | 目的 | 主要检验 |
|---|---|---|
| T01 | 单文件把 Helo 改成 Hello | 精确编辑 |
| T02 | 修好才能 javac 通过，含 String[] 和分号两个陷阱 | 编译失败后的自愈 |
| T03 | a/A.java 和 b/B.java 都改，路径已给出 | 给定路径的多文件编辑 |
| T04 | 长历史里有「不要改 KEEP.txt」，只改 CHANGE.txt | 压缩后约束还在 |
| T05 | 三步计划，最后一步容易被跳过 | 计划未完成却停了。守卫：不能只听模型说做完了 |
| T06 | 一句问答，不许改文件、不许建计划 | 不该建计划。守卫：完成闸不能强迫短任务建计划 |
| T07 | 两段 return 1，只改 UNIQUE_B | 相似片段编辑 |
| T08 | 先 grep/glob 找到 TOKEN_42 再改 | 先定位再改 |
| T09 | 只能改 only.txt | 其他文件哈希不变 |
| T10 | 提示里的路径不存在，真正文件在 lib/App.java | 路径给错后自己找到 |
| T11 | 读六份长文件，压缩发生在中途，写出两条口令 | 摘要和 DIGEST 都能不能把口令留住 |

期望 JSON 还允许 `toolNotCalled`、`toolCalledAtLeast`、`compactionHappened`。`compactionHappened` 标了 `precondition: true` 时，没发生压缩判为无效，不是通过。只有 T06 声明了 `forbiddenTools`。计划状态用代码里的 `done`。
