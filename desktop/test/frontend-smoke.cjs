"use strict";

const path = require("node:path");
const fs = require("node:fs");

const playwrightPath = path.resolve(__dirname, "..", "..", "frontend", "node_modules", "playwright");
const { chromium } = require(playwrightPath);

async function main() {
    const port = Number(process.argv[2]);
    const token = process.argv[3];
    const outputDir = process.argv[4] || path.resolve(__dirname, "..", "test-user-data");
    if (!Number.isInteger(port) || port <= 0 || !token) {
        throw new Error("用法：node test/frontend-smoke.cjs <port> <token> [output-dir]");
    }

    const browser = await chromium.launch({
        channel: "msedge",
        headless: true
    });
    try {
        const context = await browser.newContext({
            extraHTTPHeaders: {
                "X-BearCoding-Token": token
            }
        });
        const page = await context.newPage();
        const consoleErrors = [];
        const pageErrors = [];
        page.on("console", (message) => {
            if (message.type() === "error") consoleErrors.push(message.text());
        });
        page.on("pageerror", (error) => pageErrors.push(error.message));

        const response = await page.goto(`http://127.0.0.1:${port}/chat`, {
            waitUntil: "networkidle",
            timeout: 30000
        });
        if (!response || response.status() !== 200) {
            throw new Error(`页面加载失败：HTTP ${response ? response.status() : "unknown"}`);
        }

        let promptFound = false;
        try {
            await page.waitForFunction(
                () => document.body.innerText.includes("请先配置模型"),
                { timeout: 15000 }
            );
            promptFound = true;
        } catch {
            promptFound = false;
        }

        const title = await page.title();
        const bodyText = (await page.locator("body").innerText()).slice(0, 2000);
        const apiStatus = await page.evaluate(() =>
            fetch("/ai/models", { cache: "no-store" }).then((r) => r.status)
        );
        const screenshot = path.join(outputDir, "frontend-chat.png");
        fs.mkdirSync(outputDir, { recursive: true });
        await page.screenshot({ path: screenshot, fullPage: true });

        const result = {
            title,
            url: page.url(),
            status: response.status(),
            promptFound,
            apiStatus,
            consoleErrors,
            pageErrors,
            screenshot,
            bodyText
        };
        console.log(JSON.stringify(result, null, 2));
        if (!promptFound) process.exitCode = 2;
        if (apiStatus !== 200) process.exitCode = 3;
    } finally {
        await browser.close();
    }
}

main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
});
