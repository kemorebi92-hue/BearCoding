"use strict";

const http = require("node:http");

function getJson(url) {
    return new Promise((resolve, reject) => {
        const request = http.get(url, (response) => {
            let body = "";
            response.setEncoding("utf8");
            response.on("data", (chunk) => { body += chunk; });
            response.on("end", () => {
                if (response.statusCode !== 200) {
                    reject(new Error(`HTTP ${response.statusCode}: ${url}`));
                    return;
                }
                try {
                    resolve(JSON.parse(body));
                } catch (error) {
                    reject(new Error(`无法解析 JSON：${url}\n${error.message}`));
                }
            });
        });
        request.on("error", reject);
        request.setTimeout(5000, () => request.destroy(new Error(`请求超时：${url}`)));
    });
}

async function connectCdp(webSocketUrl) {
    const socket = new WebSocket(webSocketUrl);
    await new Promise((resolve, reject) => {
        socket.addEventListener("open", resolve, { once: true });
        socket.addEventListener("error", () => reject(new Error("CDP WebSocket 连接失败")), { once: true });
    });

    let nextId = 1;
    const pending = new Map();

    socket.addEventListener("message", (event) => {
        const message = JSON.parse(event.data);
        if (!message.id || !pending.has(message.id)) return;
        const { resolve, reject } = pending.get(message.id);
        pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
    });

    function send(method, params = {}) {
        const id = nextId++;
        socket.send(JSON.stringify({ id, method, params }));
        return new Promise((resolve, reject) => {
            pending.set(id, { resolve, reject });
            setTimeout(() => {
                if (pending.delete(id)) reject(new Error(`CDP 调用超时：${method}`));
            }, 10000);
        });
    }

    async function evaluate(expression) {
        const result = await send("Runtime.evaluate", {
            expression,
            awaitPromise: true,
            returnByValue: true
        });
        if (result.exceptionDetails) {
            throw new Error(`页面脚本异常：${result.exceptionDetails.text}`);
        }
        return result.result.value;
    }

    return { socket, evaluate };
}

async function main() {
    const cdpPort = Number(process.argv[2]);
    const action = process.argv[3] || "inspect";
    if (!Number.isInteger(cdpPort) || cdpPort <= 0) {
        throw new Error("用法：node test/electron-smoke.cjs <cdp-port> [inspect|close]");
    }

    const targets = await getJson(`http://127.0.0.1:${cdpPort}/json/list`);
    const page = targets.find((target) =>
        target.type === "page" &&
        typeof target.url === "string" &&
        target.url.startsWith("http://127.0.0.1:")
    );
    if (!page) {
        throw new Error(`未找到 BearCoding 页面。目标：${JSON.stringify(targets)}`);
    }

    const { socket, evaluate } = await connectCdp(page.webSocketDebuggerUrl);
    try {
        const result = {
            title: await evaluate("document.title"),
            href: await evaluate("location.href"),
            userAgent: await evaluate("navigator.userAgent"),
            apiStatus: await evaluate("fetch('/ai/models', {cache: 'no-store'}).then(r => r.status)"),
            apiBody: await evaluate("fetch('/ai/models', {cache: 'no-store'}).then(r => r.text())"),
            bodyText: await evaluate("document.body.innerText.slice(0, 1200)")
        };
        console.log(JSON.stringify(result, null, 2));

        if (action === "close") {
            try {
                await evaluate("window.close()");
            } catch {
                // window.close() 可能先关闭页面，导致 CDP 连接断开。
            }
        }
    } finally {
        socket.close();
    }
}

main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
});
