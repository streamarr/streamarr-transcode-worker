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
// Every reviewed text is one of these views of its origin. A new pin is read only through the
// first view that reproduces the reviewed bytes from the reviewed origin, so a text is unchanged
// only when that view still yields those bytes.
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
// A vendored text is upstream's bytes, whatever their encoding. The views look only for ASCII, so
// they read the bytes as latin1, which maps every byte to one character and back.
const view = (extract, bytes) =>
    Buffer.from(extract(bytes.toString("latin1")), "latin1");

const checksum = (bytes) => createHash("sha256").update(bytes).digest("hex");
const hasText = (notice) => Object.hasOwn(notice, "text");
const normalize = (repository) => repository.replace(/\.git$|\/$/g, "");
// One repository can hold several libraries on its branches, so a pin names a source only with its revision.
const sourceOf = ({ repository, revision }) => `${normalize(repository)}@${revision}`;
const keyValues = (text) =>
    Object.fromEntries(
        text
            .trim()
            .split("\n")
            .map((line) => line.split("=")),
    );
// Pins, paths and transfer diagnostics come from upstream and end up in the report. Its lines are
// read by people and by scripts, so nothing in one may break the line or drive a terminal.
const oneLine = (text) =>
    text.replace(
        /[\p{Cc}\p{Zl}\p{Zp}]/gu,
        (character) => `\\u${character.codePointAt(0).toString(16).padStart(4, "0")}`,
    );
const say = (line) => console.log(oneLine(line));
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
        const response = fs.readFileSync(body);
        return url.includes("format=TEXT")
            ? Buffer.from(response.toString("latin1").trim(), "base64")
            : response;
    } catch (error) {
        throw new Error(
            `Unable to fetch ${url}: ${String(error.stderr ?? error.message).trim()}`,
        );
    }
}

// Recipes, toolchain configurations, dependency files and listings are read, never vendored.
const readable = async (url) => new TextDecoder().decode(await download(url));
const json = async (url) => JSON.parse(await readable(url));

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
            const config = await readable(`${FFMPEG_RAW}/${locked}/${file}`);
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

const builtByRecipe = (component) =>
    component.revisionEvidence !== "notice-only" && RECIPE.test(component.recipe);

// Upstream renumbers and regroups recipes. The component follows its repository to the recipe that
// pins it first, or else to the one that pins it at all; undefined means upstream dropped it.
function followRecipe(component, recipes) {
    if (!builtByRecipe(component) || recipes.has(component.recipe)) return component;
    const pinnedBy = (relevant) =>
        [...recipes.keys()].filter((file) =>
            relevant(recipePins(recipes.get(file))).some(
                (pin) => pin.repository === normalize(component.repository),
            ),
        );
    const first = pinnedBy((pins) => pins.slice(0, 1));
    const candidates = first.length ? first : pinnedBy((pins) => pins);
    if (candidates.length > 1)
        throw new Error(
            `${component.recipe} is gone and several recipes pin ${component.repository}: ${candidates.join(", ")}`,
        );
    return candidates.length ? { ...component, recipe: candidates[0] } : undefined;
}

// Returns what upstream now builds, or undefined when the component is gone.
async function upstreamPin(component, context) {
    if (builtByRecipe(component)) return recipePin(component, context);
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
    const dependencies = await readable(
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

function namedAfter(component, url, revision) {
    const file = `${component.id}/${upstreamFile(url, revision)}`;
    return /\.(txt|md)$/i.test(file) ? file : `${file}.txt`;
}

// Upstream names the file, so it must satisfy the generator's input-path rule before use.
function ownFile(component, url, revision) {
    const file = namedAfter(component, url, revision);
    if (
        !/^[a-zA-Z0-9_+./-]+$/.test(file) ||
        file.split("/").some((part) => ["", ".", ".."].includes(part))
    )
        throw new Error(`Unsafe notice path from upstream: ${file}`);
    return file;
}

// A toolchain revision is built from a version that the reviewed role may name as well.
function toolchainRole(component, revision) {
    const prefix = TOOLCHAIN[component.id]?.prefix;
    if (!prefix) return component.role;
    return replaceToken(
        component.role,
        component.revision.slice(prefix.length),
        revision.slice(prefix.length),
    );
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
            const origin = await download(url);
            const located = Object.hasOwn(notice, "upstreamSha256")
                ? { ...notice, url, upstreamSha256: checksum(origin) }
                : { ...notice, url };
            // Every view returns its own output unchanged, so a file equal to the reviewed bytes
            // is unchanged under whichever view was reviewed, without fetching the reviewed origin.
            if (checksum(origin) === notice.sha256) return located;
            const reviewed = await download(notice.url);
            const extract = EXTRACTIONS.find(
                (candidate) => checksum(view(candidate, reviewed)) === notice.sha256,
            );
            if (!extract)
                throw new Error(
                    `No known extraction reproduces ${notice.file} from ${notice.url}`,
                );
            const extracted = view(extract, origin);
            if (checksum(extracted) === notice.sha256) return located;
            return { ...located, sha256: checksum(extracted), text: extracted };
        }),
    );
    const role = toolchainRole(component, revision);
    return { ...component, repository, revision, role, notices };
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

// Upstream writes the recipe, so reading it has to stay linear in its length: the line is taken
// by a pattern that cannot match one text two ways, and its words are checked one by one.
const configureFlags = (text) =>
    [...text.matchAll(/^[ \t]*echo (.+)$/gm)]
        .map((match) => match[1].trimEnd().split(" "))
        .filter((words) => words.every((word) => /^--enable-[a-z0-9-]+$/.test(word)))
        .flat();

// The binaries that contain what a recipe builds: those whose reviewed build configuration has one
// of its flags, else those of the components already built from it, else, for a recipe without
// flags that no review has seen, all of them. A recipe that enables nothing in either
// configuration is not in the binaries, and neither is one without flags that a review left out.
function builtFor({ flags, citing, unreviewed }, buildconf) {
    const flagged = Object.keys(buildconf).filter((architecture) =>
        flags.some((flag) => buildconf[architecture].split(/\s+/).includes(flag)),
    );
    if (flagged.length) return flagged;
    if (citing.length)
        return Object.keys(buildconf).filter((architecture) =>
            citing.some((component) => component.architectures.includes(architecture)),
        );
    return flags.length || !unreviewed ? [] : Object.keys(buildconf);
}

// Upstream's names choose the id of a proposal. It has to be one the generator accepts and that no
// component holds, because the id also names the directory the proposal's notices are written to.
function reserveId(ids, { id, repository }, file) {
    if (!/^[a-z0-9-]+$/.test(id))
        throw new Error(
            `${file} pins ${repository}, whose id ${id} is not a valid component id`,
        );
    if (ids.has(id))
        throw new Error(
            `${file} pins ${repository}, whose id ${id} belongs to ${ids.get(id)}`,
        );
    ids.set(id, "another proposed component");
}

// Proposes a component for every pin of the recipe that no component claims.
async function newComponents({ recipe, flags, architectures, claimed, ids }) {
    const pins = recipePins(recipe.text)
        .map(({ repository, revision }, index) => ({
            repository,
            revision,
            id: (index === 0
                ? path.basename(recipe.file, ".sh").replace(/^\d+-/, "")
                : path.basename(repository)
            )
                .toLowerCase()
                .replace(/_/g, "-"),
        }))
        .filter((pin) => !claimed.has(sourceOf(pin)));
    for (const pin of pins) reserveId(ids, pin, recipe.file);
    return Promise.all(
        pins.map(async ({ repository, revision, id }) => {
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
                recipe: recipe.file,
                architectures,
                role: flags.length
                    ? `Static library (${flags.join(" ")}).`
                    : `Static dependency built by ${recipe.file}.`,
                notices,
                licenseExpression: await licenseExpression(id, repository),
                distribution: "runtime",
                revisionEvidence: "source",
            };
        }),
    );
}

// Replaces whole tokens only, so a revision is never rewritten inside a longer one.
function replaceToken(text, reviewed, locked) {
    const token = reviewed.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return text.replace(
        new RegExp(`(?<![0-9A-Za-z])${token}(?![0-9A-Za-z])`, "g"),
        locked,
    );
}

function sourceAccess(source, components, replacements) {
    if (!source.includes(INDEX))
        throw new Error("SOURCE.txt has no component source index");
    let prose = source.slice(0, source.indexOf(INDEX));
    for (const [reviewed, locked] of replacements)
        prose = replaceToken(prose, reviewed, locked);
    const index = components.map(
        (component) =>
            `${component.id} (${component.architectures.join(", ")})\n  ${component.role}\n` +
            `  Repository: ${component.repository}\n  Revision: ${component.revision}\n` +
            `  Recipe: ${component.recipe}\n  Notices: see notices/sources.json (${component.id})\n` +
            (component.version_note ? `  ${component.version_note}\n` : ""),
    );
    return prose + INDEX + index.join("\n");
}

const reviewedBytes = (file) => fs.readFileSync(path.join(root, "notices", file));

// The file belongs to the notice it is named after, or else to the first in its component's directory.
function fileOwner(file, holders) {
    return (
        holders.find(
            ({ component, notice }) =>
                namedAfter(component, notice.url, component.revision) === file,
        ) ?? holders.find(({ component }) => file.startsWith(`${component.id}/`))
    );
}

// Notices whose texts were identical at review share one file. Each distinct text of such a group
// ends in one file: the owner's text stays, or the reviewed text when no owner remains, and every
// other text moves to the file named after the first notice carrying it.
function placeGroup(file, holders, writes) {
    const staying = fileOwner(file, holders) ?? holders.find(({ notice }) => !hasText(notice));
    for (const [sha256, sharers] of Map.groupBy(holders, ({ notice }) => notice.sha256)) {
        const [{ component, notice }] = sharers;
        const stays = staying?.notice.sha256 === sha256;
        const target = stays ? file : ownFile(component, notice.url, component.revision);
        if (hasText(notice) || !stays)
            writes.set(target, notice.text ?? reviewedBytes(file));
        for (const sharer of sharers) sharer.notice.file = target;
    }
}

function placeNotices(components) {
    const writes = new Map();
    const holders = components.flatMap((component) =>
        component.notices.map((notice) => ({ component, notice })),
    );
    for (const [file, group] of Map.groupBy(holders, ({ notice }) => notice.file)) {
        if (group.some(({ notice }) => hasText(notice))) placeGroup(file, group, writes);
    }
    return writes;
}

// A planned file must hold the recorded text of every notice that will reference it.
function requireRecordedTexts(components, writes) {
    for (const component of components) {
        for (const { file, sha256 } of component.notices) {
            if (writes.has(file) && checksum(writes.get(file)) !== sha256)
                throw new Error(
                    `notices/${file} would not hold the text recorded for ${component.id}`,
                );
        }
    }
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

// The report compares upstream with notices/sources.json, which is the reviewed inventory only
// while it names the FFmpeg revision that notices/manifest binds; the tool's own output moves it.
function verdict(manifest, inventory, changed) {
    const bound = inventory.find((component) => component.id === "ffmpeg")?.revision;
    if (bound !== manifest.source_revision)
        return (
            `Inventory content was not compared with the review of ${manifest.release}: notices/sources.json ` +
            `names FFmpeg revision ${bound}, not the reviewed ${manifest.source_revision}, so this report ` +
            "covers only what moved since it was regenerated. A maintainer must review it against the " +
            "reviewed inventory before notices/manifest is rebound."
        );
    return changed
        ? `Inventory content changed since the review of ${manifest.release}; a maintainer must review this diff before notices/manifest is rebound.`
        : `Inventory content unchanged since the review of ${manifest.release}; nothing but the FFmpeg revision differs.`;
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
                await readable(`${FFMPEG_RAW}/${locked}/${file}`),
            ]),
        ),
    );

    const context = { locked, recipes, inventory, pinned: new Map() };
    const report = {
        regrouped: [],
        moved: [],
        relocated: [],
        changed: new Set(),
        added: [],
        removed: [],
        ignored: [],
    };
    const derived = (component) =>
        SUBMODULE.test(component.recipe) || DEPENDENCY_FILE.test(component.recipe);
    const components = [];
    for (const reviewed of [
        ...inventory.filter((candidate) => !derived(candidate)),
        ...inventory.filter(derived),
    ]) {
        const component = followRecipe(reviewed, recipes);
        const pin = component && (await upstreamPin(component, context));
        if (!pin) {
            report.removed.push(reviewed.id);
            continue;
        }
        if (component.recipe !== reviewed.recipe)
            report.regrouped.push(
                `${component.id} (${reviewed.recipe} -> ${component.recipe})`,
            );
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

    // Every pin of a recipe that is in the binaries is claimed by a component or proposed as one.
    const claimed = new Set(components.map(sourceOf));
    const ids = new Map(inventory.map((component) => [component.id, "a reviewed component"]));
    for (const file of lockedPaths) {
        const citing = components.filter((component) => component.recipe === file);
        const unreviewed = !reviewedPaths.includes(file);
        const flags = configureFlags(recipes.get(file));
        const architectures = builtFor({ flags, citing, unreviewed }, buildconf);
        if (!architectures.length) {
            if (unreviewed) report.ignored.push(file);
            continue;
        }
        const added = await newComponents({
            recipe: { file, text: recipes.get(file) },
            flags,
            architectures,
            claimed,
            ids,
        });
        for (const component of added) claimed.add(sourceOf(component));
        components.push(...added);
        report.added.push(...added.map((component) => component.id));
    }

    const writes = placeNotices(
        components.filter((component) => !report.added.includes(component.id)),
    );
    for (const component of components) {
        for (const notice of component.notices.filter(hasText)) {
            if (report.added.includes(component.id)) writes.set(notice.file, notice.text);
            else report.changed.add(`${component.id} (${notice.file})`);
            delete notice.text;
        }
    }
    requireRecordedTexts(components, writes);

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
        ...report.regrouped.map((entry) => `Recipe moved: ${entry}`),
        ...report.moved.map((id) => `Pin moved, license text unchanged: ${id}`),
        ...report.relocated.map((entry) => `Repository moved: ${entry}`),
        ...[...report.changed].map((entry) => `License text changed: ${entry}`),
        ...report.added.map((id) => `Added component: ${id}`),
        ...report.removed.map((id) => `Removed component: ${id}`),
    ];
    say(`FFmpeg notice inventory for ${lock.release} (${locked})`);
    for (const line of summary) say(`- ${line}`);
    for (const recipe of report.ignored)
        say(`- Ignored recipe not enabled in either binary: ${recipe}`);
    say(verdict(manifest, inventory, summary.length > 0));
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
    console.error(oneLine(String(error.message)));
    process.exit(1);
}
