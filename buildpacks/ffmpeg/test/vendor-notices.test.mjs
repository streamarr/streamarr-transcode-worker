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
