"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { findLocalServer, resolveServerLaunch } = require("../server-launch");

function createLocalBuild() {
    const projectPath = fs.mkdtempSync(path.join(os.tmpdir(), "zeron-vscode-"));
    const classesPath = path.join(projectPath, "target", "classes");
    const dependenciesPath = path.join(projectPath, "target", "dependency");
    const workspacePath = path.join(projectPath, "sample_programs", "example");
    fs.mkdirSync(path.join(classesPath, "com", "maruseron", "zeron"), { recursive: true });
    fs.mkdirSync(dependenciesPath, { recursive: true });
    fs.mkdirSync(workspacePath, { recursive: true });
    fs.writeFileSync(path.join(projectPath, "pom.xml"), "<project/>");
    fs.writeFileSync(path.join(classesPath, "com", "maruseron", "zeron", "Zeron.class"), "");
    fs.writeFileSync(path.join(dependenciesPath, "lsp4j.jar"), "");
    return { projectPath, workspacePath, classesPath, dependenciesPath };
}

test("prefers compiled server classes found in a workspace ancestor", () => {
    const localBuild = createLocalBuild();
    try {
        const server = findLocalServer(localBuild.workspacePath);
        assert.equal(server.projectPath, localBuild.projectPath);
        assert.equal(server.command, "java");
        assert.deepEqual(server.args, [
            "-cp",
            `${localBuild.classesPath}${path.delimiter}${path.join(localBuild.dependenciesPath, "*")}`,
            "com.maruseron.zeron.Zeron",
            "lsp"
        ]);
        assert.equal(resolveServerLaunch(localBuild.workspacePath, "zeron", false).local, true);
    } finally {
        fs.rmSync(localBuild.projectPath, { recursive: true, force: true });
    }
});

test("falls back to the installed compiler when no local build exists", () => {
    const workspacePath = fs.mkdtempSync(path.join(os.tmpdir(), "zeron-vscode-"));
    try {
        const server = resolveServerLaunch(workspacePath, "zeron", false, "win32");
        assert.equal(server.command, "zeron");
        assert.deepEqual(server.args, ["lsp"]);
        assert.equal(server.local, false);
        assert.equal(server.shell, true);
    } finally {
        fs.rmSync(workspacePath, { recursive: true, force: true });
    }
});

test("honors an explicit executable override before a local build", () => {
    const localBuild = createLocalBuild();
    try {
        const server = resolveServerLaunch(
            localBuild.workspacePath,
            "C:\\Program Files\\Zeron\\zeron.exe",
            true,
            "win32");
        assert.equal(server.command, '"C:\\Program Files\\Zeron\\zeron.exe"');
        assert.deepEqual(server.args, ["lsp"]);
        assert.equal(server.local, false);
    } finally {
        fs.rmSync(localBuild.projectPath, { recursive: true, force: true });
    }
});
