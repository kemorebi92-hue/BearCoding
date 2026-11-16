"use strict";

/**
 * 便携 EXE 窗口验收用的 CDP 客户端（配合 packaged-window-verify.ps1）。
 *
 * 与 test/electron-smoke.cjs 的区别：这个脚本只连「已经跑起来的成品 EXE」，
 * 并且多做了两件事——截图，以及导航白名单的实际尝试。
 *
 * 用法：node test/window-cdp.cjs <cdp-port> <action> [outFile]
 *   action = inspect | screenshot | navigate-test | close
 */

const http = require("node:http");
const fs = require("node:fs");

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

async function findPage(cdpPort) {
    const targets = await getJson(`http://127.0.0.1:${cdpPort}/json/list`);
    const page = targets.find((target) =>
        target.type === "page" &&
        typeof target.url === "string" &&
        target.url.startsWith("http://127.0.0.1:")
    );
    if (!page) {
        throw new Error(`未找到 BearCoding 页面。目标：${JSON.stringify(targets)}`);
    }
    return page;
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
            }, 15000);
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

    return { socket, send, evaluate };
}

async function main() {
    const cdpPort = Number(process.argv[2]);
    const action = process.argv[3] || "inspect";
    const outFile = process.argv[4] || "";
    if (!Number.isInteger(cdpPort) || cdpPort <= 0) {
        throw new Error("用法：node test/window-cdp.cjs <cdp-port> [inspect|screenshot|navigate-test|close] [outFile]");
    }

    const page = await findPage(cdpPort);
    const { socket, send, evaluate } = await connectCdp(page.webSocketDebuggerUrl);
    const out = {};
    try {
        if (action === "inspect") {
            out.title = await evaluate("document.title");
            out.href = await evaluate("location.href");
            out.userAgent = await evaluate("navigator.userAgent");
            out.apiStatus = await evaluate("fetch('/ai/models', {cache: 'no-store'}).then(r => r.status)");
            out.apiBody = await evaluate("fetch('/ai/models', {cache: 'no-store'}).then(r => r.text())");
            out.statusBody = await evaluate("fetch('/ai/models/status', {cache: 'no-store'}).then(r => r.text())");
            out.bodyText = await evaluate("document.body.innerText.slice(0, 1500)");
            out.hasModelsNav = await evaluate(
                "Array.from(document.querySelectorAll('a,button')).some(el => /模型|设置|models/i.test(el.innerText || ''))"
            );
        } else if (action === "screenshot") {
            const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
            if (outFile) {
                fs.writeFileSync(outFile, Buffer.from(shot.data, "base64"));
                out.screenshot = outFile;
                out.bytes = fs.statSync(outFile).size;
            }
        } else if (action === "navigate-test") {
            out.before = await evaluate("location.href");
            // 试图跳到外部站点；主进程的 will-navigate 应该拦下来
            await evaluate("setTimeout(() => { location.href = 'https://example.com/'; }, 0); true");
            await new Promise((resolve) => setTimeout(resolve, 2500));
            out.after = await evaluate("location.href");
            out.blocked = out.before === out.after;
            // 新窗口也应该被 setWindowOpenHandler 拒绝
            out.windowOpenResult = await evaluate("String(window.open('https://example.com/'))");
        } else if (action === "close") {
            try {
                await evaluate("window.close()");
            } catch {
                // window.close() 可能先关闭页面，导致 CDP 连接断开
            }
            out.closed = true;
        } else {
            throw new Error(`未知 action：${action}`);
        }
        console.log(JSON.stringify(out, null, 2));
    } finally {
        socket.close();
    }
}

main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
});
