import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const library = fileURLToPath(new URL("../lib", import.meta.url));

// The digest an approving review binds, computed by the buildpack's own shell library.
export function inventoryDigest(root) {
    const result = spawnSync(
        "bash",
        [
            "-c",
            '. "$1/checksum.sh"; . "$1/notices.sh"; ffmpeg_notices_digest "$2"',
            "--",
            library,
            root,
        ],
        { encoding: "utf8", timeout: 15000 },
    );
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
}
