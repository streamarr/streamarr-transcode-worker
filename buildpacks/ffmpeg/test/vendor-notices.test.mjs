import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const vendor = fileURLToPath(
    new URL("../bin/vendor-notices.mjs", import.meta.url),
);
const generator = fileURLToPath(
    new URL("../bin/generate-notices.mjs", import.meta.url),
);
const checksum = (text) => createHash("sha256").update(text).digest("hex");
const REVIEWED = "a".repeat(40);
const LOCKED = "c".repeat(40);
const ALPHA_1 = "1".repeat(40);
const ALPHA_2 = "2".repeat(40);
const FFMPEG = "https://github.com/jellyfin/jellyfin-ffmpeg";
const RAW = "https://raw.githubusercontent.com";
const API = "https://api.github.com/repos";
const LICENSE = "Alpha license\n";
const HEADER = "/* Copyright Beta */\n";
const INDEX = "Component source index\n======================\n\n";

function recipe(pins, flag) {
    const lines = pins.flatMap(([repository, revision], index) => {
        const suffix = index === 0 ? "" : String(index + 1);
        return [
            `SCRIPT_REPO${suffix}="${repository}.git"`,
            `SCRIPT_COMMIT${suffix}="${revision}"`,
        ];
    });
    const configure = flag
        ? `ffbuild_configure() {\n    echo ${flag}\n}\n`
        : "";
    return `#!/bin/bash\n\n${lines.join("\n")}\n\n${configure}`;
}

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "ffmpeg-vendor-test-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const upstream = path.join(root, "upstream");
    const commands = path.join(root, "commands");
    const buildpack = path.join(root, "ffmpeg");
    const temporary = path.join(root, "tmp");
    for (const directory of [upstream, commands, buildpack, temporary])
        fs.mkdirSync(directory);
    fs.writeFileSync(
        path.join(commands, "curl"),
        `#!/usr/bin/env node
const fs = require("node:fs");
const { createHash } = require("node:crypto");
const url = process.argv.findLast((argument) => argument.startsWith("https://"));
const output = process.argv.includes("--output") ? process.argv[process.argv.indexOf("--output") + 1] : "-";
if (output.startsWith("/dev/")) {
    console.error("curl: (23) Failure writing output to destination " + output);
    process.exit(23);
}
// Like curl, every attempt truncates a destination file, while bytes sent to stdout stay sent.
const deliver = (bytes) => (output === "-" ? process.stdout.write(bytes) : fs.appendFileSync(output, bytes));
if (output !== "-") fs.writeFileSync(output, "");
fs.appendFileSync(process.env.FAKE_UPSTREAM + "/requests", url + "\\n");
const file = process.env.FAKE_UPSTREAM + "/" + createHash("sha256").update(url).digest("hex");
if (!fs.existsSync(file)) {
    console.error("curl: (22) The requested URL returned error: 404 " + url);
    process.exit(22);
}
if (fs.existsSync(file + ".stalled")) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2000);
const interrupted = file + ".interrupted";
if (fs.existsSync(interrupted)) {
    deliver(fs.readFileSync(interrupted));
    fs.rmSync(interrupted);
    console.error("curl: (18) transfer closed with outstanding read data remaining");
    process.exit(18);
}
deliver(fs.readFileSync(file));
`,
        { mode: 0o755 },
    );
    const serve = (url, body) =>
        fs.writeFileSync(
            path.join(upstream, checksum(url)),
            typeof body === "string" ? body : JSON.stringify(body),
        );
    const unserve = (url) => fs.rmSync(path.join(upstream, checksum(url)));
    // The next transfer of this URL delivers only its first bytes and ends as curl's exit 18.
    const interrupt = (url, bytes) =>
        fs.writeFileSync(
            path.join(upstream, `${checksum(url)}.interrupted`),
            fs.readFileSync(path.join(upstream, checksum(url))).subarray(0, bytes),
        );
    const stall = (url) => fs.writeFileSync(path.join(upstream, `${checksum(url)}.stalled`), "");
    const requests = (url) =>
        fs
            .readFileSync(path.join(upstream, "requests"), "utf8")
            .split("\n")
            .filter((requested) => requested === url).length;
    const write = (relative, text) => {
        const file = path.join(buildpack, relative);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, text);
    };
    const read = (relative) =>
        fs.readFileSync(path.join(buildpack, relative), "utf8");

    const components = [
        {
            id: "alpha",
            repository: "https://github.com/example/alpha",
            revision: ALPHA_1,
            recipe: "builder/scripts.d/50-alpha.sh",
            architectures: ["amd64", "arm64"],
            role: "Static alpha library (--enable-libalpha).",
            notices: [
                {
                    url: `${RAW}/example/alpha/${ALPHA_1}/COPYING`,
                    sha256: checksum(LICENSE),
                    file: "alpha/COPYING.txt",
                },
            ],
            licenseExpression: "MIT",
            distribution: "runtime",
            revisionEvidence: "source",
        },
        {
            id: "beta",
            repository: "https://gitlab.example/group/beta",
            revision: "v1.0",
            recipe: "builder/scripts.d/50-beta.sh",
            architectures: ["arm64"],
            role: "Beta API headers.",
            notices: [
                {
                    url: "https://gitlab.example/group/beta/-/raw/v1.0/include/beta.h",
                    sha256: checksum(HEADER),
                    file: "beta/include/beta.h.txt",
                    excerpt: true,
                },
            ],
            licenseExpression: "LicenseRef-beta",
            distribution: "embedded",
            revisionEvidence: "source",
        },
        {
            id: "ffmpeg",
            repository: FFMPEG,
            revision: REVIEWED,
            recipe: "builder",
            architectures: ["amd64", "arm64"],
            role: "FFmpeg executables.",
            notices: [
                {
                    url: `${RAW}/jellyfin/jellyfin-ffmpeg/${REVIEWED}/LICENSE.md`,
                    sha256: checksum("FFmpeg license\n"),
                    file: "ffmpeg/LICENSE.md",
                },
            ],
            licenseExpression: "GPL-3.0-or-later",
            distribution: "runtime",
            revisionEvidence: "source",
        },
    ];
    const stanza = (component) =>
        `${component.id} (${component.architectures.join(", ")})\n  ${component.role}\n` +
        `  Repository: ${component.repository}\n  Revision: ${component.revision}\n` +
        `  Recipe: ${component.recipe}\n  Notices: see notices/sources.json (${component.id})\n`;
    const writeInventory = () => {
        write("notices/sources.json", JSON.stringify(components, null, 2) + "\n");
        write(
            "SOURCE.txt",
            `Binary release: ${FFMPEG}/releases/tag/v8.1.2-4\nFFmpeg source revision: ${REVIEWED}\n\n` +
                INDEX +
                components.map(stanza).join("\n"),
        );
    };
    writeInventory();
    write("notices/alpha/COPYING.txt", LICENSE);
    write("notices/beta/include/beta.h.txt", HEADER);
    write("notices/ffmpeg/LICENSE.md", "FFmpeg license\n");
    write("LICENSE.txt", "GPL license text\n");
    for (const architecture of ["amd64", "arm64"]) {
        write(
            `notices/buildconf-${architecture}.txt`,
            "ffmpeg version 9.0.0-Jellyfin\n  configuration: --enable-gpl --enable-libalpha --enable-libdelta\n",
        );
    }
    write(
        "notices/manifest",
        `release=v8.1.2-4\nsource_revision=${REVIEWED}\namd64_sha256=${"a".repeat(64)}\narm64_sha256=${"b".repeat(64)}\n`,
    );
    write(
        "ffmpeg.lock",
        [
            "release=v9.0.0-1",
            "version=9.0.0-1",
            `source_revision=${LOCKED}`,
            "asset_variant=gpl",
            "amd64_asset=jellyfin-ffmpeg_9.0.0-1_portable_linux64-gpl.tar.xz",
            `amd64_sha256=${"d".repeat(64)}`,
            "arm64_asset=jellyfin-ffmpeg_9.0.0-1_portable_linuxarm64-gpl.tar.xz",
            `arm64_sha256=${"e".repeat(64)}`,
        ].join("\n") + "\n",
    );

    const recipes = new Map([
        ["50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_1]], "--enable-libalpha")],
        ["50-beta.sh", recipe([["https://gitlab.example/group/beta", "v1.0"]])],
    ]);
    const serveRecipes = (revision, entries = recipes) => {
        serve(`${API}/jellyfin/jellyfin-ffmpeg/git/trees/${revision}:builder/scripts.d?recursive=1`, {
            truncated: false,
            tree: [...entries.keys()].map((name) => ({ path: name, type: "blob" })),
        });
        for (const [name, text] of entries)
            serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${revision}/builder/scripts.d/${name}`, text);
    };
    serveRecipes(REVIEWED);
    serveRecipes(LOCKED);
    serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/LICENSE.md`, "FFmpeg license\n");
    serve(`${RAW}/example/alpha/${ALPHA_1}/COPYING`, LICENSE);
    serve("https://gitlab.example/group/beta/-/raw/v1.0/include/beta.h", HEADER + "int beta(void);\n");

    const run = (...args) => {
        const result = spawnSync(process.execPath, [vendor, "--root", buildpack, ...args], {
            encoding: "utf8",
            timeout: 60000,
            env: {
                ...process.env,
                PATH: `${commands}${path.delimiter}${process.env.PATH}`,
                FAKE_UPSTREAM: upstream,
                GITHUB_TOKEN: "",
                TMPDIR: temporary,
            },
        });
        assert.ifError(result.error);
        assert.deepEqual(fs.readdirSync(temporary), [], "downloads are removed when the run ends");
        return { status: result.status, output: result.stdout + result.stderr };
    };
    const inventory = () => JSON.parse(read("notices/sources.json"));
    const validate = () => {
        write(
            "notices/manifest",
            `release=v9.0.0-1\nsource_revision=${LOCKED}\namd64_sha256=${"d".repeat(64)}\narm64_sha256=${"e".repeat(64)}\n`,
        );
        return spawnSync(process.execPath, [generator, "--root", buildpack, "--validate"], {
            encoding: "utf8",
            timeout: 60000,
        });
    };
    return {
        buildpack,
        components,
        interrupt,
        inventory,
        read,
        recipes,
        requests,
        run,
        serve,
        serveRecipes,
        stall,
        unserve,
        validate,
        write,
        writeInventory,
    };
}

test("Should rebind only the FFmpeg revision when no upstream pin moved", (t) => {
    const { inventory, read, run, validate } = fixture(t);
    const manifest = read("notices/manifest");

    const result = run();

    assert.equal(result.status, 0, result.output);
    const ffmpeg = inventory().find((component) => component.id === "ffmpeg");
    assert.equal(ffmpeg.revision, LOCKED);
    assert.equal(ffmpeg.notices[0].url, `${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/LICENSE.md`);
    assert.equal(inventory().find((component) => component.id === "alpha").revision, ALPHA_1);
    assert.match(read("SOURCE.txt"), new RegExp(`releases/tag/v9\\.0\\.0-1\\nFFmpeg source revision: ${LOCKED}`));
    assert.doesNotMatch(read("SOURCE.txt"), new RegExp(REVIEWED));
    assert.equal(read("notices/manifest"), manifest, "review approval is never written");
    assert.match(result.output, /Inventory content unchanged/);
    const validation = validate();
    assert.equal(validation.status, 0, validation.stderr);
});

test("Should follow a moved pin without touching an unchanged license text", (t) => {
    const { inventory, read, recipes, run, serve, serveRecipes, validate } = fixture(t);
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, LICENSE);

    const result = run();

    assert.equal(result.status, 0, result.output);
    const alpha = inventory().find((component) => component.id === "alpha");
    assert.equal(alpha.revision, ALPHA_2);
    assert.deepEqual(alpha.notices, [
        { url: `${RAW}/example/alpha/${ALPHA_2}/COPYING`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" },
    ]);
    assert.match(read("SOURCE.txt"), new RegExp(`alpha \\(amd64, arm64\\)[\\s\\S]*?Revision: ${ALPHA_2}`));
    assert.match(result.output, /Pin moved, license text unchanged: alpha/);
    assert.match(result.output, /Inventory content changed/);
    assert.equal(validate().status, 0);
});

test("Should keep an unchanged license text when its transfer is interrupted and retried", (t) => {
    const { interrupt, inventory, read, recipes, requests, run, serve, serveRecipes, validate } = fixture(t);
    const moved = `${RAW}/example/alpha/${ALPHA_2}/COPYING`;
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    serve(moved, LICENSE);
    interrupt(moved, 8);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(requests(moved), 2, "the interrupted transfer is retried");
    assert.equal(read("notices/alpha/COPYING.txt"), LICENSE);
    assert.deepEqual(inventory().find((component) => component.id === "alpha").notices, [
        { url: moved, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" },
    ]);
    assert.match(result.output, /Pin moved, license text unchanged: alpha/);
    assert.doesNotMatch(result.output, /License text changed/);
    assert.equal(validate().status, 0);
});

test("Should record the upstream license text of a new component when its transfer is interrupted and retried", (t) => {
    const { interrupt, inventory, read, recipes, requests, run, serve, serveRecipes } = fixture(t);
    const delta = "f".repeat(40);
    const license = `${RAW}/example/delta/${delta}/LICENSE`;
    serveRecipes(LOCKED, new Map(recipes).set("50-delta.sh", recipe([["https://github.com/example/delta", delta]], "--enable-libdelta")));
    serve(`${API}/example/delta/git/trees/${delta}?recursive=1`, { truncated: false, tree: [{ path: "LICENSE", type: "blob" }] });
    serve(`${API}/example/delta/license`, { license: { spdx_id: "BSD-3-Clause" } });
    serve(license, "Delta license\n");
    interrupt(license, 6);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(requests(license), 2, "the interrupted transfer is retried");
    assert.equal(read("notices/delta/LICENSE.txt"), "Delta license\n");
    assert.equal(inventory().find((component) => component.id === "delta").notices[0].sha256, checksum("Delta license\n"));
});

test("Should read a recipe listing whose transfer is interrupted and retried", (t) => {
    const { interrupt, requests, run } = fixture(t);
    const listing = `${API}/jellyfin/jellyfin-ffmpeg/git/trees/${LOCKED}:builder/scripts.d?recursive=1`;
    interrupt(listing, 20);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(requests(listing), 2, "the interrupted transfer is retried");
    assert.match(result.output, /Inventory content unchanged/);
});

test("Should leave no download behind when the run fails while another transfer is in flight", (t) => {
    const { run, serve, stall } = fixture(t);
    const listing = (revision) => `${API}/jellyfin/jellyfin-ffmpeg/git/trees/${revision}:builder/scripts.d?recursive=1`;
    stall(listing(REVIEWED));
    serve(listing(LOCKED), { truncated: true, tree: [] });

    const result = run();

    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /recipe listing .* is truncated/);
});

test("Should re-extract a changed header excerpt with the rule that produced the reviewed text", (t) => {
    const { inventory, read, recipes, run, serve, serveRecipes, validate } = fixture(t);
    const header = "/* Copyright Beta and contributors */\n";
    serveRecipes(LOCKED, new Map(recipes).set("50-beta.sh", recipe([["https://gitlab.example/group/beta", "v2.0"]])));
    serve("https://gitlab.example/group/beta/-/raw/v2.0/include/beta.h", header + "int beta(int);\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    const beta = inventory().find((component) => component.id === "beta");
    assert.equal(beta.revision, "v2.0");
    assert.deepEqual(beta.notices[0], {
        url: "https://gitlab.example/group/beta/-/raw/v2.0/include/beta.h",
        sha256: checksum(header),
        file: "beta/include/beta.h.txt",
        excerpt: true,
    });
    assert.equal(read("notices/beta/include/beta.h.txt"), header);
    assert.match(result.output, /License text changed: beta \(beta\/include\/beta\.h\.txt\)/);
    assert.equal(validate().status, 0);
});

for (const [name, reviewed, locked] of [
    ["gains terms behind an excerpt marker", LICENSE, `${LICENSE}/** @file */\nADDITIONAL TERMS: no commercial redistribution.\n`],
    ["gains terms after its leading comment", "/* Alpha license */\n", "/* Alpha license */\nADDITIONAL TERMS: no commercial redistribution.\n"],
    ["gains terms after its license comments", "/* Alpha license */\n\n/* Copyright Alpha */\n", "/* Alpha license */\n\n/* Copyright Alpha */\nADDITIONAL TERMS\n"],
    ["switches to CRLF line endings", LICENSE, "Alpha license\r\n"],
]) {
    test(`Should report a changed license text when a license reviewed as the whole file ${name}`, (t) => {
        const { components, inventory, read, recipes, run, serve, serveRecipes, validate, write, writeInventory } = fixture(t);
        const moved = `${RAW}/example/alpha/${ALPHA_2}/COPYING`;
        components[0].notices[0].sha256 = checksum(reviewed);
        writeInventory();
        write("notices/alpha/COPYING.txt", reviewed);
        serve(`${RAW}/example/alpha/${ALPHA_1}/COPYING`, reviewed);
        serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
        serve(moved, locked);

        const result = run();

        assert.equal(result.status, 0, result.output);
        assert.match(result.output, /License text changed: alpha \(alpha\/COPYING\.txt\)/);
        assert.doesNotMatch(result.output, /Pin moved, license text unchanged: alpha/);
        assert.deepEqual(inventory().find((component) => component.id === "alpha").notices, [
            { url: moved, sha256: checksum(locked), file: "alpha/COPYING.txt" },
        ]);
        assert.equal(read("notices/alpha/COPYING.txt"), locked);
        assert.equal(validate().status, 0);
    });
}

test("Should normalize line endings the same way as the reviewed text", (t) => {
    const { components, inventory, read, recipes, run, serve, serveRecipes, write, writeInventory } = fixture(t);
    components[0].notices[0].sha256 = checksum("Alpha\nlicense\n");
    writeInventory();
    write("notices/alpha/COPYING.txt", "Alpha\nlicense\n");
    serve(`${RAW}/example/alpha/${ALPHA_1}/COPYING`, "Alpha\r\nlicense\r\n");
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, "Alpha\r\nlicense v2\r\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(read("notices/alpha/COPYING.txt"), "Alpha\nlicense v2\n");
    assert.equal(inventory()[0].notices[0].sha256, checksum("Alpha\nlicense v2\n"));
});

test("Should decode base64 source views before comparing license text", (t) => {
    const { components, inventory, recipes, run, serve, serveRecipes, writeInventory } = fixture(t);
    components[0].repository = "https://chromium.googlesource.com/alpha";
    components[0].notices[0].url = `https://chromium.googlesource.com/alpha/+/${ALPHA_1}/COPYING?format=TEXT`;
    writeInventory();
    const pins = (revision) => recipe([["https://chromium.googlesource.com/alpha", revision]], "--enable-libalpha");
    serveRecipes(REVIEWED, new Map(recipes).set("50-alpha.sh", pins(ALPHA_1)));
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", pins(ALPHA_2)));
    serve(components[0].notices[0].url, Buffer.from(LICENSE).toString("base64"));
    serve(`https://chromium.googlesource.com/alpha/+/${ALPHA_2}/COPYING?format=TEXT`, Buffer.from(LICENSE).toString("base64"));

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(inventory()[0].revision, ALPHA_2);
    assert.equal(inventory()[0].notices[0].sha256, checksum(LICENSE));
});

test("Should follow a dependency to its new repository when the recipe replaces the pin", (t) => {
    const { inventory, read, recipes, run, serve, serveRecipes, validate } = fixture(t);
    const mirror = "https://chromium.googlesource.com/mirror/alpha";
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([[mirror, ALPHA_2]], "--enable-libalpha")));
    serve(`${mirror}/+/${ALPHA_2}/COPYING?format=TEXT`, Buffer.from(LICENSE).toString("base64"));

    const result = run();

    assert.equal(result.status, 0, result.output);
    const alpha = inventory().find((component) => component.id === "alpha");
    assert.equal(alpha.repository, mirror);
    assert.equal(alpha.revision, ALPHA_2);
    assert.deepEqual(alpha.notices, [
        { url: `${mirror}/+/${ALPHA_2}/COPYING?format=TEXT`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" },
    ]);
    assert.match(read("SOURCE.txt"), new RegExp(`Repository: ${mirror}\\n  Revision: ${ALPHA_2}`));
    assert.match(result.output, /Repository moved: alpha \(https:\/\/github\.com\/example\/alpha -> https:\/\/chromium\.googlesource\.com\/mirror\/alpha\)/);
    assert.match(result.output, /Inventory content changed/);
    assert.equal(validate().status, 0);
});

test("Should keep a shared license file intact for the component that still uses it", (t) => {
    const { components, inventory, read, recipes, run, serve, serveRecipes, validate, writeInventory } = fixture(t);
    components.push({
        ...components[0],
        id: "gamma",
        repository: "https://github.com/example/gamma",
        recipe: "builder/scripts.d/50-gamma.sh",
        notices: [{ url: `${RAW}/example/gamma/${ALPHA_1}/docs/LICENSE`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    const withGamma = new Map(recipes).set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_1]]));
    serveRecipes(REVIEWED, withGamma);
    serveRecipes(LOCKED, new Map(withGamma).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, "Alpha license v2\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(read("notices/alpha/COPYING.txt"), "Alpha license v2\n");
    const gamma = inventory().find((component) => component.id === "gamma");
    assert.equal(gamma.notices[0].file, "gamma/docs/LICENSE.txt");
    assert.equal(read("notices/gamma/docs/LICENSE.txt"), LICENSE);
    assert.equal(validate().status, 0);
});

test("Should write a changed text to its own file when the reviewed file belongs to another component", (t) => {
    const { components, inventory, read, recipes, run, serve, serveRecipes, validate, writeInventory } = fixture(t);
    components.push({
        ...components[0],
        id: "gamma",
        repository: "https://github.com/example/gamma",
        recipe: "builder/scripts.d/50-gamma.sh",
        notices: [{ url: `${RAW}/example/gamma/${ALPHA_1}/LICENSE.md`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    const withGamma = new Map(recipes).set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_1]]));
    serveRecipes(REVIEWED, withGamma);
    serveRecipes(LOCKED, new Map(recipes).set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_2]])));
    serve(`${RAW}/example/gamma/${ALPHA_1}/LICENSE.md`, LICENSE);
    serve(`${RAW}/example/gamma/${ALPHA_2}/LICENSE.md`, "Gamma license v2\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(read("notices/alpha/COPYING.txt"), LICENSE);
    assert.equal(inventory().find((component) => component.id === "gamma").notices[0].file, "gamma/LICENSE.md");
    assert.equal(read("notices/gamma/LICENSE.md"), "Gamma license v2\n");
    assert.equal(validate().status, 0);
});

// Models openmpt: LICENSE and src/mpt/LICENSE.BSD-3-Clause.txt were byte-identical at review and
// share the file named after the first.
function shareAlphaLicense({ components, recipes, serve, serveRecipes, writeInventory }, texts) {
    const origin = (revision, upstream) => `${RAW}/example/alpha/${revision}/${upstream}`;
    components[0].notices = Object.keys(texts).map((upstream) => ({ url: origin(ALPHA_1, upstream), sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }));
    writeInventory();
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    for (const [upstream, updated] of Object.entries(texts)) {
        serve(origin(ALPHA_1, upstream), LICENSE);
        serve(origin(ALPHA_2, upstream), updated);
    }
}

for (const [name, texts, expected, reported] of [
    [
        "rewrite the shared file once when both origins change to the same text",
        { COPYING: "Alpha license 2026\n", "src/LICENSE.BSD": "Alpha license 2026\n" },
        ["alpha/COPYING.txt", "alpha/COPYING.txt"],
        ["alpha/COPYING.txt"],
    ],
    [
        "keep the reviewed text in place when only the notice borrowing the file changes",
        { COPYING: LICENSE, "src/LICENSE.BSD": "Alpha license 2027\n" },
        ["alpha/COPYING.txt", "alpha/src/LICENSE.BSD.txt"],
        ["alpha/src/LICENSE.BSD.txt"],
    ],
    [
        "keep the file for the notice it is named after when both origins change differently",
        { "src/LICENSE.BSD": "Alpha BSD license 2026\n", COPYING: "Alpha license 2026\n" },
        ["alpha/src/LICENSE.BSD.txt", "alpha/COPYING.txt"],
        ["alpha/src/LICENSE.BSD.txt", "alpha/COPYING.txt"],
    ],
]) {
    test(`Should ${name}`, (t) => {
        const context = fixture(t);
        shareAlphaLicense(context, texts);

        const result = context.run();

        assert.equal(result.status, 0, result.output);
        const { notices } = context.inventory().find((component) => component.id === "alpha");
        assert.deepEqual(notices.map((notice) => notice.file), expected);
        for (const [index, updated] of Object.values(texts).entries()) {
            assert.equal(notices[index].sha256, checksum(updated));
            assert.equal(context.read(`notices/${expected[index]}`), updated);
        }
        assert.deepEqual(result.output.match(/(?<=License text changed: alpha \()[^)]+/g), reported);
        const validation = context.validate();
        assert.equal(validation.status, 0, validation.stderr);
    });
}

const BETA_HEADERS = ["beta.h", "beta_decode.h", "beta_parse.h"];
const CONTRIBUTORS = "/* Copyright Beta and contributors */\n";

// Models ffnvcodec: header excerpts that were byte-identical at review share the first header's file.
function shareBetaExcerpt({ components, recipes, serve, serveRecipes, writeInventory }, comments) {
    const origin = (revision, header) => `https://gitlab.example/group/beta/-/raw/${revision}/include/${header}`;
    components[1].notices = BETA_HEADERS.map((header) => ({ url: origin("v1.0", header), sha256: checksum(HEADER), file: "beta/include/beta.h.txt", excerpt: true }));
    writeInventory();
    serveRecipes(LOCKED, new Map(recipes).set("50-beta.sh", recipe([["https://gitlab.example/group/beta", "v2.0"]])));
    for (const [index, header] of BETA_HEADERS.entries()) {
        serve(origin("v1.0", header), `${HEADER}int reviewed(void);\n`);
        serve(origin("v2.0", header), `${comments[index]}int locked(void);\n`);
    }
}

for (const [name, comments, expected] of [
    [
        "give a changed header excerpt its own file when the headers sharing its reviewed file did not change",
        [HEADER, CONTRIBUTORS, HEADER],
        ["beta/include/beta.h.txt", "beta/include/beta_decode.h.txt", "beta/include/beta.h.txt"],
    ],
    [
        "move the reviewed excerpt to one file for the headers still using it when the header it is named after changes",
        [CONTRIBUTORS, HEADER, HEADER],
        ["beta/include/beta.h.txt", "beta/include/beta_decode.h.txt", "beta/include/beta_decode.h.txt"],
    ],
    [
        "share one new file between header excerpts that change to the same text",
        [HEADER, CONTRIBUTORS, CONTRIBUTORS],
        ["beta/include/beta.h.txt", "beta/include/beta_decode.h.txt", "beta/include/beta_decode.h.txt"],
    ],
    [
        "rewrite the shared excerpt in place when every header changes to the same text",
        [CONTRIBUTORS, CONTRIBUTORS, CONTRIBUTORS],
        ["beta/include/beta.h.txt", "beta/include/beta.h.txt", "beta/include/beta.h.txt"],
    ],
]) {
    test(`Should ${name}`, (t) => {
        const context = fixture(t);
        shareBetaExcerpt(context, comments);

        const result = context.run();

        assert.equal(result.status, 0, result.output);
        const { notices } = context.inventory().find((component) => component.id === "beta");
        assert.deepEqual(notices.map((notice) => notice.file), expected);
        for (const [index, comment] of comments.entries()) {
            assert.equal(notices[index].sha256, checksum(comment));
            assert.equal(context.read(`notices/${expected[index]}`), comment);
        }
        const validation = context.validate();
        assert.equal(validation.status, 0, validation.stderr);
    });
}

test("Should rewrite both texts when a component and the component sharing its file change together", (t) => {
    const { components, inventory, read, recipes, run, serve, serveRecipes, validate, writeInventory } = fixture(t);
    components.push({
        ...components[0],
        id: "gamma",
        repository: "https://github.com/example/gamma",
        recipe: "builder/scripts.d/50-gamma.sh",
        notices: [{ url: `${RAW}/example/gamma/${ALPHA_1}/LICENSE.md`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    serveRecipes(REVIEWED, new Map(recipes).set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_1]])));
    serveRecipes(LOCKED, new Map(recipes)
        .set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha"))
        .set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_2]])));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, "Alpha license v2\n");
    serve(`${RAW}/example/gamma/${ALPHA_1}/LICENSE.md`, LICENSE);
    serve(`${RAW}/example/gamma/${ALPHA_2}/LICENSE.md`, "Gamma license v2\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(read("notices/alpha/COPYING.txt"), "Alpha license v2\n");
    const gamma = inventory().find((component) => component.id === "gamma");
    assert.deepEqual(gamma.notices, [
        { url: `${RAW}/example/gamma/${ALPHA_2}/LICENSE.md`, sha256: checksum("Gamma license v2\n"), file: "gamma/LICENSE.md" },
    ]);
    assert.equal(read("notices/gamma/LICENSE.md"), "Gamma license v2\n");
    const validation = validate();
    assert.equal(validation.status, 0, validation.stderr);
});

test("Should rewrite a changed text in place when its reviewed file is not named after the upstream path", (t) => {
    const { components, inventory, read, recipes, run, serve, serveRecipes, validate, writeInventory } = fixture(t);
    components[0].notices[0].url = `${RAW}/example/alpha/${ALPHA_1}/trunk/alpha/COPYING`;
    writeInventory();
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    serve(`${RAW}/example/alpha/${ALPHA_1}/trunk/alpha/COPYING`, LICENSE);
    serve(`${RAW}/example/alpha/${ALPHA_2}/trunk/alpha/COPYING`, "Alpha license v2\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(inventory().find((component) => component.id === "alpha").notices[0].file, "alpha/COPYING.txt");
    assert.equal(read("notices/alpha/COPYING.txt"), "Alpha license v2\n");
    assert.equal(validate().status, 0);
});

test("Should move a changed text out of a file left behind by a removed component", (t) => {
    const { buildpack, components, inventory, read, recipes, run, serve, serveRecipes, validate, writeInventory } = fixture(t);
    components.push({
        ...components[0],
        id: "gamma",
        repository: "https://github.com/example/gamma",
        recipe: "builder/scripts.d/50-gamma.sh",
        notices: [{ url: `${RAW}/example/gamma/${ALPHA_1}/LICENSE.md`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    serveRecipes(REVIEWED, new Map(recipes).set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_1]])));
    const remaining = new Map(recipes).set("50-gamma.sh", recipe([["https://github.com/example/gamma", ALPHA_2]]));
    remaining.delete("50-alpha.sh");
    serveRecipes(LOCKED, remaining);
    serve(`${RAW}/example/gamma/${ALPHA_1}/LICENSE.md`, LICENSE);
    serve(`${RAW}/example/gamma/${ALPHA_2}/LICENSE.md`, "Gamma license v2\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(inventory().find((component) => component.id === "gamma").notices[0].file, "gamma/LICENSE.md");
    assert.equal(read("notices/gamma/LICENSE.md"), "Gamma license v2\n");
    assert.equal(fs.existsSync(path.join(buildpack, "notices/alpha")), false);
    assert.equal(validate().status, 0);
});

test("Should resolve secondary, dependency-file, submodule and toolchain pins from their recorded evidence", (t) => {
    const { components, inventory, recipes, run, serve, serveRecipes, writeInventory } = fixture(t);
    const child = (id, recipePath, revision, repository = `https://github.com/example/${id}`) => ({
        ...components[0],
        id,
        repository,
        revision,
        recipe: recipePath,
        notices: [{ url: `${repository.replace("https://github.com", RAW)}/${revision}/COPYING`, sha256: checksum(LICENSE), file: `${id}/COPYING.txt` }],
    });
    components.push(
        child("alpha-loader", "builder/scripts.d/50-alpha.sh", "3".repeat(40)),
        child("dep-tools", "alpha/DEPS", "4".repeat(40)),
        child("sub-github", "alpha/3rdparty/sub_github (gitlink)", "5".repeat(40)),
        child("sub-gitlab", "beta/3rdparty/sub_gitlab (gitlink)", "6".repeat(40)),
        child("gcc-runtime", "builder/images/base-linux64/ct-ng-config; builder/images/base-linuxarm64/ct-ng-config", "releases/gcc-15.2.0", "https://github.com/gcc-mirror/gcc"),
        child("glibc-startup", "builder/images/base-linux64/ct-ng-config; builder/images/base-linuxarm64/ct-ng-config", "glibc-2.28", "https://github.com/bminor/glibc"),
        { ...child("implib", "builder/images/base-linux64/Dockerfile", "7".repeat(40)), revisionEvidence: "notice-only" },
    );
    writeInventory();
    serveRecipes(LOCKED, new Map(recipes)
        .set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2], ["https://github.com/example/alpha-loader", "8".repeat(40)]], "--enable-libalpha"))
        .set("50-beta.sh", recipe([["https://gitlab.example/group/beta", "v2.0"]])));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, LICENSE);
    serve(`${RAW}/example/alpha-loader/${"3".repeat(40)}/COPYING`, LICENSE);
    serve(`${RAW}/example/alpha-loader/${"8".repeat(40)}/COPYING`, LICENSE);
    serve("https://gitlab.example/group/beta/-/raw/v2.0/include/beta.h", HEADER);
    serve(`${RAW}/example/alpha/${ALPHA_2}/DEPS`, `vars = {\n  'dep_tools_revision': '${"9".repeat(40)}',\n}\n`);
    serve(`${RAW}/example/dep-tools/${"4".repeat(40)}/COPYING`, LICENSE);
    serve(`${RAW}/example/dep-tools/${"9".repeat(40)}/COPYING`, LICENSE);
    serve(`${API}/example/alpha/contents/3rdparty/sub_github?ref=${ALPHA_2}`, { type: "submodule", sha: "b".repeat(40) });
    serve(`${RAW}/example/sub-github/${"5".repeat(40)}/COPYING`, LICENSE);
    serve(`${RAW}/example/sub-github/${"b".repeat(40)}/COPYING`, LICENSE);
    serve("https://gitlab.example/api/v4/projects/group%2Fbeta/repository/tree?path=3rdparty&ref=v2.0&per_page=100", [
        { type: "commit", path: "3rdparty/sub_gitlab", id: "d".repeat(40) },
    ]);
    serve(`${RAW}/example/sub-gitlab/${"6".repeat(40)}/COPYING`, LICENSE);
    serve(`${RAW}/example/sub-gitlab/${"d".repeat(40)}/COPYING`, LICENSE);
    for (const image of ["base-linux64", "base-linuxarm64"])
        serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/builder/images/${image}/ct-ng-config`, 'CT_GLIBC_VERSION="2.28"\nCT_GCC_VERSION="16.1.0"\n');
    serve(`${RAW}/gcc-mirror/gcc/releases/gcc-15.2.0/COPYING`, LICENSE);
    serve(`${RAW}/gcc-mirror/gcc/releases/gcc-16.1.0/COPYING`, LICENSE);

    const result = run("--dry-run");

    assert.equal(result.status, 0, result.output);
    for (const id of ["alpha", "alpha-loader", "beta", "dep-tools", "gcc-runtime", "sub-github", "sub-gitlab"])
        assert.match(result.output, new RegExp(`Pin moved, license text unchanged: ${id}\\b`));
    assert.doesNotMatch(result.output, /glibc-startup|implib/);
    assert.equal(inventory().find((component) => component.id === "alpha").revision, ALPHA_1, "dry run writes nothing");
});

test("Should add a newly pinned dependency that the binaries enable and ignore one they do not", (t) => {
    const { inventory, read, recipes, run, serve, serveRecipes, validate } = fixture(t);
    const delta = "f".repeat(40);
    serveRecipes(LOCKED, new Map(recipes)
        .set("50-delta.sh", recipe([["https://github.com/example/delta", delta]], "--enable-libdelta"))
        .set("50-windows-only.sh", recipe([["https://github.com/example/windows-only", delta]], "--enable-mediafoundation"))
        .set("45-helper.sh", recipe([["https://gitlab.example/group/helper", "v3"]])));
    serve(`${API}/example/delta/git/trees/${delta}?recursive=1`, {
        truncated: false,
        tree: ["LICENSE", "docs/AUTHORS", "src/deep/nested/third/LICENSE", "src/main.c"].map((file) => ({ path: file, type: "blob" })),
    });
    serve(`${API}/example/delta/license`, { license: { spdx_id: "BSD-3-Clause" } });
    serve(`${RAW}/example/delta/${delta}/LICENSE`, "Delta license\n");
    serve(`${RAW}/example/delta/${delta}/docs/AUTHORS`, "Delta authors\n");
    serve("https://gitlab.example/api/v4/projects/group%2Fhelper/repository/tree?ref=v3&per_page=100", [
        { type: "blob", path: "COPYING" },
        { type: "blob", path: "helper.c" },
    ]);
    serve("https://gitlab.example/group/helper/-/raw/v3/COPYING", "Helper license\n");

    const result = run();

    assert.equal(result.status, 0, result.output);
    const added = inventory().find((component) => component.id === "delta");
    assert.deepEqual(added, {
        id: "delta",
        repository: "https://github.com/example/delta",
        revision: delta,
        recipe: "builder/scripts.d/50-delta.sh",
        architectures: ["amd64", "arm64"],
        role: "Static library (--enable-libdelta).",
        notices: [
            { url: `${RAW}/example/delta/${delta}/LICENSE`, sha256: checksum("Delta license\n"), file: "delta/LICENSE.txt" },
            { url: `${RAW}/example/delta/${delta}/docs/AUTHORS`, sha256: checksum("Delta authors\n"), file: "delta/docs/AUTHORS.txt" },
        ],
        licenseExpression: "BSD-3-Clause",
        distribution: "runtime",
        revisionEvidence: "source",
    });
    const helper = inventory().find((component) => component.id === "helper");
    assert.equal(helper.role, "Static dependency built by builder/scripts.d/45-helper.sh.");
    assert.equal(helper.licenseExpression, "LicenseRef-helper");
    assert.equal(read("notices/helper/COPYING.txt"), "Helper license\n");
    assert.equal(inventory().some((component) => component.id === "windows-only"), false);
    assert.deepEqual(inventory().map((component) => component.id), ["alpha", "beta", "delta", "ffmpeg", "helper"]);
    assert.match(read("SOURCE.txt"), /delta \(amd64, arm64\)\n {2}Static library \(--enable-libdelta\)\./);
    assert.match(result.output, /Added component: delta/);
    assert.match(result.output, /Added component: helper/);
    assert.match(result.output, /Ignored recipe not enabled in either binary: builder\/scripts\.d\/50-windows-only\.sh/);
    assert.equal(validate().status, 0);
});

// Models 45-x11/30-libxcb.sh: reviewed, left out of the inventory, and switched on by a later release.
for (const [name, reviewedHasRecipe] of [
    ["is new since the review", false],
    ["already existed at the reviewed revision without being inventoried", true],
]) {
    test(`Should add a dependency the refreshed build configurations enable when its recipe ${name}`, (t) => {
        const { inventory, recipes, run, serve, serveRecipes, validate, write } = fixture(t);
        const pinned = "f".repeat(40);
        const enabled = recipe([["https://github.com/example/mfx", pinned]], "--enable-libmfx");
        if (reviewedHasRecipe) serveRecipes(REVIEWED, new Map(recipes).set("50-mfx.sh", `${enabled}ffbuild_enabled() {\n    return -1\n}\n`));
        serveRecipes(LOCKED, new Map(recipes).set("50-mfx.sh", enabled));
        serve(`${API}/example/mfx/git/trees/${pinned}?recursive=1`, { truncated: false, tree: [{ path: "LICENSE", type: "blob" }] });
        serve(`${API}/example/mfx/license`, { license: { spdx_id: "MIT" } });
        serve(`${RAW}/example/mfx/${pinned}/LICENSE`, "Mfx license\n");
        write("notices/buildconf-amd64.txt", "ffmpeg version 9.0.0-Jellyfin\n  configuration: --enable-gpl --enable-libalpha --enable-libmfx\n");

        const result = run();

        assert.equal(result.status, 0, result.output);
        assert.match(result.output, /Added component: mfx/);
        assert.match(result.output, /Inventory content changed/);
        assert.doesNotMatch(result.output, /Inventory content unchanged/);
        const mfx = inventory().find((component) => component.id === "mfx");
        assert.deepEqual(mfx.architectures, ["amd64"]);
        assert.equal(mfx.recipe, "builder/scripts.d/50-mfx.sh");
        assert.equal(validate().status, 0);
    });
}

for (const [name, flag] of [
    ["has no configure flag", undefined],
    ["has a configure flag that neither build configuration enables", "--enable-libxcb"],
]) {
    test(`Should report an unchanged inventory when a recipe the review left out ${name}`, (t) => {
        const { inventory, recipes, run, serveRecipes } = fixture(t);
        const leftOut = new Map(recipes).set("45-x11/30-libxcb.sh", recipe([["https://gitlab.example/xorg/libxcb", "v1.17"]], flag));
        serveRecipes(REVIEWED, leftOut);
        serveRecipes(LOCKED, leftOut);

        const result = run();

        assert.equal(result.status, 0, result.output);
        assert.match(result.output, /Inventory content unchanged/);
        assert.doesNotMatch(result.output, /libxcb/);
        assert.deepEqual(inventory().map((component) => component.id), ["alpha", "beta", "ffmpeg"]);
    });
}

// Models 20-libiconv.sh, which gained SCRIPT_REPO2 (gnulib) beside a pin that did not move.
function serveGainedPin({ serve }, pinned) {
    serve(`${API}/example/gained/git/trees/${pinned}?recursive=1`, { truncated: false, tree: [{ path: "COPYING", type: "blob" }] });
    serve(`${API}/example/gained/license`, { license: { spdx_id: "LGPL-2.1-or-later" } });
    serve(`${RAW}/example/gained/${pinned}/COPYING`, "Gained license\n");
}

test("Should propose a dependency that an inventoried recipe starts to pin", (t) => {
    const context = fixture(t);
    const { components, inventory, read, recipes, run, serveRecipes, validate } = context;
    const pinned = "8".repeat(40);
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_1], ["https://github.com/example/gained", pinned]], "--enable-libalpha")));
    serveGainedPin(context, pinned);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /Added component: gained/);
    assert.match(result.output, /Inventory content changed/);
    assert.doesNotMatch(result.output, /Inventory content unchanged/);
    assert.deepEqual(inventory().find((component) => component.id === "gained"), {
        id: "gained",
        repository: "https://github.com/example/gained",
        revision: pinned,
        recipe: "builder/scripts.d/50-alpha.sh",
        architectures: ["amd64", "arm64"],
        role: "Static library (--enable-libalpha).",
        notices: [{ url: `${RAW}/example/gained/${pinned}/COPYING`, sha256: checksum("Gained license\n"), file: "gained/COPYING.txt" }],
        licenseExpression: "LGPL-2.1-or-later",
        distribution: "runtime",
        revisionEvidence: "source",
    });
    assert.deepEqual(inventory().find((component) => component.id === "alpha"), components[0]);
    assert.equal(read("notices/gained/COPYING.txt"), "Gained license\n");
    assert.match(read("SOURCE.txt"), /gained \(amd64, arm64\)/);
    assert.equal(validate().status, 0);
});

test("Should give a dependency gained by a recipe without configure flags the architectures built from that recipe", (t) => {
    const context = fixture(t);
    const { inventory, recipes, run, serveRecipes } = context;
    const pinned = "8".repeat(40);
    serveRecipes(LOCKED, new Map(recipes).set("50-beta.sh", recipe([["https://gitlab.example/group/beta", "v1.0"], ["https://github.com/example/gained", pinned]])));
    serveGainedPin(context, pinned);

    const result = run();

    assert.equal(result.status, 0, result.output);
    const gained = inventory().find((component) => component.id === "gained");
    assert.deepEqual(gained.architectures, ["arm64"]);
    assert.equal(gained.role, "Static dependency built by builder/scripts.d/50-beta.sh.");
});

test("Should report an unchanged inventory when a recipe pins a repository that another recipe's component claims", (t) => {
    const { components, inventory, recipes, run, serve, serveRecipes, writeInventory } = fixture(t);
    components.push({
        ...components[0],
        id: "gamma-headers",
        repository: "https://github.com/example/gamma-headers",
        recipe: "builder/scripts.d/45-gamma-headers.sh",
        notices: [{ url: `${RAW}/example/gamma-headers/${ALPHA_1}/LICENSE.md`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    const pinned = new Map(recipes)
        .set("45-gamma-headers.sh", recipe([["https://github.com/example/gamma-headers", ALPHA_1]]))
        .set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_1], ["https://github.com/example/gamma-headers", "v1.4"]], "--enable-libalpha"));
    serveRecipes(REVIEWED, pinned);
    serveRecipes(LOCKED, pinned);
    serve(`${RAW}/example/gamma-headers/${ALPHA_1}/LICENSE.md`, LICENSE);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /Inventory content unchanged/);
    assert.deepEqual(inventory().map((component) => component.id), ["alpha", "beta", "ffmpeg", "gamma-headers"]);
});

const regrouped = (recipes, from, to) => {
    const moved = new Map(recipes).set(to, recipes.get(from));
    moved.delete(from);
    return moved;
};

// Models 50-vulkan/50-shaderc.sh -> 47-vulkan/50-shaderc.sh, whose DEPS file pins glslang and spirv-tools.
test("Should follow a regrouped recipe and keep the dependencies resolved through its component", (t) => {
    const { buildpack, components, inventory, read, recipes, run, serve, serveRecipes, validate, write, writeInventory } = fixture(t);
    const tools = "4".repeat(40);
    components.push({
        ...components[0],
        id: "dep-tools",
        repository: "https://github.com/example/dep-tools",
        revision: tools,
        recipe: "alpha/DEPS",
        notices: [{ url: `${RAW}/example/dep-tools/${tools}/COPYING`, sha256: checksum(LICENSE), file: "dep-tools/COPYING.txt" }],
    });
    writeInventory();
    write("notices/dep-tools/COPYING.txt", LICENSE);
    serveRecipes(LOCKED, regrouped(recipes, "50-alpha.sh", "47-group/50-alpha.sh"));
    serve(`${RAW}/example/alpha/${ALPHA_1}/DEPS`, `vars = {\n  'dep_tools_revision': '${tools}',\n}\n`);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /Recipe moved: alpha \(builder\/scripts\.d\/50-alpha\.sh -> builder\/scripts\.d\/47-group\/50-alpha\.sh\)/);
    assert.doesNotMatch(result.output, /(Added|Removed) component/);
    assert.match(result.output, /Inventory content changed/);
    assert.deepEqual(inventory(), [
        { ...components[0], recipe: "builder/scripts.d/47-group/50-alpha.sh" },
        components[1],
        components[3],
        { ...components[2], revision: LOCKED, notices: [{ ...components[2].notices[0], url: `${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/LICENSE.md` }] },
    ]);
    assert.equal(fs.existsSync(path.join(buildpack, "notices/dep-tools/COPYING.txt")), true);
    assert.match(read("SOURCE.txt"), /Recipe: builder\/scripts\.d\/47-group\/50-alpha\.sh/);
    assert.equal(validate().status, 0);
});

// Models 45-libNE10.sh -> 45-libne10.sh: an arm64-only entry whose role and excerpt a person wrote.
test("Should keep the reviewed entry of a component when upstream only renames its recipe", (t) => {
    const { components, inventory, read, recipes, run, serveRecipes, validate } = fixture(t);
    serveRecipes(LOCKED, regrouped(recipes, "50-beta.sh", "50-Beta.sh"));

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.deepEqual(inventory().find((component) => component.id === "beta"), { ...components[1], recipe: "builder/scripts.d/50-Beta.sh" });
    assert.equal(read("notices/beta/include/beta.h.txt"), HEADER);
    assert.match(result.output, /Recipe moved: beta/);
    assert.equal(validate().status, 0);
});

// Models 45-opencl.sh, which pins the headers first and the loader second.
test("Should follow a regrouped recipe for every component built from it and still follow their pins", (t) => {
    const { components, inventory, recipes, run, serve, serveRecipes, writeInventory } = fixture(t);
    const loader = "3".repeat(40);
    components.push({
        ...components[0],
        id: "alpha-loader",
        repository: "https://github.com/example/alpha-loader",
        revision: loader,
        notices: [{ url: `${RAW}/example/alpha-loader/${loader}/COPYING`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    const pins = (revision) => recipe([["https://github.com/example/alpha", revision], ["https://github.com/example/alpha-loader", loader]], "--enable-libalpha");
    serveRecipes(REVIEWED, new Map(recipes).set("50-alpha.sh", pins(ALPHA_1)));
    serveRecipes(LOCKED, regrouped(new Map(recipes).set("50-alpha.sh", pins(ALPHA_2)), "50-alpha.sh", "45-alpha.sh"));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, LICENSE);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /Recipe moved: alpha \(/);
    assert.match(result.output, /Recipe moved: alpha-loader \(/);
    assert.match(result.output, /Pin moved, license text unchanged: alpha\n/);
    assert.deepEqual(
        inventory().map(({ id, recipe: built, revision }) => [id, built, revision]).filter(([id]) => id.startsWith("alpha")),
        [["alpha", "builder/scripts.d/45-alpha.sh", ALPHA_2], ["alpha-loader", "builder/scripts.d/45-alpha.sh", loader]],
    );
});

// Models 45-vulkan-headers.sh: the loader's recipe pins the same repository second.
test("Should follow a regrouped recipe to the recipe that pins the repository first", (t) => {
    const { components, inventory, recipes, run, serve, serveRecipes, writeInventory } = fixture(t);
    components.push({
        ...components[0],
        id: "gamma-headers",
        repository: "https://github.com/example/gamma-headers",
        recipe: "builder/scripts.d/50-group/45-gamma-headers.sh",
        notices: [{ url: `${RAW}/example/gamma-headers/${ALPHA_1}/LICENSE.md`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" }],
    });
    writeInventory();
    const pinned = new Map(recipes)
        .set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_1], ["https://github.com/example/gamma-headers", "v1.4"]], "--enable-libalpha"))
        .set("50-group/45-gamma-headers.sh", recipe([["https://github.com/example/gamma-headers", ALPHA_1]]));
    serveRecipes(REVIEWED, pinned);
    serveRecipes(LOCKED, regrouped(pinned, "50-group/45-gamma-headers.sh", "47-group/45-gamma-headers.sh"));
    serve(`${RAW}/example/gamma-headers/${ALPHA_1}/LICENSE.md`, LICENSE);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.equal(inventory().find((component) => component.id === "gamma-headers").recipe, "builder/scripts.d/47-group/45-gamma-headers.sh");
    assert.doesNotMatch(result.output, /(Added|Removed) component|Pin moved/);
});

test("Should remove a component and its unshared files when upstream drops the recipe", (t) => {
    const { buildpack, inventory, read, recipes, run, serveRecipes, validate } = fixture(t);
    const remaining = new Map(recipes);
    remaining.delete("50-beta.sh");
    serveRecipes(LOCKED, remaining);

    const result = run();

    assert.equal(result.status, 0, result.output);
    assert.deepEqual(inventory().map((component) => component.id), ["alpha", "ffmpeg"]);
    assert.equal(fs.existsSync(path.join(buildpack, "notices/beta")), false);
    assert.doesNotMatch(read("SOURCE.txt"), /beta \(arm64\)/);
    assert.match(result.output, /Removed component: beta/);
    assert.equal(validate().status, 0);
});

function changeAlphaLicense({ recipes, serve, serveRecipes }) {
    serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
    serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, "Alpha license v2\n");
}

for (const [name, args, arrange, firstVerdict] of [
    ["reported again with --dry-run", ["--dry-run"], changeAlphaLicense, /^Inventory content changed/m],
    ["regenerated a second time", [], changeAlphaLicense, /^Inventory content changed/m],
    ["reported again after a run that moved only the FFmpeg revision", ["--dry-run"], () => {}, /^Inventory content unchanged/m],
]) {
    test(`Should not call a regenerated inventory unchanged while the manifest still binds the reviewed release when it is ${name}`, (t) => {
        const context = fixture(t);
        const { read, run } = context;
        const inputs = ["notices/sources.json", "SOURCE.txt", "notices/alpha/COPYING.txt", "notices/manifest"];
        const manifest = read("notices/manifest");
        arrange(context);
        const first = run();
        assert.equal(first.status, 0, first.output);
        assert.match(first.output, firstVerdict);
        const regenerated = inputs.map(read);

        const second = run(...args);

        assert.equal(second.status, 0, second.output);
        assert.equal(read("notices/manifest"), manifest, "the regenerated inventory is still unreviewed");
        assert.deepEqual(inputs.map(read), regenerated, "the second run changes nothing");
        assert.doesNotMatch(second.output, /Inventory content unchanged|was rebound/);
        assert.match(second.output.trimEnd().split("\n").at(-1), /^Inventory content was not compared with the review of v8\.1\.2-4: /);
    });
}

test("Should not claim to have rebound anything when it only reports an unchanged inventory", (t) => {
    const { run } = fixture(t);

    const result = run("--dry-run");

    assert.equal(result.status, 0, result.output);
    assert.equal(result.output.trimEnd().split("\n").at(-1), "Inventory content unchanged since the review of v8.1.2-4; nothing but the FFmpeg revision differs.");
});

for (const [name, arrange, message] of [
    [
        "a license file is missing at the new pin",
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]]))),
        /Unable to fetch .*\/example\/alpha\/2{40}\/COPYING/,
    ],
    [
        "the reviewed text cannot be reproduced from upstream",
        ({ recipes, serve, serveRecipes }) => {
            serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]])));
            serve(`${RAW}/example/alpha/${ALPHA_1}/COPYING`, "Some other text\n");
            serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, "Alpha license v2\n");
        },
        /No known extraction reproduces alpha\/COPYING\.txt/,
    ],
    [
        "a recipe replaces the recorded repository with more than one candidate",
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/renamed", ALPHA_2], ["https://github.com/example/other", ALPHA_2]]))),
        /builder\/scripts\.d\/50-alpha\.sh no longer pins https:\/\/github\.com\/example\/alpha/,
    ],
    [
        "the recipe listing is truncated",
        ({ serve }) => serve(`${API}/jellyfin/jellyfin-ffmpeg/git/trees/${LOCKED}:builder/scripts.d?recursive=1`, { truncated: true, tree: [] }),
        /recipe listing .* is truncated/,
    ],
    [
        "a component records evidence the tool cannot follow",
        ({ components, writeInventory }) => {
            components[0].recipe = "somewhere/else.txt";
            writeInventory();
        },
        /Cannot resolve the upstream pin of alpha from "somewhere\/else\.txt"/,
    ],
    [
        "a component names a parent the inventory does not contain",
        ({ components, writeInventory }) => {
            components[0].recipe = "missing/DEPS";
            writeInventory();
        },
        /Cannot resolve the upstream pin of alpha from "missing\/DEPS"/,
    ],
    [
        "the toolchain images disagree about a version",
        ({ components, serve, writeInventory }) => {
            components[0] = { ...components[0], id: "gcc-runtime", recipe: "builder/images/base-linux64/ct-ng-config; builder/images/base-linuxarm64/ct-ng-config" };
            writeInventory();
            serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/builder/images/base-linux64/ct-ng-config`, 'CT_GCC_VERSION="15.2.0"\n');
            serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/builder/images/base-linuxarm64/ct-ng-config`, 'CT_GCC_VERSION="16.1.0"\n');
        },
        /Toolchain images disagree about CT_GCC_VERSION: 15\.2\.0, 16\.1\.0/,
    ],
    [
        "upstream names a license file the generator would refuse to read",
        ({ recipes, serve, serveRecipes }) => {
            serveRecipes(LOCKED, new Map(recipes).set("50-delta.sh", recipe([["https://github.com/example/delta", ALPHA_2]])));
            serve(`${API}/example/delta/git/trees/${ALPHA_2}?recursive=1`, { truncated: false, tree: [{ path: "LICENSE (old).txt", type: "blob" }] });
            serve(`${RAW}/example/delta/${ALPHA_2}/LICENSE (old).txt`, LICENSE);
        },
        /Unsafe notice path from upstream: delta\/LICENSE \(old\)\.txt/,
    ],
    [
        "a changed text would replace a reviewed text that another notice still uses",
        ({ components, recipes, serve, serveRecipes, write, writeInventory }) => {
            components[0].notices.push(
                { url: `${RAW}/example/alpha/${ALPHA_1}/src/LICENSE.BSD`, sha256: checksum(LICENSE), file: "alpha/COPYING.txt" },
                { url: `${RAW}/example/alpha/${ALPHA_1}/NOTICE`, sha256: checksum("Alpha notice\n"), file: "alpha/src/LICENSE.BSD.txt" },
            );
            writeInventory();
            write("notices/alpha/src/LICENSE.BSD.txt", "Alpha notice\n");
            serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_2]], "--enable-libalpha")));
            serve(`${RAW}/example/alpha/${ALPHA_1}/src/LICENSE.BSD`, LICENSE);
            serve(`${RAW}/example/alpha/${ALPHA_2}/COPYING`, LICENSE);
            serve(`${RAW}/example/alpha/${ALPHA_2}/src/LICENSE.BSD`, "Alpha license 2027\n");
            serve(`${RAW}/example/alpha/${ALPHA_2}/NOTICE`, "Alpha notice\n");
        },
        /notices\/alpha\/src\/LICENSE\.BSD\.txt would not hold the text recorded for alpha/,
    ],
    [
        "a recipe is regrouped and swaps its repository in the same release",
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, regrouped(new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/renamed", ALPHA_1]], "--enable-libalpha")), "50-alpha.sh", "47-group/50-Alpha.sh")),
        /builder\/scripts\.d\/47-group\/50-Alpha\.sh pins https:\/\/github\.com\/example\/renamed, whose id alpha belongs to a reviewed component/,
    ],
    [
        "a regrouped recipe leaves several recipes pinning the repository",
        ({ recipes, serveRecipes }) => {
            const moved = regrouped(recipes, "50-alpha.sh", "47-group/50-alpha.sh");
            serveRecipes(LOCKED, moved.set("48-group/50-alpha.sh", moved.get("47-group/50-alpha.sh")));
        },
        /builder\/scripts\.d\/50-alpha\.sh is gone and several recipes pin https:\/\/github\.com\/example\/alpha: builder\/scripts\.d\/47-group\/50-alpha\.sh, builder\/scripts\.d\/48-group\/50-alpha\.sh/,
    ],
    [
        "a gained pin would take the id of a reviewed component",
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", ALPHA_1], ["https://github.com/fork/Beta", ALPHA_2]], "--enable-libalpha"))),
        /builder\/scripts\.d\/50-alpha\.sh pins https:\/\/github\.com\/fork\/Beta, whose id beta belongs to a reviewed component/,
    ],
    [
        "a new dependency is hosted where license files cannot be listed",
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-epsilon.sh", recipe([["https://svn.code.sf.net/p/epsilon/svn", "42"]]))),
        /Cannot list license files of new component epsilon/,
    ],
]) {
    test(`Should change nothing and fail when ${name}`, (t) => {
        const context = fixture(t);
        const inputs = ["notices/sources.json", "SOURCE.txt", "notices/alpha/COPYING.txt"];
        arrange(context);
        const arranged = inputs.map(context.read);

        const result = context.run();

        assert.equal(result.status, 1, result.output);
        assert.match(result.output, message);
        assert.deepEqual(inputs.map(context.read), arranged);
    });
}

const UNCHANGED = "Inventory content unchanged since the review of v8.1.2-4; nothing but the FFmpeg revision differs.";
const SPANNING = `\n${UNCHANGED}\n::error title=forged::annotation from upstream\n`;
const TOOLCHAIN_IMAGES = "builder/images/base-linux64/ct-ng-config; builder/images/base-linuxarm64/ct-ng-config";

for (const [name, status, arrange] of [
    [
        "a toolchain version",
        1,
        ({ components, serve, writeInventory }) => {
            components[0] = { ...components[0], id: "gcc-runtime", recipe: TOOLCHAIN_IMAGES };
            writeInventory();
            serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/builder/images/base-linux64/ct-ng-config`, `CT_GCC_VERSION="15.2.0${SPANNING}"\n`);
            serve(`${RAW}/jellyfin/jellyfin-ffmpeg/${LOCKED}/builder/images/base-linuxarm64/ct-ng-config`, 'CT_GCC_VERSION="15.2.0"\n');
        },
    ],
    [
        "the repository of a reviewed recipe",
        1,
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([[`https://evil.example/alpha${SPANNING}`, ALPHA_2]], "--enable-libalpha"))),
    ],
    [
        "the revision of a reviewed recipe",
        1,
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-alpha.sh", recipe([["https://github.com/example/alpha", `${ALPHA_2}${SPANNING}`]], "--enable-libalpha"))),
    ],
    [
        "the repository of a new recipe",
        1,
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set("50-epsilon.sh", recipe([[`https://svn.code.sf.net/p/epsilon/svn${SPANNING}`, "42"]]))),
    ],
    [
        "a dependency-file revision",
        1,
        ({ components, serve, writeInventory }) => {
            components.push({
                ...components[0],
                id: "dep-tools",
                repository: "https://github.com/example/dep-tools",
                revision: "4".repeat(40),
                recipe: "alpha/DEPS",
                notices: [{ url: `${RAW}/example/dep-tools/${"4".repeat(40)}/COPYING`, sha256: checksum(LICENSE), file: "dep-tools/COPYING.txt" }],
            });
            writeInventory();
            serve(`${RAW}/example/alpha/${ALPHA_1}/DEPS`, `vars = {\n  'dep_tools_revision': '${"9".repeat(40)}${SPANNING}',\n}\n`);
        },
    ],
    [
        "the malformed file listing of a new dependency's host",
        1,
        ({ recipes, serve, serveRecipes }) => {
            serveRecipes(LOCKED, new Map(recipes).set("45-helper.sh", recipe([["https://gitlab.example/group/helper", "v3"]])));
            serve("https://gitlab.example/api/v4/projects/group%2Fhelper/repository/tree?ref=v3&per_page=100", "a\n::error::forged");
        },
    ],
    [
        "the path of a recipe that is not in the binaries",
        0,
        ({ recipes, serveRecipes }) => serveRecipes(LOCKED, new Map(recipes).set(`50-windows ${UNCHANGED} \u009b2J.sh`, recipe([["https://github.com/example/windows-only", ALPHA_2]], "--enable-mediafoundation"))),
    ],
]) {
    test(`Should state its verdict only on its own closing line when ${name} from upstream spans lines`, (t) => {
        const context = fixture(t);
        arrange(context);

        const result = context.run();

        assert.equal(result.status, status, result.output);
        const lines = result.output.trimEnd().split("\n");
        assert.deepEqual(lines.filter((line) => line.startsWith("Inventory content")), status === 0 ? [lines.at(-1)] : [], result.output);
        assert.deepEqual(lines.filter((line) => line.startsWith("::")), [], result.output);
        assert.doesNotMatch(lines.join(""), /[\p{Cc}\p{Zl}\p{Zp}]/u, "control characters from upstream are neutralised");
        assert.match(result.output, /\\u000a::error|\\u2028Inventory content unchanged.*\\u009b2J/, "the value is still reported");
    });
}
