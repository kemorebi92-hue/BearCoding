"use strict";

const fs = require("node:fs");
const path = require("node:path");

const APP_NAME = "BearCoding";
const DEV_DATA_OVERRIDE = "BEARCODING_DEV_DATA_DIR";
const BACKEND_JAR_OVERRIDE = "BEARCODING_BACKEND_JAR";
const JAVA_OVERRIDE = "BEARCODING_JAVA";

function isFile(candidate) {
    try {
        return fs.statSync(candidate).isFile();
    } catch {
        return false;
    }
}

function requireFile(candidate, label) {
    if (!isFile(candidate)) {
        throw new Error(`${label}不存在：${candidate}`);
    }
    return candidate;
}

function requireAbsolute(candidate, label) {
    if (!path.isAbsolute(candidate)) {
        throw new Error(`${label}必须是绝对路径：${candidate}`);
    }
    return path.normalize(candidate);
}

/**
 * 正式版数据根固定为 %LOCALAPPDATA%\BearCoding。
 * 开发版默认使用 desktop/test-user-data；测试可用显式绝对路径隔离。
 */
function resolveDataRoot({ isPackaged, env, appDir }) {
    if (isPackaged) {
        const localAppData = env.LOCALAPPDATA;
        if (!localAppData || !localAppData.trim()) {
            throw new Error("正式模式无法确定用户数据目录：环境变量 LOCALAPPDATA 缺失。");
        }
        const base = requireAbsolute(localAppData.trim(), "LOCALAPPDATA");
        return path.join(base, APP_NAME);
    }

    const override = env[DEV_DATA_OVERRIDE];
    if (override && override.trim()) {
        return requireAbsolute(override.trim(), DEV_DATA_OVERRIDE);
    }
    return path.join(appDir, "test-user-data");
}

/**
 * 打包版只使用随包携带的 JRE；开发版优先显式路径，其次 JAVA_HOME，最后 PATH 中的 java。
 */
function resolveJava({ isPackaged, resourcesPath, env }) {
    if (isPackaged) {
        return requireFile(
            path.join(resourcesPath, "jre", "bin", "java.exe"),
            "内置 Java 运行时"
        );
    }

    const explicit = env[JAVA_OVERRIDE];
    if (explicit && explicit.trim()) {
        return requireFile(
            requireAbsolute(explicit.trim(), JAVA_OVERRIDE),
            `${JAVA_OVERRIDE} 指向的 Java`
        );
    }

    const javaHome = env.JAVA_HOME;
    if (javaHome && javaHome.trim()) {
        const candidate = path.join(javaHome.trim(), "bin", "java.exe");
        if (isFile(candidate)) return candidate;
    }

    return "java";
}

/**
 * 打包版 JAR 使用 electron-builder 的稳定 staging 名称。
 * 开发版从 backend/target 查找唯一的 Spring Boot JAR；多个候选时拒绝猜测。
 */
function resolveBackendJar({ isPackaged, resourcesPath, env, projectRoot }) {
    if (isPackaged) {
        return requireFile(
            path.join(resourcesPath, "app", "bearcoding-backend.jar"),
            "内置 Spring Boot JAR"
        );
    }

    const explicit = env[BACKEND_JAR_OVERRIDE];
    if (explicit && explicit.trim()) {
        return requireFile(
            requireAbsolute(explicit.trim(), BACKEND_JAR_OVERRIDE),
            `${BACKEND_JAR_OVERRIDE} 指向的后端 JAR`
        );
    }

    const targetDir = path.join(projectRoot, "backend", "target");
    let entries;
    try {
        entries = fs.readdirSync(targetDir, { withFileTypes: true });
    } catch {
        throw new Error(
            `后端构建目录不存在：${targetDir}\n请先运行 desktop\\build-portable.ps1，或手动执行 Maven package。`
        );
    }

    const jars = entries
        .filter((entry) => entry.isFile())
        .map((entry) => entry.name)
        .filter((name) => name.endsWith(".jar"))
        .filter((name) => !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"))
        .sort();

    if (jars.length === 0) {
        throw new Error(
            `未找到后端 JAR：${targetDir}\n请先运行 desktop\\build-portable.ps1，或手动执行 Maven package。`
        );
    }
    if (jars.length > 1) {
        throw new Error(
            `后端 target 目录存在多个 JAR，无法确定使用哪一个：\n${jars.join("\n")}\n` +
            "请先执行 Maven clean package，或通过 BEARCODING_BACKEND_JAR 显式指定。"
        );
    }

    return path.join(targetDir, jars[0]);
}

function resolveDesktopPaths(options) {
    return {
        dataRoot: resolveDataRoot(options),
        java: resolveJava(options),
        jar: resolveBackendJar(options),
        icon: requireFile(path.join(options.appDir, "build", "icon.png"), "应用图标")
    };
}

module.exports = {
    APP_NAME,
    DEV_DATA_OVERRIDE,
    BACKEND_JAR_OVERRIDE,
    JAVA_OVERRIDE,
    resolveDataRoot,
    resolveJava,
    resolveBackendJar,
    resolveDesktopPaths
};
