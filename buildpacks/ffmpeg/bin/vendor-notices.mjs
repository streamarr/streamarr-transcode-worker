import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { parseArgs, promisify } from "node:util";

const { values } = parseArgs({
    options: { root: { type: "string" }, "dry-run": { type: "boolean" } },
});
const root = path.resolve(
    values.root ?? fileURLToPath(new URL("..", import.meta.url)),
);
const httpLibrary = fileURLToPath(new URL("../lib/http.sh", import.meta.url));
const run = promisify(execFile);
const FFMPEG_RAW = "https://raw.githubusercontent.com/jellyfin/jellyfin-ffmpeg";
const FFMPEG_API = "https://api.github.com/repos/jellyfin/jellyfin-ffmpeg";
const INDEX = "Component source index\n======================\n\n";
const RECIPE = /^builder\/scripts\.d\/[^;]+\.sh$/;
const DEPENDENCY_FILE = /^([a-z0-9-]+)\/DEPS$/;
const SUBMODULE = /^([a-z0-9-]+)\/(.+) \(gitlink\)$/;
const LICENSE_FILE =
    /(^|\/)(copying|copyright|licen[cs]e|notice|authors|patents)[^/]*$/i;
const TOOLCHAIN = {
    "gcc-runtime": { key: "CT_GCC_VERSION", prefix: "releases/gcc-" },
    "glibc-startup": { key: "CT_GLIBC_VERSION", prefix: "glibc-" },
};
const comments = (text) =>
    [...text.matchAll(/\/\*[\s\S]*?\*\//g)].map((match) => match[0]);
// Every reviewed text is one of these views of its origin; a new pin reuses the view that
// reproduces the reviewed bytes, so excerpts and newline handling never depend on memory.
const EXTRACTIONS = [
    (text) => text,
    (text) => text.replace(/\r\n/g, "\n"),
    (text) => `${text.match(/^\/\*[\s\S]*?\*\//)?.[0] ?? ""}\n`,
    (text) =>
        `${comments(text)
            .slice(0, 4)
            .filter((comment) => /copyright|license|permission/i.test(comment))
            .join("\n\n")}\n`,
    (text) => text.split("/** @file")[0],
];

const checksum = (text) => createHash("sha256").update(text).digest("hex");
const hasText = (notice) => Object.hasOwn(notice, "text");
const normalize = (repository) => repository.replace(/\.git$|\/$/g, "");
const keyValues = (text) =>
    Object.fromEntries(
        text
            .trim()
            .split("\n")
            .map((line) => line.split("=")),
    );
const downloads = new Map();
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "ffmpeg-vendor-"));
process.on("exit", () =>
    fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 3 }),
);

function download(url) {
    if (!downloads.has(url)) downloads.set(url, request(url));
    return downloads.get(url);
}

// ffmpeg_curl repeats a transfer that failed part-way; only a destination file, which every
// attempt truncates, keeps the bytes of the failed attempt out of the body.
async function request(url) {
    const command = url.startsWith("https://api.github.com/")
        ? 'ffmpeg_github_api_get "$2" "$3"'
        : 'ffmpeg_curl --fail --location --proto =https --proto-redir =https --silent --show-error --max-time 60 "$2" --output "$3"';
    const body = path.join(scratch, checksum(url));
    try {
        await run("bash", ["-c", `. "$1"; ${command}`, "--", httpLibrary, url, body]);
        const text = new TextDecoder().decode(fs.readFileSync(body));
        return url.includes("format=TEXT")
            ? Buffer.from(text.trim(), "base64").toString()
            : text;
    } catch (error) {
        throw new Error(
            `Unable to fetch ${url}: ${String(error.stderr ?? error.message).trim()}`,
        );
    }
}

const json = async (url) => JSON.parse(await download(url));

function rawUrl(repository, revision, file) {
    if (repository.startsWith("https://github.com/"))
        return `${repository.replace("https://github.com", "https://raw.githubusercontent.com")}/${revision}/${file}`;
    if (repository.includes("googlesource.com"))
        return `${repository}/+/${revision}/${file}?format=TEXT`;
    if (repository.includes("bitbucket.org"))
        return `${repository}/raw/${revision}/${file}`;
    return `${repository}/-/raw/${revision}/${file}`;
}

function gitlabApi(repository) {
    const { origin, pathname } = new URL(repository);
    return `${origin}/api/v4/projects/${encodeURIComponent(pathname.slice(1))}/repository/tree`;
}

async function recipePaths(revision) {
    const listing = await json(
        `${FFMPEG_API}/git/trees/${revision}:builder/scripts.d?recursive=1`,
    );
    if (listing.truncated)
        throw new Error(`The recipe listing of ${revision} is truncated`);
    return listing.tree
        .filter((entry) => entry.type === "blob" && entry.path.endsWith(".sh"))
        .map((entry) => `builder/scripts.d/${entry.path}`);
}

function recipePins(text) {
    return [...text.matchAll(/^SCRIPT_REPO(\d*)="([^"]+)"/gm)].map(
        ([, suffix, repository]) => ({
            repository: normalize(repository),
            revision: text.match(
                new RegExp(`^SCRIPT_(?:COMMIT|REV)${suffix}="([^"]+)"`, "m"),
            )?.[1],
        }),
    );
}

async function submodulePin(parent, revision, submodule) {
    if (parent.repository.startsWith("https://github.com/")) {
        const repository = parent.repository.replace(
            "https://github.com",
            "https://api.github.com/repos",
        );
        return (await json(`${repository}/contents/${submodule}?ref=${revision}`))
            .sha;
    }
    const entries = await json(
        `${gitlabApi(parent.repository)}?path=${path.dirname(submodule)}&ref=${revision}&per_page=100`,
    );
    return entries.find(
        (entry) => entry.type === "commit" && entry.path === submodule,
    )?.id;
}

async function toolchainPin(component, locked) {
    const { key, prefix } = TOOLCHAIN[component.id];
    const versions = await Promise.all(
        component.recipe.split("; ").map(async (file) => {
            const config = await download(`${FFMPEG_RAW}/${locked}/${file}`);
            return config.match(new RegExp(`^${key}="([^"]+)"`, "m"))?.[1];
        }),
    );
    if (!versions[0] || new Set(versions).size !== 1)
        throw new Error(
            `Toolchain images disagree about ${key}: ${versions.join(", ")}`,
        );
    return prefix + versions[0];
}

// A recipe that swaps a dependency's mirror leaves exactly one pin no component claims.
function recipePin(component, context) {
    const pins = recipePins(context.recipes.get(component.recipe));
    const pin = pins.find(
        (candidate) => candidate.repository === normalize(component.repository),
    );
    if (pin?.revision) return pin;
    const claimed = context.inventory
        .filter((other) => other.recipe === component.recipe)
        .map((other) => normalize(other.repository));
    const unclaimed = pins.filter(
        (candidate) =>
            candidate.revision && !claimed.includes(candidate.repository),
    );
    if (unclaimed.length !== 1)
        throw new Error(
            `${component.recipe} no longer pins ${component.repository}`,
        );
    return unclaimed[0];
}

// Returns what upstream now builds, or undefined when the component is gone.
async function upstreamPin(component, context) {
    if (
        component.revisionEvidence !== "notice-only" &&
        RECIPE.test(component.recipe)
    ) {
        return context.recipes.has(component.recipe)
            ? recipePin(component, context)
            : undefined;
    }
    const revision = await upstreamRevision(component, context);
    return revision && { repository: normalize(component.repository), revision };
}

async function upstreamRevision(component, context) {
    if (component.id === "ffmpeg") return context.locked;
    if (component.revisionEvidence === "notice-only") return component.revision;
    if (TOOLCHAIN[component.id]) return toolchainPin(component, context.locked);
    const [, parentId, submodule] =
        component.recipe.match(SUBMODULE) ??
        component.recipe.match(DEPENDENCY_FILE) ??
        [];
    if (!context.inventory.some((candidate) => candidate.id === parentId))
        throw new Error(
            `Cannot resolve the upstream pin of ${component.id} from "${component.recipe}"`,
        );
    const parent = context.pinned.get(parentId);
    if (!parent) return undefined;
    if (submodule) return submodulePin(parent, parent.revision, submodule);
    const dependencies = await download(
        rawUrl(parent.repository, parent.revision, "DEPS"),
    );
    return dependencies.match(
        new RegExp(
            `'${component.id.replaceAll("-", "_")}_revision':\\s*'([^']+)'`,
        ),
    )?.[1];
}

const upstreamFile = (url, revision) =>
    url.split(`${revision}/`)[1].split("?")[0];

// Upstream names the file, so it must satisfy the generator's input-path rule before use.
function ownFile(component, url, revision) {
    const file = `${component.id}/${upstreamFile(url, revision)}`;
    if (
        !/^[a-zA-Z0-9_+./-]+$/.test(file) ||
        file.split("/").some((part) => ["", ".", ".."].includes(part))
    )
        throw new Error(`Unsafe notice path from upstream: ${file}`);
    return /\.(txt|md)$/i.test(file) ? file : `${file}.txt`;
}

async function repin(component, pin) {
    const { repository, revision } = pin;
    const relocated = repository !== normalize(component.repository);
    const notices = await Promise.all(
        component.notices.map(async (notice) => {
            const url = relocated
                ? rawUrl(
                      repository,
                      revision,
                      upstreamFile(notice.url, component.revision),
                  )
                : notice.url.replaceAll(component.revision, revision);
            if (url === notice.url)
                throw new Error(
                    `Notice URL of ${component.id} does not name revision ${component.revision}`,
                );
            const text = await download(url);
            if (
                EXTRACTIONS.some(
                    (extract) => checksum(extract(text)) === notice.sha256,
                )
            )
                return { ...notice, url };
            const reviewed = await download(notice.url);
            const extract = EXTRACTIONS.find(
                (candidate) => checksum(candidate(reviewed)) === notice.sha256,
            );
            if (!extract)
                throw new Error(
                    `No known extraction reproduces ${notice.file} from ${notice.url}`,
                );
            return {
                ...notice,
                url,
                sha256: checksum(extract(text)),
                text: extract(text),
            };
        }),
    );
    return { ...component, repository, revision, notices };
}

async function licenseFiles(repository, revision) {
    if (repository.startsWith("https://github.com/")) {
        const listing = await json(
            `${repository.replace("https://github.com", "https://api.github.com/repos")}/git/trees/${revision}?recursive=1`,
        );
        if (listing.truncated)
            throw new Error(`The file listing of ${repository} is truncated`);
        return listing.tree
            .filter((entry) => entry.type === "blob")
            .map((entry) => entry.path)
            .filter((file) => file.split("/").length < 4);
    }
    const entries = await json(
        `${gitlabApi(repository)}?ref=${revision}&per_page=100`,
    );
    return entries
        .filter((entry) => entry.type === "blob")
        .map((entry) => entry.path);
}

async function licenseExpression(id, repository) {
    if (!repository.startsWith("https://github.com/"))
        return `LicenseRef-${id}`;
    try {
        const detected = await json(
            `${repository.replace("https://github.com", "https://api.github.com/repos")}/license`,
        );
        const identifier = detected.license?.spdx_id;
        return identifier && identifier !== "NOASSERTION"
            ? identifier
            : `LicenseRef-${id}`;
    } catch {
        return `LicenseRef-${id}`;
    }
}

// A recipe that enables nothing in either reviewed build configuration is not in the binaries.
async function newComponents(recipe, text, buildconf) {
    const flags = [...text.matchAll(/^\s*echo ((?:--enable-[a-z0-9-]+ ?)+)$/gm)]
        .flatMap((match) => match[1].trim().split(" "));
    const architectures = Object.keys(buildconf).filter(
        (architecture) =>
            !flags.length ||
            flags.some((flag) =>
                buildconf[architecture].split(/\s+/).includes(flag),
            ),
    );
    if (!architectures.length) return [];
    return Promise.all(
        recipePins(text).map(async ({ repository, revision }, index) => {
            const id =
                index === 0
                    ? path.basename(recipe, ".sh").replace(/^\d+-/, "")
                    : path.basename(repository).toLowerCase().replace(/_/g, "-");
            let files;
            try {
                files = await licenseFiles(repository, revision);
            } catch (error) {
                throw new Error(
                    `Cannot list license files of new component ${id} (${repository}): ${error.message}`,
                );
            }
            const component = { id };
            const notices = await Promise.all(
                files
                    .filter((file) => LICENSE_FILE.test(file))
                    .sort()
                    .map(async (file) => {
                        const url = rawUrl(repository, revision, file);
                        const text = await download(url);
                        return {
                            url,
                            sha256: checksum(text),
                            file: ownFile(component, url, revision),
                            text,
                        };
                    }),
            );
            if (!notices.length)
                throw new Error(`No license files found for new component ${id}`);
            return {
                id,
                repository,
                revision,
                recipe,
                architectures,
                role: flags.length
                    ? `Static library (${flags.join(" ")}).`
                    : `Static dependency built by ${recipe}.`,
                notices,
                licenseExpression: await licenseExpression(id, repository),
                distribution: "runtime",
                revisionEvidence: "source",
            };
        }),
    );
}

function sourceAccess(source, components, replacements) {
    if (!source.includes(INDEX))
        throw new Error("SOURCE.txt has no component source index");
    let prose = source.slice(0, source.indexOf(INDEX));
    for (const [reviewed, locked] of replacements) {
        const token = reviewed.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
        prose = prose.replace(
            new RegExp(`(?<![0-9A-Za-z])${token}(?![0-9A-Za-z])`, "g"),
            locked,
        );
    }
    const index = components.map(
        (component) =>
            `${component.id} (${component.architectures.join(", ")})\n  ${component.role}\n` +
            `  Repository: ${component.repository}\n  Revision: ${component.revision}\n` +
            `  Recipe: ${component.recipe}\n  Notices: see notices/sources.json (${component.id})\n` +
            (component.version_note ? `  ${component.version_note}\n` : ""),
    );
    return prose + INDEX + index.join("\n");
}

function noticeFiles(directory, prefix = "") {
    return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) =>
        entry.isDirectory()
            ? noticeFiles(
                  path.join(directory, entry.name),
                  `${prefix}${entry.name}/`,
              )
            : prefix
              ? [`${prefix}${entry.name}`]
              : [],
    );
}

async function main() {
    const lock = keyValues(fs.readFileSync(path.join(root, "ffmpeg.lock"), "utf8"));
    const manifest = keyValues(
        fs.readFileSync(path.join(root, "notices/manifest"), "utf8"),
    );
    const inventory = JSON.parse(
        fs.readFileSync(path.join(root, "notices/sources.json"), "utf8"),
    );
    const buildconf = Object.fromEntries(
        ["amd64", "arm64"].map((architecture) => [
            architecture,
            fs.readFileSync(
                path.join(root, `notices/buildconf-${architecture}.txt`),
                "utf8",
            ),
        ]),
    );
    const locked = lock.source_revision;
    const [reviewedPaths, lockedPaths] = await Promise.all([
        recipePaths(manifest.source_revision),
        recipePaths(locked),
    ]);
    const recipes = new Map(
        await Promise.all(
            lockedPaths.map(async (file) => [
                file,
                await download(`${FFMPEG_RAW}/${locked}/${file}`),
            ]),
        ),
    );

    const context = { locked, recipes, inventory, pinned: new Map() };
    const report = {
        moved: [],
        relocated: [],
        changed: [],
        added: [],
        removed: [],
        ignored: [],
    };
    const derived = (component) =>
        SUBMODULE.test(component.recipe) || DEPENDENCY_FILE.test(component.recipe);
    const components = [];
    for (const component of [
        ...inventory.filter((candidate) => !derived(candidate)),
        ...inventory.filter(derived),
    ]) {
        const pin = await upstreamPin(component, context);
        if (!pin) {
            report.removed.push(component.id);
            continue;
        }
        const relocated = pin.repository !== normalize(component.repository);
        if (!relocated && pin.revision === component.revision) {
            context.pinned.set(component.id, component);
            components.push(component);
            continue;
        }
        const repinned = await repin(component, pin);
        context.pinned.set(component.id, repinned);
        components.push(repinned);
        if (relocated)
            report.relocated.push(
                `${component.id} (${component.repository} -> ${pin.repository})`,
            );
        if (
            !relocated &&
            component.id !== "ffmpeg" &&
            !repinned.notices.some(hasText)
        )
            report.moved.push(component.id);
    }

    const known = new Set([...reviewedPaths, ...inventory.map((c) => c.recipe)]);
    for (const recipe of lockedPaths.filter((file) => !known.has(file))) {
        const added = await newComponents(recipe, recipes.get(recipe), buildconf);
        if (!added.length) report.ignored.push(recipe);
        components.push(...added);
        report.added.push(...added.map((component) => component.id));
    }

    // A rewritten file may still hold the reviewed text of another component.
    const writes = new Map();
    const holders = (file) =>
        components.flatMap((component) =>
            component.notices
                .filter((notice) => notice.file === file)
                .map((notice) => ({ component, notice })),
        );
    for (const component of components) {
        for (const notice of component.notices.filter(hasText)) {
            const text = notice.text;
            delete notice.text;
            if (report.added.includes(component.id)) {
                writes.set(notice.file, text);
                continue;
            }
            report.changed.push(`${component.id} (${notice.file})`);
            if (!notice.file.startsWith(`${component.id}/`)) {
                notice.file = ownFile(component, notice.url, component.revision);
                writes.set(notice.file, text);
                continue;
            }
            const reviewed = fs.readFileSync(path.join(root, "notices", notice.file));
            for (const other of holders(notice.file)) {
                if (other.notice === notice) continue;
                other.notice.file = ownFile(
                    other.component,
                    other.notice.url,
                    other.component.revision,
                );
                writes.set(other.notice.file, reviewed);
            }
            writes.set(notice.file, text);
        }
    }

    components.sort((left, right) => (left.id < right.id ? -1 : 1));
    const replacements = [
        [`releases/tag/${manifest.release}`, `releases/tag/${lock.release}`],
        ...inventory.flatMap((component) => {
            const pinned = context.pinned.get(component.id) ?? component;
            return [
                [component.revision, pinned.revision],
                [component.repository, pinned.repository],
            ].filter(([reviewed, locked]) => reviewed !== locked);
        }),
    ];
    const source = sourceAccess(
        fs.readFileSync(path.join(root, "SOURCE.txt"), "utf8"),
        components,
        replacements,
    );
    const referenced = new Set(
        components.flatMap((component) =>
            component.notices.map((notice) => notice.file),
        ),
    );

    const summary = [
        ...report.moved.map((id) => `Pin moved, license text unchanged: ${id}`),
        ...report.relocated.map((entry) => `Repository moved: ${entry}`),
        ...report.changed.map((entry) => `License text changed: ${entry}`),
        ...report.added.map((id) => `Added component: ${id}`),
        ...report.removed.map((id) => `Removed component: ${id}`),
    ];
    console.log(`FFmpeg notice inventory for ${lock.release} (${locked})`);
    for (const line of summary) console.log(`- ${line}`);
    for (const recipe of report.ignored)
        console.log(`- Ignored recipe not enabled in either binary: ${recipe}`);
    console.log(
        summary.length
            ? "Inventory content changed; a maintainer must review this diff before notices/manifest is rebound."
            : "Inventory content unchanged; only the FFmpeg revision was rebound.",
    );
    if (values["dry-run"]) return;

    for (const [file, text] of writes) {
        const target = path.join(root, "notices", file);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, text);
    }
    for (const file of noticeFiles(path.join(root, "notices"))) {
        if (referenced.has(file)) continue;
        fs.rmSync(path.join(root, "notices", file));
        for (
            let directory = path.dirname(path.join(root, "notices", file));
            fs.readdirSync(directory).length === 0;
            directory = path.dirname(directory)
        )
            fs.rmdirSync(directory);
    }
    fs.writeFileSync(
        path.join(root, "notices/sources.json"),
        `${JSON.stringify(components, null, 2)}\n`,
    );
    fs.writeFileSync(path.join(root, "SOURCE.txt"), source);
}

try {
    await main();
} catch (error) {
    console.error(error.message);
    process.exit(1);
}
