# Streamarr Transcode Worker - Project Guidelines

## Commands
- FFmpeg tooling requires the Node major in `buildpacks/ffmpeg/.nvmrc` (24.x) and Bash. `./mvnw test` and `./mvnw verify` run that tooling and fail with any other major: run `nvm use "$(cat buildpacks/ffmpeg/.nvmrc)"` first. Keep Node version markers out of the repository root: Paketo treats them as application dependencies.
- `./mvnw verify` — full build: unit tests (Surefire, `*Test`) + integration tests (Failsafe, `*IT`) + Checkstyle + Spotless + JaCoCo
- `./mvnw test` — unit tests only
- One unit test: `./mvnw test -Dtest=CircularLineBufferTest -Djacoco.skip=true`. One integration test: `./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=WorkerPackagingIT -Djacoco.skip=true`
- `./mvnw spotless:apply` — format before committing
- Smoke tests (`@Tag("SmokeTest")`, `WorkerMediaSmokeTest`) are excluded from all normal builds; with FFmpeg and ffprobe on `PATH`, run with `./mvnw test -Dtest=WorkerMediaSmokeTest -Dsurefire.excludedGroups=`
- Image tests (`@Tag("ImageTest")`, `WorkerImageIT`) are excluded from all normal builds and need Docker and `pack`. Build with `.github/actions/pack-build/build-worker-image.sh streamarr-worker:local`, then run `./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=WorkerImageIT -Dfailsafe.excludedGroups=SmokeTest -Dworker.image=streamarr-worker:local -Djacoco.skip=true`. Neither command publishes. See [Image validation](docs/image-validation.md)
- Node tests cover the tooling around the build: `node --test buildpacks/ffmpeg/test/*.test.mjs` (notice generator), `node --test .github/actions/pack-build/*.test.mjs` (image build and publication boundaries), `node --test .github/actions/sonar/test/*.test.mjs` (analysis failure handling)
- `node buildpacks/ffmpeg/bin/vendor-notices.mjs` — regenerate `notices/sources.json`, the notice files, and `SOURCE.txt` from upstream's pins for the locked release; `--dry-run` only reports, and so does a run at the release `notices/manifest` binds. It never writes `notices/manifest`, and a changed inventory still needs a maintainer's review. Its verdict compares upstream with the working tree, so it says "unchanged" only while `notices/sources.json` is still the inventory `notices/manifest` binds
- `buildpacks/ffmpeg/bin/update-lock --check` — validate the FFmpeg lock, review manifest, and notices offline. `buildpacks/ffmpeg/ffmpeg.lock` is generated from `buildpacks/ffmpeg/release` — never hand-edit it; change `release` and regenerate with `buildpacks/ffmpeg/bin/update-lock`. See the [FFmpeg runtime lock](buildpacks/ffmpeg/README.md)
- The worker protocol is owned by streamarr-server and published as a Buf Java SDK (`build.buf.gen.streamarr.transcode.v1`). This repository keeps no proto copy: change the contract in the server, then move the `buf.sdk.version` pin in `pom.xml`
- Local worker: start Streamarr with its worker session listener bound to loopback and mount the same media directory in both processes. Set `TRANSCODE_WORKER_ID`, `TRANSCODE_WORKER_SOURCE_NAMESPACE_ID` (the same UUID the server uses), and `TRANSCODE_WORKER_SOURCE_ROOT`, run `./mvnw verify`, then `java -jar "target/transcode-worker-$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout).jar"`. The worker connects to `127.0.0.1:9090` over plaintext gRPC and serves Actuator health on port 9091. See the [README](README.md) for the remaining `TRANSCODE_WORKER_*` settings.

## Engineering Philosophy

### Pre-production Compatibility
- Streamarr has no production instances. Breaking changes are acceptable during this period.
- Evolve the worker's settings, behavior, and image in place and update the server and deployment examples to match. Do not add compatibility switches or legacy adapters solely to preserve pre-production contracts.
- The server owns the worker protocol. Contract changes are additive, and the server dispatches a new operation only to workers that advertise support for it, so implement and advertise a capability here before the server relies on it. See [ADR 0033](https://github.com/streamarr/streamarr-adr/blob/main/adr/0033-transcode-worker-is-a-separate-service.adoc).
- Establish supported server and worker version compatibility requirements before production deployments begin.

### TDD: Red-Green-Refactor
- Write a failing test FIRST (RED)
- Write the minimum code to make it pass (GREEN)
- Refactor with confidence (REFACTOR)
- Every feature and bug fix starts with a test
- When fixing a defect: first write an API-level failing test, then write the smallest test that replicates the problem, then get both to pass
- Refactor only when tests are green — never refactor while red
- Use the simplest solution that could possibly work

### Root Cause First
- Before fixing a bug, reproduce it and explain the mechanism. A fix that adds a retry, sleep, widened timeout, defensive check, or call-site special case without a stated mechanism is a symptom patch, not a fix.
- If the mechanism lives in a lower layer, fix it there rather than working around it in the caller — correct behavior becomes dependent on the workaround and the underlying bug remains hidden from the next caller.
- Dismissing an intermittent failure as unrelated to the implementation requires evidence; assume an intermittent failure is a real race until proven otherwise — single-thread happy paths hide races, concurrency tests surface them.
- State the root cause in the PR description so reviewers can check the diagnosis, not just the patch.

### Tidy First (Kent Beck)
- Separate all changes into two types:
    1. STRUCTURAL: Rearranging code without changing behavior (renaming, extracting methods, moving code)
    2. BEHAVIORAL: Adding or modifying actual functionality
- Never mix structural and behavioral changes in the same commit
- Always make structural changes first when both are needed
- Validate structural changes don't alter behavior by running tests before and after

### Commit Discipline
- Only commit when ALL tests pass and ALL warnings are resolved
- Each commit is a single logical unit of work
- Commit subjects start with the lowercase prefix `structural:` or `behavioral:` (e.g. `structural: carry probe attempt context through execution`); streamarr-server and streamarr-web use the same lowercase prefixes
- The dedicated release bot uses Release Please's `chore(main): release X` subjects for stable and snapshot version updates, with an empty squash commit body; this exception does not change human commit subjects
- Small, frequent commits over large, infrequent ones
- Commit messages must be under 200 words
- Always use signed commits (`git commit -S`)
- Keep GitHub stacked PR branches linear by rebasing dependent branches onto their updated bases. Put review fixes in ordinary commits; changes made only inside merge commits can be lost when GitHub automatically rebases the remaining stack after a merge.
- NEVER include Co-Authored-By trailers, "Generated by", "Authored by", or any AI attribution bylines in commits, PRs, issues, or any other artifacts
- Eliminate duplication ruthlessly; express intent through naming and structure

### SonarCloud Quality Gate
All PRs must pass these conditions on new code:
- **Coverage** ≥80% (aim for 90%) — write tests for new code
- **Duplicated Lines** ≤5% — extract shared logic, don't copy-paste
- **Maintainability Rating** A — no code smells
- **Reliability Rating** A — no bugs
- **Security Rating** A — no vulnerabilities
- **Security Hotspots Reviewed** 100% — review all flagged hotspots
- **New Lines** ≤2,000 — aim for ≤1,500 to leave buffer for test coverage

### Flat Control Flow
- Use early returns and guard clauses — avoid else/else-if chains
- No nested conditionals — extract to well-named private methods or use early exits
- Prefer switch expressions over if/else-if chains
- One level of indentation inside methods is ideal; two is acceptable; three means refactor
- Leave a blank line after a completed control-flow block when another statement follows; do not
  add one before `else`, `catch`, `finally`, a `do`/`while` tail, or the enclosing closing brace

### Concurrency Coordination

Choose the simplest mechanism that fits the operation:

- **Virtual threads are the async model**: `Executors.newVirtualThreadPerTaskExecutor()` in try-with-resources, plus `spring.threads.virtual.enabled: true`. No `@Async`, no reactive/actor frameworks. When a task's failure must be visible, use `execute` with a terminal catch rather than `submit` — a throwable captured in a `Future` nobody reads vanishes (see `TranscodeWorker.startVariant`).
- **One monitor per session owner**: `TranscodeWorker` guards its channel, control stream, accepted session, and active job attempts with its own monitor; `WorkerProbeSession` does the same for probe attempts. gRPC `StreamObserver`s are not thread-safe, so every message on the control stream goes through the synchronized `TranscodeWorker.send`.
- **Fence stale sessions by identity**: callbacks that outlive their session compare the session object they captured with the current one and return when they differ (`sendProbeResult`, `endSession`). A disconnected or replaced session must never publish results, upload segments, or stop the next session's work. Commands whose target is not this worker id and boot id are rejected or ignored.
- **Don't join work that needs the monitor while holding it**: the upload loop polls for segments and uploads them on a virtual thread, taking the worker monitor only for brief state checks. Probe completion needs the worker monitor, so `close()` joins probe work only after `closeConnection()` releases it.
- **Every started process has an owner that stops it**: a failure after `engine.start()` must stop the process and drop its attempt, or FFmpeg leaks. Session loss stops all active attempts, and the server learns of their end from the disconnect. Restore the interrupt flag when catching `InterruptedException`.

**Anti-pattern:** reading session or attempt state, releasing the monitor, then acting on what was
read is a check-then-act race. Check and act inside one synchronized section, as
`finishEndedVariant` and `stopVariant` do.

### Defensive Programming
- Fail fast with meaningful exceptions at system boundaries
- Use custom exceptions that convey intent (not generic RuntimeException)
- Validate inputs at the API boundary; trust internal code
- Use Optional for values that may be absent — never return null

### Secret Handling
- The worker holds no application secrets. It loads no certificates, private keys, tokens, or
  passwords, and it selects no transport mode; distributed deployments protect the session with
  mesh-enforced mTLS. Do not add an application credential path without revisiting
  [ADR 0035](https://github.com/streamarr/streamarr-adr/blob/main/adr/0035-worker-uses-spring-boot-and-mesh-transport-security.adoc).
- A worker UUID identifies an instance inside the shared worker trust boundary. It is a claim, not
  a credential — never treat it as proof of identity.
- CI credentials (SonarCloud, Docker Hub, GitHub App keys) live in Actions secrets. Never put one in
  a command argument, source file, or log.

### Code Style
- Google Java Format enforced via Spotless (runs on build)
- Checkstyle also runs at `validate` and fails the build (`checkstyle.xml`)
- No manual formatting debates — the formatter is always right
- Comments explain non-obvious contracts or why an implementation must be unusual; they do not
  narrate names, control flow, tests, or duplicate ADR and policy text
- Use Javadoc only for caller-visible contracts; keep implementation rationale as a concise local
  comment beside the relevant code
- If a comment has to defend fragile code, fix or encode the invariant instead — prefer tests and
  static enforcement over prose that can drift
- Don't add javadoc/comments to code you didn't change

### Java Language
- Leverage Lombok: `@Slf4j`, `@Builder`, `@Getter`, `@RequiredArgsConstructor`, etc.
- Use Lombok `@NonNull` for required record components and method or constructor parameters instead of `Objects.requireNonNull`; in tests, use AssertJ null assertions for required fixtures and resources
- Prefer Builders over passing args to constructors — use `@Builder` for domain objects, DTOs, and any class with more than 2-3 fields
- The builder preference applies to methods too: no method, factory, or test helper should take more than 2-3 positional arguments. Accept a builder-built object instead, or (for fixtures/helpers) return a pre-populated builder the caller customizes with named setters — e.g. `WorkerProbeFixtures.requestBuilder().setProbeAttemptId(id).build()`, never `buildRequest(id, namespace, path, 1)`
- Prefer `var` for local variables unless the type isn't obvious or would lead to misinterpretation
- Use records for immutable data carriers (DTOs, value objects, embeddables)
- Use sealed interfaces/classes when the set of subtypes is known and fixed
- Don't widen known types to `Object` and cast them back; isolate unavoidable unchecked casts to
  the smallest private boundary and document the invariant
- Prefer `Optional` over nullable returns — never return null from a public method
- Use `switch` expressions (not statements) with exhaustive pattern matching
- Use record deconstruction patterns when switching over sealed types
- Use `_` (unnamed variable) for unused caught exceptions and lambda parameters — `catch (IOException _)`
- Prefer `Stream.toList()` over `Collectors.toList()` when an unmodifiable list is acceptable
- Use text blocks (`"""`) for multi-line strings in tests (ffprobe JSON fixtures, scripted executables)

## Architecture Rules
- Four packages under `com.streamarr.transcode`: `engine` (FFmpeg command building, process management, capability detection), `probe` (ffprobe execution and result mapping), `protocol` (contract helpers such as `ProtoUuid` and `WorkerIdentityMetadata`), and `worker` (session, job mapping, settings, Spring Boot application, Actuator health)
- `worker` depends on `engine`, `probe`, and `protocol`; those three depend on nothing in this repository — not on `worker` and not on each other
- `engine` must NEVER import Spring, gRPC, or the generated contract SDK (`build.buf.gen.streamarr.transcode.v1`). `WorkerVariantJobMapper` translates a contract `VariantJob` into the engine's own `TranscodeRequest`; contract types stop there
- Spring stays in `worker` (`TranscodeWorkerApplication`, `WorkerActuatorConfiguration`). Everything else is plain Java constructed by those beans
- gRPC (`io.grpc`) stays in `worker` and `protocol`. Transport construction goes through the `WorkerRuntime` seam so tests can substitute `ScriptedWorkerRuntime` without a socket
- Media sources arrive as a namespace id and relative key. `WorkerMediaSourceResolver` resolves them under the configured source root and rejects anything that escapes it, including through symlinks. Never pass a server-supplied path to a process unresolved
- Settings are read once at startup by `TranscodeWorkerSettings.fromEnvironment`, which fails fast on a missing or malformed value. FFmpeg and ffprobe are validated before the worker registers, so a worker never advertises a capability it cannot run
- The Actuator port serves orchestrator health checks only. The server never dials it, and readiness (`workerSession`) requires an accepted session — a fully occupied worker remains ready
- ArchUnit is on the test classpath, but no `ArchitectureTest` exists yet — these rules are upheld by review until one does

## Settled Decisions (do not revisit without an ADR)
- Architectural decisions are recorded in the canonical
  [`streamarr/streamarr-adr`](https://github.com/streamarr/streamarr-adr) repository.
  Read the relevant ADR before revisiting a decision,
  and record newly settled decisions there using its `adr/template.adoc` and next available
  repository-wide number.
- **Separate service**: the worker is its own repository and image, and the server runs no FFmpeg. There is no shared engine artifact and no Maven reactor ([ADR 0033](https://github.com/streamarr/streamarr-adr/blob/main/adr/0033-transcode-worker-is-a-separate-service.adoc)).
- **Contract**: the server owns the proto and publishes it through Buf; the worker consumes the generated SDK and never a copied proto (ADR 0033).
- **Runtime and health**: Spring Boot with Actuator `/actuator/health/liveness` and `/actuator/health/readiness`. Don't reintroduce a custom gRPC health service, and keep the engine independent of Spring ([ADR 0035](https://github.com/streamarr/streamarr-adr/blob/main/adr/0035-worker-uses-spring-boot-and-mesh-transport-security.adoc)).
- **Transport**: one plaintext gRPC client in every topology. No application TLS, certificate loading, worker tokens, or transport-mode flags; distributed deployments rely on mesh-enforced mTLS and ServiceAccount authorization (ADR 0035).
- **Process execution**: FFmpeg and ffprobe run through `ProcessBuilder`, not JNI bindings ([ADR 0004](https://github.com/streamarr/streamarr-adr/blob/main/adr/0004-processbuilder-over-jni.adoc)).
- **FFmpeg runtime**: numbered Jellyfin FFmpeg releases pinned by `buildpacks/ffmpeg/ffmpeg.lock`. No floating `latest`, distro FFmpeg, or daily autobuilds ([ADR 0029](https://github.com/streamarr/streamarr-adr/blob/main/adr/0029-pin-jellyfin-ffmpeg-releases.adoc)).
- **Concurrency runtime**: virtual threads (Loom). Don't introduce reactive/actor frameworks.
- **Release versions**: Release Please owns the Maven version, changelog, and GitHub releases — don't hand-edit the version in `pom.xml` or `.release-please-manifest.json` ([ADR 0034](https://github.com/streamarr/streamarr-adr/blob/main/adr/0034-release-please-owns-maven-release-versions.adoc), [Releases](docs/releases.md)).
- **Sonar config** lives in `pom.xml` properties, not a `sonar-project.properties` file.

## FFmpeg Runtime and Redistribution
- `buildpacks/ffmpeg/release` is the only Renovate-owned input. `generated/` is Git-ignored — commit reviewed inputs, never reproducible outputs
- `notices/manifest` binds the reviewed inventory to a release, source revision, both archive digests and the reviewed content itself: `inventory_sha256` digests every file under `notices/` except the manifest, together with `SOURCE.txt`, so an input edited after the binding no longer inherits it — offline validation and the buildpack refuse it until an approval covers the new content. Only `buildpacks/ffmpeg/bin/review-release` writes it, and only when its checks find no change inside the inventory: every changed path lies outside it (the changelog and patch series by decision, although quilt obeys the series), and no added, modified, renamed or removed `debian/patches/*.patch` creates a file its reviewed version did not create, or adds, changes or drops edits to `configure`, `LICENSE*` or `COPYING*`. The script reads each patch at both revisions and hands a person any patch it cannot read unambiguously, such as one holding a NUL byte or anything but unified-diff file headers and hunks. It does not read the text a patch adds to a source file. The synchronization workflow runs it from the trusted base and commits the result only on a run started by Renovate's own push, never over reviewed inputs that someone else pushed. It commits only what that run produced: a failed regeneration writes no notice input and deletes none, and a failed capture leaves that architecture's `buildconf` as the head holds it, so the trusted base's copies never replace the head. An approval covers only the content it was submitted on, so whenever an unreviewed run has anything to commit it restores the trusted base's `notices/manifest` in that same commit, withdrawing a binding the head carries. It also writes the manifest with `--approved` when an approving review of the `ffmpeg-notices-review`-labelled Renovate pull request triggers `approve-ffmpeg-notices.yml`, which binds only for an approver whose permission on this repository is `admin` or `maintain` — it reads that role from the API, never from the review's `author_association`, which reports organization membership. Exit status 3 means a person must review both binaries and their dependencies; regenerate the inputs with `vendor-notices.mjs` and `capture-buildconf`, then bind them with that approving review, never by hand. The lock resolver and notice generator never write it, and regeneration never substitutes for that review
- `notices/` must contain only inventoried inputs. Stray files such as `.DS_Store` fail the inventory check
- Streamarr selects FFmpeg's native `aac` encoder, not `libfdk_aac`, and the runtime must not enable `--enable-nonfree`
- Do not publish a binary whose Corresponding Source is unavailable
- The buildpack, lock, notices, and their Node tests are kept together. See the [FFmpeg runtime lock](buildpacks/ffmpeg/README.md)

## Images and Releases
- CI publishes the exact image it tested — publication never rebuilds. Pull requests and manual runs never authenticate or publish. See [Image validation](docs/image-validation.md)
- Tags can be replaced; consumers pin a tag and digest. Stable `X.Y.Z` tags and `latest` belong to Release Publisher; CI on `main` publishes commit and snapshot tags
- The aggregate `build` check covers verification, SonarCloud, smoke tests, and both native image jobs. Keep a new required job inside it
- Workflows, release configuration, and packaging scripts are pinned by tests in `src/test/java/com/streamarr/transcode/config` and the Node tests beside the scripts. Change the test with the workflow

## Testing

### Strategy (Hexagonal)
- Test behavior at the highest public API — the **worker session**: drive `TranscodeWorker` with the commands a control plane sends and assert the messages, uploads, and process effects it produces; test inputs → outputs, not internal wiring
- Use the lightest test that proves the behavior:
    - **Unit tests with Fakes** for business logic, orchestration, and behavioral contracts — fast, no Spring context, no socket (`ScriptedWorkerRuntime`, `FakeFfmpegProcessManager`, `ControlledProbeProcess`, `QueuedProbeExecutor`)
    - **Mockito stubs** when Fakes can't reach a code path (e.g., `mock(Path.class)` for a filesystem failure) — stubs provide canned answers ([Mocks Aren't Stubs](https://martinfowler.com/articles/mocksArentStubs.html))
    - **Integration tests** when behavior depends on the real gRPC transport, the Spring Boot application, or the packaged jar — a scripted control plane built from the contract stubs listens on an ephemeral port
    - **Image tests** (Testcontainers) when behavior depends on the shipped image: the Boot process, its health probes, the locked FFmpeg runtime, and termination
    - **Smoke tests** when only real FFmpeg and ffprobe can prove it: probing, corrupt-media classification, remuxing, transcoding, segment uploads
- **Engine tests** verify command construction and process management through the engine's own types — don't route them through the worker session
- **Pure logic unit tests** (no Spring, no processes) for parsers, validators, and stateless utilities with meaningful complexity (e.g., `CircularLineBuffer`, settings parsing)
- **Configuration tests** in `src/test/java/com/streamarr/transcode/config` pin workflows, release configuration, and packaging scripts

### Hard Rules
- Mockito **mocks for verification** are banned — no `verify()`, no `ArgumentCaptor`. Observe outcomes through Fakes (e.g., `FakeFfmpegProcessManager`) instead
- NEVER make a method public or package-private solely for testing, break encapsulation via reflection (`FieldUtils.writeField`), or test implementation details that would break on refactoring
- Tests never start the Streamarr server or a database. The server's tests cross the boundary through the published worker image; this repository's tests use a scripted control plane
- Normal builds never require FFmpeg, ffprobe, Docker, or a registry; tests that exercise scripts put scripted `docker` and `curl` executables on `PATH`. Anything that needs the real thing carries `SmokeTest` or `ImageTest`
- Tests never authenticate to or contact a registry; publication tests use a registry fake

### Conventions
- Integration tests: `*IT.java`, `@Tag("IntegrationTest")`; bind ephemeral ports (`forPort(0)`, `--server.port=0`) and read the bound port back
- Unit tests: `*Test.java`, `@Tag("UnitTest")`
- Smoke tests: `@Tag("SmokeTest")`; image tests: `*IT.java`, `@Tag("ImageTest")`, image supplied with `-Dworker.image=<image>`
- Application tests run the real application — as a child JVM (`TranscodeWorkerApplicationIT`) or through `SpringApplicationBuilder` (`WorkerHealthServerIT`) — no test slices (`@WebMvcTest`, etc.)
- Wait on conditions with Awaitility or a bounded future — never a bare `Thread.sleep`
- Test naming: `shouldExpectedBehaviorWhenCondition()` + `@DisplayName` in the same human-readable `Should ... when ...` phrasing

## Twelve-Factor Principles ([12factor.net](https://12factor.net))
We follow these factors from the Twelve-Factor App methodology:

- **III. Config** — All environment-specific config via environment variables, never hardcoded. `TRANSCODE_WORKER_*` variables are parsed once into `TranscodeWorkerSettings`; Spring's standard `SERVER_PORT` sets the Actuator port.
- **IV. Backing Services** — The control plane, the source media mount, and segment scratch storage are attached resources swappable via config. No code changes to point at a different server, media root, or output directory.
- **VI. Processes** — The worker is stateless. Job attempts live only in memory and end with the session; the server owns recovery and redispatch. Workers mount source media read-only and write temporary output to separate storage.
- **IX. Disposability** — Fast startup and graceful shutdown. Startup validates FFmpeg and ffprobe before registering. Shutdown sends FFmpeg its graceful quit command, closes the session, and cleans up temporary files. Session loss ends active work and the process exits for its supervisor to restart it.
- **X. Dev/Prod Parity** — Image tests run the actual Boot image with the locked FFmpeg runtime, and CI publishes that same tested image. No distro FFmpeg substitutes in CI.
- **XI. Logs** — Treat logs as event streams. No file-based logging; the process writes to stdout and the platform collects it. FFmpeg stderr is drained into a bounded buffer and surfaced only as failure diagnostics.

## Tech Stack
- Java 25 (LTS), Spring Boot 4.x — exact versions live in `pom.xml` (Renovate keeps them current; don't pin patch versions here)
- gRPC (`grpc-netty-shaded`) with the Buf-generated `streamarr-org_transcode_grpc_java` SDK for the worker protocol
- Spring Boot Actuator over Spring MVC for health probes only; virtual threads for concurrency
- FFmpeg and ffprobe through `ProcessBuilder`, shipped by the locked Jellyfin FFmpeg buildpack in a Paketo-built, non-root image
- The worker probes media and runs remux and transcode jobs dispatched by the server, uploading HLS segments back over gRPC.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **streamarr-transcode-worker**.

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root. These instructions use GitNexus 1.6.12 and require Node `^22.18.0 || >=24.11.0`, including when using Bun as the package runner. Before using the generated runner, install `npm install --global gitnexus@1.6.12` and verify `gitnexus --version` reports `1.6.12`; its automatic fallbacks can otherwise fetch `latest`. Without that global install or `.gitnexus/run.cjs`, replace `node .gitnexus/run.cjs` with `npx gitnexus@1.6.12`, `bunx gitnexus@1.6.12`, or (pnpm 10.2+) `pnpm --allow-build=@ladybugdb/core --allow-build=gitnexus --allow-build=tree-sitter dlx gitnexus@1.6.12`. See `.claude/skills/gitnexus-cli/SKILL.md` for setup and npm 11 recovery.

## Always Do

- **Bind the checkout before MCP checks.** Use `list_repos` to select the intended repository; when more than one is indexed, pass `repo` on subsequent calls. Use `repo: "streamarr-transcode-worker"` only when that name identifies one checkout; otherwise use the intended registered absolute path. For `detect_changes`, also pass the absolute `worktree` path when the MCP server starts outside the linked worktree being edited. Apply these arguments to the examples below; CLI `--repo .` selects the current checkout.
- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/streamarr-transcode-worker/context` | Codebase overview, check index freshness |
| `gitnexus://repo/streamarr-transcode-worker/clusters` | All functional areas |
| `gitnexus://repo/streamarr-transcode-worker/processes` | All execution flows |
| `gitnexus://repo/streamarr-transcode-worker/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
