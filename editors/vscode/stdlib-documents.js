"use strict";

const path = require("node:path");

const DOCUMENT_SCHEME = "zeron-stdlib";

function bundledRelativePath(uri) {
    if (uri.scheme !== "file") return undefined;
    const normalized = uri.fsPath.replace(/\\/g, "/");
    const match = normalized.match(
        /(?:^|\/)(?:share\/zeron\/stdlib|src\/main\/resources\/stdlib|target\/classes\/stdlib)\/(zeron\/.+\.zn)$/i
    );
    return match?.[1];
}

function readonlyUri(vscode, sourceUri, relativePath) {
    const encodedSource = Buffer.from(sourceUri.toString(), "utf8").toString("base64url");
    return vscode.Uri.parse(`${DOCUMENT_SCHEME}:/${relativePath}?source=${encodedSource}`);
}

function createDefinitionMiddleware(vscode) {
    const rewrite = (definition) => {
        if (definition === undefined || definition === null) return definition;
        if (Array.isArray(definition)) return definition.map(rewrite);

        if (definition.uri) {
            const relativePath = bundledRelativePath(definition.uri);
            return relativePath
                ? new vscode.Location(readonlyUri(vscode, definition.uri, relativePath), definition.range)
                : definition;
        }
        if (definition.targetUri) {
            const relativePath = bundledRelativePath(definition.targetUri);
            return relativePath
                ? { ...definition, targetUri: readonlyUri(vscode, definition.targetUri, relativePath) }
                : definition;
        }
        return definition;
    };

    return {
        async provideDefinition(document, position, token, next) {
            return rewrite(await next(document, position, token));
        }
    };
}

function createContentProvider(vscode) {
    return {
        async provideTextDocumentContent(uri) {
            const relativePath = uri.path.replace(/^\/+/, "");
            if (!relativePath.startsWith("zeron/")
                    || !relativePath.endsWith(".zn")
                    || path.posix.normalize(relativePath) !== relativePath) {
                throw new Error(`Invalid Zeron standard-library document: ${uri}`);
            }

            const query = new URLSearchParams(uri.query);
            const encodedSource = query.get("source");
            if (!encodedSource || [...query.keys()].length !== 1) {
                throw new Error(`Missing source for Zeron standard-library document: ${uri}`);
            }
            const sourceText = Buffer.from(encodedSource, "base64url").toString("utf8");
            if (Buffer.from(sourceText, "utf8").toString("base64url") !== encodedSource) {
                throw new Error(`Invalid source URI for Zeron standard-library document: ${uri}`);
            }
            const sourceUri = vscode.Uri.parse(sourceText);
            if (sourceUri.scheme !== "file") {
                throw new Error(`Unsupported source URI for Zeron standard-library document: ${uri}`);
            }

            const sourcePath = path.resolve(sourceUri.fsPath).replace(/\\/g, "/").toLowerCase();
            const validSuffixes = [
                `/share/zeron/stdlib/${relativePath}`,
                `/src/main/resources/stdlib/${relativePath}`,
                `/target/classes/stdlib/${relativePath}`
            ];
            if (!validSuffixes.some(suffix => sourcePath.endsWith(suffix.toLowerCase()))) {
                throw new Error(`Source is outside the bundled Zeron standard library: ${sourceUri}`);
            }

            const content = await vscode.workspace.fs.readFile(sourceUri);
            return Buffer.from(content).toString("utf8");
        }
    };
}

module.exports = {
    createContentProvider,
    createDefinitionMiddleware
};
