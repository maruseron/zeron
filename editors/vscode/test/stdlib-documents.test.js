"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const { fileURLToPath, pathToFileURL } = require("node:url");
const test = require("node:test");

const { createContentProvider, createDefinitionMiddleware } = require("../stdlib-documents");

class TestUri {
    constructor(scheme, fsPath, pathName, query, text) {
        this.scheme = scheme;
        this.fsPath = fsPath;
        this.path = pathName;
        this.query = query;
        this.text = text;
    }

    toString() {
        return this.text;
    }

    static parse(text) {
        const parsed = new URL(text);
        const scheme = parsed.protocol.slice(0, -1);
        if (scheme === "file") {
            return new TestUri(scheme, fileURLToPath(parsed), parsed.pathname, parsed.search.slice(1), text);
        }
        return new TestUri(scheme, "", decodeURIComponent(parsed.pathname), parsed.search.slice(1), text);
    }

    static file(filePath) {
        const text = pathToFileURL(filePath).toString();
        return new TestUri("file", fileURLToPath(text), new URL(text).pathname, "", text);
    }
}

class TestLocation {
    constructor(uri, range) {
        this.uri = uri;
        this.range = range;
    }
}

function mockVscode() {
    const reads = [];
    const vscode = {
        Uri: TestUri,
        Location: TestLocation,
        workspace: {
            fs: {
                async readFile(uri) {
                    reads.push(uri.fsPath);
                    return Buffer.from("public type Option<T> = Some(value: T) | None;");
                }
            }
        }
    };
    return { vscode, reads };
}

test("rewrites standard-library definitions to read-only virtual documents", async () => {
    const { vscode, reads } = mockVscode();
    const source = path.join(path.parse(process.cwd()).root, "Program Files", "Zeron",
        "share", "zeron", "stdlib", "zeron", "lang", "option.zn");
    const location = new TestLocation(TestUri.file(source), { start: 1, end: 2 });
    const middleware = createDefinitionMiddleware(vscode);

    const result = await middleware.provideDefinition({}, {}, {}, async () => [location]);

    assert.equal(result[0].uri.scheme, "zeron-stdlib");
    assert.equal(result[0].uri.path, "/zeron/lang/option.zn");
    assert.deepEqual(result[0].range, location.range);
    const content = await createContentProvider(vscode).provideTextDocumentContent(result[0].uri);
    assert.match(content, /public type Option/);
    assert.deepEqual(reads, [source]);
});

test("leaves project definitions unchanged", async () => {
    const { vscode } = mockVscode();
    const location = new TestLocation(
        TestUri.file(path.join(process.cwd(), "src", "Main.zn")),
        { start: 1, end: 2 });
    const result = await createDefinitionMiddleware(vscode)
        .provideDefinition({}, {}, {}, async () => location);

    assert.equal(result, location);
});

test("rejects virtual standard-library URIs that point outside bundled source roots", async () => {
    const { vscode } = mockVscode();
    const source = path.join(process.cwd(), "private", "option.zn");
    const encodedSource = Buffer.from(TestUri.file(source).toString(), "utf8").toString("base64url");
    const uri = TestUri.parse(`zeron-stdlib:/zeron/lang/option.zn?source=${encodedSource}`);

    await assert.rejects(createContentProvider(vscode).provideTextDocumentContent(uri),
        /outside the bundled Zeron standard library/);
});
