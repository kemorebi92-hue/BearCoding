"use strict";

const { app, BrowserWindow, dialog } = require("electron");
const { spawn, spawnSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const net = require("node:net");
const path = require("node:path");
const desktopPaths = require("./paths.cjs");

// ============================
// 1. 保存应用运行状态
// ============================

let win = null;
let backend = null;
let backendExited = false;
let backendReady = false;
let quitting = false;

let DATA_ROOT = null;
let JAVA = null;
let JAR = null;
let ICON = null;

// 每次启动都生成新的随机鉴权令牌；不写入磁盘、日志或页面。
const LOCAL_TOKEN = crypto.randomBytes(32).toString("hex");
const TOKEN_HEADER = "X-BearCoding-Token";
const TOKEN_ENV = "BEARCODING_LOCAL_TOKEN";

// ============================
// 2. 自动选择空闲端口
// ============================

function findFreePort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();

        server.once("error", reject);

        server.listen(0, "127.0.0.1", () => {
            const port = server.address().port;

            server.close((error) => {
                if (error) reject(error);
                else resolve(port);
            });
        });
    });
}

// ============================
// 3. 启动 Java 后端
// ============================

function startBackend(port) {
    const logDir = path.join(DATA_ROOT, "logs");
    fs.mkdirSync(logDir, { recursive: true });

    const logFile = path.join(logDir, "backend.log");
    const logFd = fs.openSync(logFile, "a");

    try {
        backend = spawn(JAVA, [
            "-Dfile.encoding=UTF-8",
            "-jar",
            JAR,
            `--server.port=${port}`,
            "--server.address=127.0.0.1",
            "--app.auth.mode=desktop",
            "--app.data.mode=desktop"
        ], {
            cwd: DATA_ROOT,
            env: {
                ...process.env,
                JINGJING_DATA_DIR: DATA_ROOT,
                [TOKEN_ENV]: LOCAL_TOKEN
            },
            windowsHide: true,
            stdio: ["ignore", logFd, logFd]
        });
    } finally {
        fs.closeSync(logFd);
    }

    backend.on("error", (error) => {
        backendExited = true;
        console.error("Java 启动失败：", error.message);
    });

    backend.on("exit", (code) => {
        backendExited = true;

        if (backendReady && !quitting) {
            dialog.showErrorBox(
                "BearCoding 后端已退出",
                `Java 进程退出，退出码：${code}\n请检查：${logFile}`
            );

            app.quit();
        }
    });
}

// ============================
// 4. 等待 Java 后端就绪
// ============================

async function waitForBackend(origin) {
    const deadline = Date.now() + 120000;

    while (Date.now() < deadline) {
        if (backendExited) {
            throw new Error("Java 后端提前退出，请检查 backend.log");
        }

        try {
            const response = await fetch(`${origin}/ai/models`, {
                headers: {
                    [TOKEN_HEADER]: LOCAL_TOKEN
                },
                signal: AbortSignal.timeout(3000)
            });

            if (response.ok) {
                return;
            }
        } catch {
            // Java 可能还在启动，继续检查
        }

        await new Promise(resolve => setTimeout(resolve, 1000));
    }

    throw new Error("等待 Java 后端启动超时");
}

// ============================
// 5. 创建 Electron 窗口
// ============================

function createWindow(origin) {
    win = new BrowserWindow({
        width: 1200,
        height: 800,

        // BearCoding 小熊图标
        icon: ICON,

        autoHideMenuBar: true,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            sandbox: true
        }
    });

    const isAppOrigin = (url) => {
        try {
            return new URL(url).origin === origin;
        } catch {
            return false;
        }
    };

    // 阻止跳转到其他网站
    const preventExternal = (event, details) => {
        const url = typeof details === "string"
            ? details
            : (details?.url ?? event.url);

        if (!isAppOrigin(url)) {
            event.preventDefault();
        }
    };

    win.webContents.on("will-navigate", preventExternal);
    win.webContents.on("will-redirect", preventExternal);

    win.webContents.setWindowOpenHandler(() => ({
        action: "deny"
    }));

    // 只对本应用后端地址注入鉴权令牌
    win.webContents.session.webRequest.onBeforeSendHeaders(
        { urls: [`${origin}/*`] },
        (details, callback) => {
            if (details.webContentsId === win.webContents.id) {
                details.requestHeaders[TOKEN_HEADER] = LOCAL_TOKEN;
            }

            callback({
                requestHeaders: details.requestHeaders
            });
        }
    );

    win.on("closed", () => {
        win = null;
    });

    return win.loadURL(`${origin}/chat`);
}

// ============================
// 6. 启动完整桌面应用
// ============================

async function startApplication() {
    try {
        const resolved = desktopPaths.resolveDesktopPaths({
            isPackaged: app.isPackaged,
            resourcesPath: process.resourcesPath,
            appDir: __dirname,
            projectRoot: path.resolve(__dirname, ".."),
            env: process.env
        });

        DATA_ROOT = resolved.dataRoot;
        JAVA = resolved.java;
        JAR = resolved.jar;
        ICON = resolved.icon;

        fs.mkdirSync(DATA_ROOT, { recursive: true });

        const port = await findFreePort();
        const origin = `http://127.0.0.1:${port}`;

        console.log("BearCoding 后端端口：", port);
        console.log("数据目录：", DATA_ROOT);

        startBackend(port);

        console.log("正在启动 Java 后端...");

        await waitForBackend(origin);

        backendReady = true;

        console.log("后端已就绪，正在打开窗口...");

        await createWindow(origin);
    } catch (error) {
        dialog.showErrorBox(
            "BearCoding 启动失败",
            `${error.message}\n\n数据目录：${DATA_ROOT || "未确定"}`
        );

        app.quit();
    }
}

// ============================
// 7. 单实例保护
// ============================

const gotLock = app.requestSingleInstanceLock();

if (!gotLock) {
    app.quit();
} else {
    app.on("second-instance", () => {
        if (win) {
            if (win.isMinimized()) win.restore();
            win.focus();
        }
    });

    app.whenReady().then(startApplication);
}

// ============================
// 8. 退出时清理自己的 Java 后端
// ============================

app.on("before-quit", () => {
    quitting = true;

    if (backend && backend.pid && !backendExited) {
        if (process.platform === "win32") {
            spawnSync("taskkill", [
                "/PID",
                String(backend.pid),
                "/T",
                "/F"
            ], {
                windowsHide: true,
                timeout: 10000,
                stdio: "ignore"
            });
        } else {
            backend.kill("SIGTERM");
        }
    }
});

app.on("window-all-closed", () => {
    app.quit();
});
