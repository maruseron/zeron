"use strict";

const fs = require("node:fs");
const path = require("node:path");

const mainClass = "com.maruseron.zeron.Zeron";

function findLocalServer(workspacePath) {
    let projectPath = path.resolve(workspacePath);
    while (true) {
        const targetPath = path.join(projectPath, "target");
        const classesPath = path.join(targetPath, "classes");
        const dependenciesPath = path.join(targetPath, "dependency");
        const mainClassPath = path.join(classesPath, "com", "maruseron", "zeron", "Zeron.class");
        const hasRuntimeDependencies = fs.existsSync(dependenciesPath)
            && fs.readdirSync(dependenciesPath).some(file => file.endsWith(".jar"));

        if (fs.existsSync(path.join(projectPath, "pom.xml"))
            && fs.existsSync(mainClassPath)
            && hasRuntimeDependencies) {
            return {
                command: "java",
                args: [
                    "-cp",
                    `${classesPath}${path.delimiter}${path.join(dependenciesPath, "*")}`,
                    mainClass,
                    "lsp"
                ],
                projectPath
            };
        }

        const parentPath = path.dirname(projectPath);
        if (parentPath === projectPath) return undefined;
        projectPath = parentPath;
    }
}

function resolveServerLaunch(workspacePath, executable, hasExplicitExecutable, platform = process.platform) {
    if (!hasExplicitExecutable) {
        const localServer = findLocalServer(workspacePath);
        if (localServer !== undefined) {
            return { ...localServer, local: true };
        }
    }

    if (typeof executable !== "string" || executable.trim().length === 0) {
        throw new Error("The Zeron server executable path must not be empty.");
    }

    const trimmedExecutable = executable.trim();
    return {
        command: platform === "win32" && trimmedExecutable.includes(" ")
            ? `"${trimmedExecutable}"`
            : trimmedExecutable,
        args: ["lsp"],
        local: false,
        shell: platform === "win32"
    };
}

module.exports = { findLocalServer, resolveServerLaunch };
