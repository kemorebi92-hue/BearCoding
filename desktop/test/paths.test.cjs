"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
    resolveDataRoot,
    resolveJava,
    resolveBackendJar,
    resolveDesktopPaths
} = require("../paths.cjs");

function tempDir(t) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bearcoding-desktop-paths-"));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    return dir;
}

test("packaged data root is %LOCALAPPDATA%/BearCoding", () => {
    const root = resolveDataRoot({
        isPackaged: true,
        env: { LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local" },
        appDir: "C:\\repo\\desktop"
    });
    assert.equal(root, path.join("C:\\Users\\tester\\AppData\\Local", "BearCoding"));
});

test("packaged data root fails closed without LOCALAPPDATA", () => {
    assert.throws(
        () => resolveDataRoot({ isPackaged: true, env: {}, appDir: "C:\\repo\\desktop" }),
        /LOCALAPPDATA/
    );
});

test("development data root defaults to desktop/test-user-data", () => {
    const appDir = "C:\\repo\\desktop";
    assert.equal(
        resolveDataRoot({ isPackaged: false, env: {}, appDir }),
        path.join(appDir, "test-user-data")
    );
});

test("development data root accepts only an absolute override", () => {
    const absolute = "D:\\BearCoding-Test-Data";
    assert.equal(
        resolveDataRoot({
            isPackaged: false,
            env: { BEARCODING_DEV_DATA_DIR: absolute },
            appDir: "C:\\repo\\desktop"
        }),
        path.normalize(absolute)
    );
    assert.throws(
        () => resolveDataRoot({
            isPackaged: false,
            env: { BEARCODING_DEV_DATA_DIR: "relative-test-data" },
            appDir: "C:\\repo\\desktop"
        }),
        /绝对路径/
    );
});

test("packaged Java must exist inside resources/jre", (t) => {
    const resources = tempDir(t);
    assert.throws(
        () => resolveJava({ isPackaged: true, resourcesPath: resources, env: {} }),
        /内置 Java 运行时/
    );

    const java = path.join(resources, "jre", "bin", "java.exe");
    fs.mkdirSync(path.dirname(java), { recursive: true });
    fs.writeFileSync(java, "");
    assert.equal(resolveJava({ isPackaged: true, resourcesPath: resources, env: {} }), java);
});

test("development Java prefers explicit path, then JAVA_HOME, then PATH", (t) => {
    const root = tempDir(t);
    const explicit = path.join(root, "custom-java.exe");
    fs.writeFileSync(explicit, "");
    assert.equal(
        resolveJava({ isPackaged: false, resourcesPath: root, env: { BEARCODING_JAVA: explicit } }),
        explicit
    );

    const javaHome = path.join(root, "jdk");
    const homeJava = path.join(javaHome, "bin", "java.exe");
    fs.mkdirSync(path.dirname(homeJava), { recursive: true });
    fs.writeFileSync(homeJava, "");
    assert.equal(
        resolveJava({ isPackaged: false, resourcesPath: root, env: { JAVA_HOME: javaHome } }),
        homeJava
    );

    assert.equal(
        resolveJava({ isPackaged: false, resourcesPath: root, env: {} }),
        "java"
    );
});

test("development JAR resolver ignores Maven .original and source jars", (t) => {
    const projectRoot = tempDir(t);
    const target = path.join(projectRoot, "backend", "target");
    fs.mkdirSync(target, { recursive: true });
    const jar = path.join(target, "bailianAI-3.0.0.jar");
    fs.writeFileSync(jar, "");
    fs.writeFileSync(path.join(target, "bailianAI-3.0.0.jar.original"), "");
    fs.writeFileSync(path.join(target, "bailianAI-3.0.0-sources.jar"), "");

    assert.equal(
        resolveBackendJar({ isPackaged: false, resourcesPath: projectRoot, env: {}, projectRoot }),
        jar
    );
});

test("development JAR resolver refuses to guess among multiple JARs", (t) => {
    const projectRoot = tempDir(t);
    const target = path.join(projectRoot, "backend", "target");
    fs.mkdirSync(target, { recursive: true });
    fs.writeFileSync(path.join(target, "old.jar"), "");
    fs.writeFileSync(path.join(target, "new.jar"), "");

    assert.throws(
        () => resolveBackendJar({ isPackaged: false, resourcesPath: projectRoot, env: {}, projectRoot }),
        /多个 JAR/
    );
});

test("packaged JAR and icon are resolved from resources and app directory", (t) => {
    const root = tempDir(t);
    const resources = path.join(root, "resources");
    const appDir = path.join(root, "app");
    const jar = path.join(resources, "app", "bearcoding-backend.jar");
    const java = path.join(resources, "jre", "bin", "java.exe");
    const icon = path.join(appDir, "build", "icon.png");
    fs.mkdirSync(path.dirname(jar), { recursive: true });
    fs.mkdirSync(path.dirname(java), { recursive: true });
    fs.mkdirSync(path.dirname(icon), { recursive: true });
    fs.writeFileSync(jar, "");
    fs.writeFileSync(java, "");
    fs.writeFileSync(icon, "");

    const resolved = resolveDesktopPaths({
        isPackaged: true,
        resourcesPath: resources,
        appDir,
        projectRoot: root,
        env: { LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local" }
    });

    assert.equal(resolved.jar, jar);
    assert.equal(resolved.java, java);
    assert.equal(resolved.icon, icon);
    assert.equal(
        resolved.dataRoot,
        path.join("C:\\Users\\tester\\AppData\\Local", "BearCoding")
    );
});
