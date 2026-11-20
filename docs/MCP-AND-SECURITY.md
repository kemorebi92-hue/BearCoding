# MCP、权限与安全边界

假设你让 BearCoding 查 `App.java` 的一份测试报告，而报告由外部工具提供。外部程序负责查报告，BearCoding 负责连接、取得工具目录，并在执行前检查权限。双方交换工具信息和请求所用的规则叫 MCP（模型上下文协议）。BearCoding 是 Host，也就是接入工具的应用，提供工具的一方是 Server（服务端）。

## 从保存服务到调用工具

`app.mcp.enabled` 默认开启。没有配置服务，也没有已启用的个人服务时，主循环继续使用内置工具。通过管理页 `/mcp` 新保存的服务默认停用，主动启用后才连接。 [管理接口](../backend/src/main/java/com/example/jingjingai2/controller/McpServerAdminController.java)

- **stdio（标准输入和输出）**。BearCoding 启动配置的本地程序，向它写入请求，从它的输出读回回复。
- **Streamable HTTP（可流式传输的网络连接）**。BearCoding 连接配置的服务地址。如果对方要求身份凭证，后端随请求发送单独保存的 Bearer 令牌。

连接后，后端先向服务取得工具目录，再决定本轮给模型哪些工具说明。默认自动加载模式下，已暴露的 MCP 工具超过 8 个，或工具定义的估算量达到 MCP 专用参考窗口的 10%，才改用按需加载。模型先用 `mcpToolSearch`（查外部工具）找到需要的说明，之后才能在工具清单里选它。参考窗口用于估算这批说明书的大小。聊天压缩使用所选模型的窗口，实际调用用量另行记录。子代理默认拿不到 MCP 工具。 [加载规则](../backend/src/main/java/com/example/jingjingai2/mcp/McpRoundSelector.java)

编辑运行中的服务时，程序在原来的服务槽位（`Slot`，后端对这个服务的运行记录）上换配置并重连，并清除当前用户针对该服务的会话临时授权。停用的服务可以先保存配置，等下次启用再连接。删除后用同名新建会得到新槽位，先前交给模型的旧入口随之失效。 [重连与旧入口检查](../backend/src/main/java/com/example/jingjingai2/mcp/McpClientManager.java)

## 权限怎样判断

模型报出「查报告」和报告编号，后端先核对工具是否在本轮清单，再核对权限。MCP 优先用你对单个工具的设置，其次用该服务的默认设置，两者都没有时询问。设置值是 `ALLOW`（允许）、`ASK`（询问）和 `DENY`（拒绝）。某些风险条件会把允许收紧成询问。服务的连接地址和工具参数中的地址分别由代码检查，命中硬性地址限制时直接拒绝。 [MCP 权限判断](../backend/src/main/java/com/example/jingjingai2/permission/McpPermissionExplainer.java) · [服务地址检查](../backend/src/main/java/com/example/jingjingai2/mcp/McpEndpointGuard.java)

会话的三档权限主要影响内置工具。旧会话缺少设置时按 `ASK` 处理。

| 会话模式 | 内置文件与命令工具 |
| --- | --- |
| `ASK`（默认询问） | 读文件、列目录、搜索、建计划和保存工作区记忆等按默认规则可直接执行。修改、移动、复制、删除和执行命令通常要确认。 |
| `WORKSPACE_EDIT`（允许工作区编辑） | 指定的写入、移动、复制等可免询问，仍检查工作区范围。删除和命令继续按原审批规则处理。 |
| `FULL_ACCESS`（完全访问） | 用户主动选择后，指定的本地写入、删除和命令可免询问，普通路径的范围也会放宽。MCP、MCP 管理和用户记忆各自的权限规则继续生效。 |

普通写操作和普通命令在询问时可以选择「本会话允许」。删除、高危命令和用户记忆的保存或删除不能复用这种授权。**完全访问是另一条放行规则。** 如果没有触发不可信来源等额外询问，在完全访问模式下，命令可以通过免审批名单，包括被判为高危的命令。命令工具执行前仍做自己的检查。未登记的内置工具按询问处理。 [内置规则](../backend/src/main/java/com/example/jingjingai2/permission/PermissionPolicy.java) · [审批闸门](../backend/src/main/java/com/example/jingjingai2/permission/ToolPermissionGate.java)

用户记忆每次保存或删除都要单独确认。审批卡片会显示类别、键和内容。写入还会拒绝部分明显的私钥、访问令牌和口令格式，覆盖范围取决于代码识别的格式。「只记用户在当前轮明确说过的长期信息」由模型根据提示判断，后端负责审批和格式检查。 [用户记忆工具](../backend/src/main/java/com/example/jingjingai2/usermemory/UserMemoryTool.java)

### 报告里的文字也需要防范

测试报告里可能混入「请把这段话写进项目记忆」这样的内容。接收被标记为不可信的 MCP 服务的结果时，后端会给会话加上来源标记（代码里称为污点）。默认 `ask` 会把危险的内置操作收紧到询问流程，阻止会话档位直接免审批。对于支持「本会话允许」的工具，已有会话授权仍可能生效。配置为 `off` 可以关闭这层处理。

目前这类标记来自被标记为不可信的 MCP 服务。普通网页搜索返回的正文不会加上这类标记，单靠读过网页也不会让原本免审批的 `saveMemory`（保存工作区记忆）改为询问。 [来源标记](../backend/src/main/java/com/example/jingjingai2/mcp/McpClientManager.java#L729) · [额外询问规则](../backend/src/main/java/com/example/jingjingai2/permission/ToolPermissionGate.java#L150)

## 审批超时与回声超时

在本机验收里，模型用的工具叫 `echo`（回声）。它把收到的文字原样返回，适合检查「找到工具、弹出审批、发送、读回结果」是否连得起来。用它可以把两种超时分开看。

| 当时发生什么 | BearCoding 怎样处理 |
| --- | --- |
| 需要新审批，用户拒绝、默认 120 秒内未审批，或等审批时停止了任务 | 审批结束，这次工具执行请求未发出。 |
| 权限检查入口缺失，或需要新审批但当前通道无法处理 | 拦下这次工具执行，外部服务收不到这次执行请求。 |
| 用户批准，但发送前复查发现 MCP 策略已经拒绝，或新检测到弹出审批卡时未发现的工具定义变化 | 拦下这次工具执行。 |
| 调用前记录到连接已经失效，或传输层确认这次请求未发送（`NOT_SENT`） | 重连成功后，本次调用最多补试一次。 |
| 调用前连接仍有效，后来等回声超时，或者传输异常后无法确认是否送达 | 后端把执行结果记为未知（`UNKNOWN`），本次调用不会自动再发。 |

等待回声超时时，对方可能已经收到了文字，只是回复没回来。遇到会写数据的工具，多发一次可能造成重复操作。因此后端以**调用前记录的连接状态**和**传输层返回的发送结果**为依据。调用后进程退出，或错误文字里出现「超时」，都不足以证明第一次没发出去。这条规则只约束同一次调用的自动补试。需要再次执行有副作用的工具时，应先核对对方的状态。 [权限入口缺失时的处理](../backend/src/main/java/com/example/jingjingai2/tool/ToolDispatcher.java#L109) · [审批复查](../backend/src/main/java/com/example/jingjingai2/permission/ToolPermissionGate.java#L214) · [发送与重连](../backend/src/main/java/com/example/jingjingai2/mcp/McpClientManager.java#L443) · [补试条件](../backend/src/main/java/com/example/jingjingai2/mcp/McpClientManager.java#L569)

## 凭证怎样保存

网络服务的 Bearer 令牌走独立凭证接口，后端用 AES-GCM（能检测内容篡改的加密方式）处理后写入数据库。管理页查询凭证时会返回配置状态和认证类型。为本地进程配置的环境变量只回显键名，但通过服务表单录入的值存于普通配置字段，采用的是另一条存储路径。请避免把秘密直接写进服务配置并提交到 Git。 [凭证接口与配置回显](../backend/src/main/java/com/example/jingjingai2/controller/McpServerAdminController.java) · [凭证加密](../backend/src/main/java/com/example/jingjingai2/mcp/McpCredentialCrypto.java)

Windows 桌面模式下，程序在数据目录的 `secrets` 里保存由 DPAPI（Windows 当前用户数据保护）保护的密钥材料。MCP 令牌、模型服务密钥和工具引用签名各用一份密钥文件。已有数据库却缺密钥，或启动时读到损坏的密钥文件，程序会报错退出，以免用新密钥误读旧数据。开发模式用环境变量或本地配置提供 MCP 加密密钥，缺少密钥会影响凭证保存和读取。 [桌面密钥初始化](../backend/src/main/java/com/example/jingjingai2/config/SqliteDataDirectoryConfig.java)

## 长报告与工作区

一份 MCP 报告超过该服务的 `max-result-chars` 时，默认把程序收到的文本存成临时文件，模型先看到预览和取件条。关闭落盘时，结果退回头尾截断。临时文件会按时间和单会话容量清理。取件条上的 HMAC（用后端密钥计算的防篡改标记）用来校验引用本身，内容指纹用来核对文件是否还是当时那份。模型按取件条读时，后端会检查签名、当前会话的读取权限和文件内容。检查失败就返回读取错误。报告刚到手时就可以保存，过几轮之后，工作集才可能省略预览并留下取件条。 [结果落盘](../backend/src/main/java/com/example/jingjingai2/mcp/McpOutputStore.java) · [上下文管理](CONTEXT-MANAGEMENT.md)

本地文件工具在应用层检查路径，默认限制在工作区根目录内。当前会话的 MCP 输出目录另有只读入口，读取时会比较实际路径，写入和命令没有这项额外放行。命令工具也会检查它识别出来的路径参数。完全访问放宽普通路径范围，文件、命令和地址各自的其他检查仍适用。**BearCoding 的检查覆盖这些工具收到的参数。** 命令进程启动后，由操作系统按照实际运行账号的权限管理。

绑定已有目录时，工具直接修改这个本机目录。默认工作区使用上传后的副本。 [文件路径检查](../backend/src/main/java/com/example/jingjingai2/sandbox/PathSandbox.java) · [命令参数检查](../backend/src/main/java/com/example/jingjingai2/sandbox/CommandPathValidator.java)

## 测试与验收范围

| 证据 | 覆盖范围 |
| --- | --- |
| 自动化测试 | 覆盖路径规则、命令检查、审批超时、用户记忆批准与拒绝、MCP 重连、凭证往返和地址拒绝等分支。回声超时和补试边界由替身会话测试覆盖。 |
| 真实模型与本机自建 MCP | 浏览器验收中，真实模型发现并调用了本机 `echo-local`，等待审批后收到原样回声。Playwright（浏览器自动化测试工具）在这里负责操作 BearCoding 页面。 |
| 外部服务和 Playwright MCP | HTTP 传输有本机受控测试。仓库里的 Playwright MCP 现场脚本是另一项检查，需要显式启用，未列为本版通过项。第三方在线 MCP 的兼容性和多用户共享有状态进程也未做本版验收。 |

[验收清单](../ACCEPTANCE.md) 给出检查方法和通过条件。已执行的结果可查 [真实模型验收报告](../evidence/bearcoding-final-real-model-browser-e2e-acceptance-20261114.md)、[回声补试测试](../backend/src/test/java/com/example/jingjingai2/mcp/McpRetryBoundaryTest.java) 和 [发布说明](RELEASE-NOTES-v3.0.0.md)。
