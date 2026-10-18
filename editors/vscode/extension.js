"use strict";

const vscode = require("vscode");
const { LanguageClient, TransportKind } = require("vscode-languageclient/node");

let client;

async function activate(context) {
    const workspaceFolders = vscode.workspace.workspaceFolders ?? [];
    if (workspaceFolders.length !== 1) {
        const message = workspaceFolders.length === 0
            ? "Open a Zeron project folder to enable Zeron language features."
            : "The Zeron language server currently supports one project per VS Code workspace.";
        void vscode.window.showWarningMessage(message);
        return;
    }

    const workspaceFolder = workspaceFolders[0];
    const outputChannel = vscode.window.createOutputChannel("Zeron Language Server");
    context.subscriptions.push(outputChannel);

    const configuredExecutable = vscode.workspace.getConfiguration("zeron").get("server.path", "zeron");
    if (typeof configuredExecutable !== "string" || configuredExecutable.trim().length === 0) {
        const message = "The Zeron server executable path must not be empty.";
        outputChannel.appendLine(message);
        void vscode.window.showErrorMessage(message);
        return;
    }
    const executable = configuredExecutable.trim();
    const command = process.platform === "win32" && executable.includes(" ")
        ? `"${executable}"`
        : executable;

    const serverOptions = {
        command,
        args: ["lsp"],
        transport: TransportKind.stdio,
        options: {
            cwd: workspaceFolder.uri.fsPath,
            shell: process.platform === "win32"
        }
    };
    const clientOptions = {
        documentSelector: [{ scheme: "file", language: "zeron" }],
        workspaceFolder,
        outputChannel
    };

    client = new LanguageClient("zeron", "Zeron Language Server", serverOptions, clientOptions);
    context.subscriptions.push(client);

    try {
        await client.start();
    } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        const message = `Unable to start the Zeron language server: ${detail}`;
        outputChannel.appendLine(message);
        void vscode.window.showErrorMessage(
            `${message}. Install Zeron 0.3.0 or later, or configure zeron.server.path.`);
    }
}

function deactivate() {
    if (client === undefined) return undefined;
    return client.stop();
}

module.exports = { activate, deactivate };
