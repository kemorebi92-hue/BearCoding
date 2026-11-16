"use strict";

/**
 * 受控测试模型服务（acceptance 用，不是产品代码）。
 *
 * 目的：在没有真实模型 Key 的情况下，让「读文件 → 改 Java 文件 → 自动编译校验 →
 * 展示结果」这条核心链路可以端到端跑通并留下可复核的日志。
 *
 * 它只实现 OpenAI 兼容协议里 BearCoding 实际用到的那一小块：
 *   POST /v1/chat/completions  (stream=true, SSE)
 * 并按「已出现的工具调用」推进一个固定脚本：
 *   1. readFile  -> 读 src/main/java/Demo.java
 *   2. editFile  -> 把 Hello, World 改成 Hello, BearCoding v3.0.0
 *   3. 纯文本总结 -> 结束任务
 *
 * 它不会联网、不读用户数据、不写除 --log 之外的任何文件。
 *
 * 用法：
 *   node mock-model-server.cjs --port 18099 --log <path>\requests.log
 */

const http = require("node:http");
const fs = require("node:fs");

const args = process.argv.slice(2);
function argOf(name, fallback) {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}

const PORT = Number(argOf("--port", "18099"));
const LOG = argOf("--log", "");

function log(line) {
    const text = `[${new Date().toISOString()}] ${line}\n`;
    if (LOG) {
        try {
            fs.appendFileSync(LOG, text);
        } catch {
            // 日志失败不影响测试
        }
    }
    process.stdout.write(text);
}

const DEMO_PATH = "src/main/java/Demo.java";
const OLD_TEXT = 'System.out.println("Hello, World");';
const NEW_TEXT = 'System.out.println("Hello, BearCoding v3.0.0");';

/** 从请求里恢复「已经发生过哪些工具调用」，用来决定下一步。 */
function calledTools(messages) {
    const names = [];
    for (const m of messages || []) {
        if (m && m.role === "assistant" && Array.isArray(m.tool_calls)) {
            for (const tc of m.tool_calls) {
                const name = tc?.function?.name;
                if (name) names.push(name);
            }
        }
    }
    return names;
}

function sseChunk(res, payload) {
    res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function baseChunk(extra) {
    return {
        id: "chatcmpl-bearcoding-acceptance",
        object: "chat.completion.chunk",
        created: Math.floor(Date.now() / 1000),
        model: "bearcoding-acceptance-mock",
        ...extra
    };
}

function streamText(res, text) {
    sseChunk(res, baseChunk({ choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] }));
    // 按小块推送，模拟真实流式输出
    const step = 24;
    for (let i = 0; i < text.length; i += step) {
        sseChunk(res, baseChunk({ choices: [{ index: 0, delta: { content: text.slice(i, i + step) }, finish_reason: null }] }));
    }
    sseChunk(res, baseChunk({ choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }));
    res.write("data: [DONE]\n\n");
    res.end();
}

function streamToolCall(res, name, callArgs) {
    sseChunk(res, baseChunk({
        choices: [{
            index: 0,
            delta: {
                role: "assistant",
                tool_calls: [{
                    index: 0,
                    id: `call_${Date.now()}`,
                    type: "function",
                    function: { name, arguments: JSON.stringify(callArgs) }
                }]
            },
            finish_reason: null
        }]
    }));
    sseChunk(res, baseChunk({ choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] }));
    res.write("data: [DONE]\n\n");
    res.end();
}

/** 非流式响应：BearCoding 的主循环有时用 stream=false 调用，必须同时支持。 */
function jsonCompletion(res, decision) {
    const message = decision.kind === "tool"
        ? {
            role: "assistant",
            content: null,
            tool_calls: [{
                id: `call_${Date.now()}`,
                type: "function",
                function: { name: decision.name, arguments: JSON.stringify(decision.args) }
            }]
        }
        : { role: "assistant", content: decision.text };

    const payload = {
        id: "chatcmpl-bearcoding-acceptance",
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: "bearcoding-acceptance-mock",
        choices: [{
            index: 0,
            message,
            finish_reason: decision.kind === "tool" ? "tool_calls" : "stop"
        }],
        usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 }
    };
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify(payload));
}

function decide(messages) {
    const names = calledTools(messages);
    if (!names.includes("readFile")) {
        return {
            kind: "tool",
            name: "readFile",
            args: { path: DEMO_PATH },
            note: "step1 readFile"
        };
    }
    if (!names.includes("editFile")) {
        return {
            kind: "tool",
            name: "editFile",
            args: { path: DEMO_PATH, oldStr: OLD_TEXT, newStr: NEW_TEXT },
            note: "step2 editFile"
        };
    }
    return {
        kind: "text",
        note: "step3 final",
        text:
            "已完成。\n\n" +
            `1. 用 readFile 读取了 ${DEMO_PATH}。\n` +
            "2. 用 editFile 把输出语句从 Hello, World 改成 Hello, BearCoding v3.0.0。\n" +
            "3. 系统随后自动对工作区跑了 javac 编译校验，结果见工具/校验记录。\n\n" +
            "本次改动的最终代码：\n\n" +
            "```java\npublic class Demo {\n    public static void main(String[] args) {\n" +
            "        " + NEW_TEXT + "\n    }\n}\n```\n"
    };
}

const server = http.createServer((req, res) => {
    if (req.method === "GET" && req.url === "/health") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
        return;
    }

    if (req.method !== "POST" || !req.url.endsWith("/chat/completions")) {
        res.writeHead(404, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: { message: "not found" } }));
        return;
    }

    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
        let body = {};
        try {
            body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        } catch {
            body = {};
        }

        const messages = Array.isArray(body.messages) ? body.messages : [];
        const toolNames = calledTools(messages);
        const decision = decide(messages);
        const toolCount = Array.isArray(body.tools) ? body.tools.length : 0;
        const lastUser = [...messages].reverse().find((m) => m && m.role === "user");
        log(
            `request model=${body.model || "?"} stream=${body.stream === true} tools=${toolCount} ` +
            `assistantToolCalls=[${toolNames.join(",")}] -> ${decision.note}`
        );
        if (lastUser && toolNames.length === 0) {
            log(`  first user message: ${String(lastUser.content).slice(0, 200).replace(/\s+/g, " ")}`);
        }

        if (body.stream !== true) {
            jsonCompletion(res, decision);
            return;
        }

        res.writeHead(200, {
            "content-type": "text/event-stream; charset=utf-8",
            "cache-control": "no-cache",
            connection: "keep-alive"
        });

        if (decision.kind === "tool") {
            streamToolCall(res, decision.name, decision.args);
        } else {
            streamText(res, decision.text);
        }
    });
});

server.listen(PORT, "127.0.0.1", () => {
    log(`mock model server listening on http://127.0.0.1:${PORT}/v1 (scripted acceptance model)`);
});
