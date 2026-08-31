<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge for Kotlin

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## This Is A Fork

This repository is a fork of [unclebob/swarm-forge](https://github.com/unclebob/swarm-forge), retargeted from Clojure/Babashka to **Kotlin Multiplatform** projects that build with the Gradle wrapper and ship Android and iOS.

The orchestration is upstream's and unchanged: tmux sessions, worktrees, the handoff daemon, the audit gate, and the pack cockpit. What this fork replaces is the layer underneath — the constitution's engineering article and the tool table that article names.

Upstream tells every agent to install `crap4clj`, `dry4clj`, and `clj-mutate`, and to build a project-specific acceptance entry-point generator, runtime, and mutation runner adapter from scratch. In a Gradle project those instructions produce, at best, six agents each guessing at a different scaffold. This fork replaces the Clojure tools with Kotlin equivalents and ships the acceptance pipeline as a real tool, so no agent has to invent one.

| Concern | Upstream | This fork |
| --- | --- | --- |
| Coverage | `cloverage` | `kover` (Kotlin's own coverage plugin) |
| CRAP score | `crap4clj` | `crap4kotlin` (Kover XML + cyclomatic complexity) |
| Duplication | `dry4clj` | `dry4kotlin` (PMD CPD, Kotlin and Swift) |
| Static analysis | — | `detekt` |
| Code mutation | `clj-mutate` | `mutate4kotlin` (PIT via `gradle-pitest-plugin`) |
| Acceptance pipeline | each project builds its own | `aps-kotlin`, shipped |
| Gherkin mutation | APS `gherkin-mutator` | APS `gherkin-mutator`, unchanged, with `aps-kotlin worker` as the runner adapter |

Everything in the sections below is upstream's design unless it appears under **Kotlin Toolchain** or **Acceptance Tiers**.

## Intent

This `main` branch is documentary: it explains the system and carries the shared operational scripts and default constitution articles. The runnable workflow branches carry the project-facing configurations, role prompts, and local constitution articles that define specific workflows.

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## Branches

The runnable SwarmForge configurations live on dedicated branches. Each branch contains the `swarmforge/swarmforge.conf`, local constitution articles, and role prompts for one workflow. Use the `get-swarm-forge` helper to compose a runnable branch with the shared operational scripts and shared constitution articles from `main`.

### `two-pack`

`two-pack` is the quick backend workflow. Use it for small tasks that benefit from fast coding without the overhead of Gherkin and acceptance testing, while still preserving backend refactoring and hardening.

- `coder` implements requested behavior with TDD and unit tests.
- `cleaner` batches coder handoffs and performs cleanup, CRAP and DRY review, architectural review, encapsulation and separation-of-concerns fixes, and language mutation hardening.

The normal flow is `coder` -> `cleaner`, then a completion broadcast to every other role (card to Done). Use this branch when you want a tight implementation/refinement loop without specification, QA, property-test, or acceptance-test roles.

In this fork, two-pack's project article forbids installing `aps-kotlin`, `gherkin-parser`, `ir-dry-checker`, and `gherkin-mutator`, and the launcher does not announce those tools to a pack with no specifier. Unit tests are two-pack's only behavioral evidence, so the article also tells both agents to default new tests to `commonTest` rather than `androidUnitTest`.

### `four-pack`

`four-pack` is the compact specification workflow. Use it for moderate projects that require Gherkin specification and some architectural consideration without splitting every quality gate into its own agent:

- `specifier` turns user intent into precise Gherkin acceptance specifications and asks for approval before handoff.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `refactorer` performs behavior-preserving cleanup, coverage improvement, CRAP and DRY review, mutation-site scans, and property-test support.
- `architect` owns high-level structure, dependency direction, mutation hardening, DRY review, soft Gherkin mutation, and final completion notification.

The normal flow is `specifier` -> `coder` -> `refactorer` -> `architect`, then a completion broadcast to every other role (card to Done). Use this branch when you want disciplined development without splitting cleanup, architecture, hardening, and QA into separate agents.

In this fork, four-pack builds Tier 1 acceptance only. It has no role that owns a device suite, so its local engineering article forbids Espresso, on-device Compose UI tests, and XCUITest here and points device-level verification at six-pack. Robolectric compose tests are not affected: they run on the JVM in the host test source set, so they stay in Tier 1 everywhere. Kotest is named as the property-testing framework so the refactorer stops shopping for one.

### `six-pack`

`six-pack` is the full workflow. Use it for major projects that require full specification, up-front QA, backend verification, and significant architectural consideration. It separates each major quality gate into its own role:

- `specifier` turns user intent into accepted Gherkin specifications and end-to-end QA procedures.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `cleaner` performs local behavior-preserving cleanup, coverage improvement, CRAP and DRY review, and mutation-site scans.
- `architect` reviews module structure, boundaries, dependency direction, and property-test coverage.
- `hardender` performs mutation hardening, language mutation, CRAP and DRY verification, and soft Gherkin mutation.
- `QA` converts the specifier's QA procedures into executable scripts, runs final user-interface verification, checks handoff consistency, and sends completion notifications.

The normal flow is `specifier` -> `coder` -> `cleaner` -> `architect` -> `hardender` -> `QA`, then a completion broadcast to every other role (card to Done). Use this branch when you want each review and verification concern owned by a separate agent.

In this fork, six-pack is the only pack with both acceptance tiers: the coder builds Tier 1 and the hardender mutates it, while QA owns the Tier 2 device suite. Tier 2 is never mutated. See **Acceptance Tiers** below.

### `simple-windows`

`simple-windows` is a tag on `main`, not a workflow branch. It marks the last commit before the pack cockpit: one Terminal window per role, no dashboard, and no `window-invisible`. It does not sit on `squad` or the other squad branches.

```sh
git fetch origin tag simple-windows
git checkout simple-windows
```

Or download that snapshot:

```sh
curl -L "https://github.com/unclebob/swarm-forge/archive/refs/tags/simple-windows.tar.gz" | tar -xz --strip-components=1
```

Do not use `simple-windows` as `BRANCH=` in the pack getting-started command below; that command is for `two-pack`, `four-pack`, and `six-pack`.

## Prerequisites

SwarmForge runs locally. Before starting a runnable branch, make sure the target machine has:

- `zsh`
- `git`
- `tmux`
- Babashka (`bb`)
- At least one configured agent backend, such as `codex`, `claude`, `copilot`, or `grok`

This fork additionally needs, for the Kotlin tools:

- A JDK on `PATH`. AGP 8 requires 17 or newer; these tools were exercised on 21.
- A **Gradle wrapper** committed in the target project. Every Kotlin tool runs `./gradlew` from the worktree root and refuses to fall back to a `gradle` on `PATH`, because a swarm-wide daemon and version drift between six worktrees is not worth debugging.
- Whatever the project's own targets need: the Android SDK for `androidTarget()`, and Xcode for the iOS targets.
- Network access on first use. `detekt`, `dry4kotlin` (PMD, ~50 MB), and `aps-kotlin` (the JUnit Platform console launcher) download their jars into `.swarmforge/tools/` once and reuse them.

`kotlinc` is *not* required. Nothing in this fork compiles Kotlin directly; the generated acceptance tests are compiled by the project's own Gradle build.

## Getting Started

Install the `get-swarm-forge` helper somewhere on your `PATH`, such as `~/cmds` or `~/bin`:

```sh
mkdir -p ~/cmds
cp get-swarm-forge ~/cmds/get-swarm-forge
chmod +x ~/cmds/get-swarm-forge
```

Make sure that utility directory is on your shell `PATH`, then run the helper in
the project directory where you want to use SwarmForge:

```sh
get-swarm-forge four-pack codex --yolo
```

Use `two-pack` for the quick two-agent workflow, `four-pack` for the compact specification workflow, or `six-pack` for the full six-agent workflow. Do not use `main` here; `main` stores the shared operational scripts and core constitution articles, while the runnable branches provide the configurations and prompts intended for projects.

`get-swarm-forge` downloads `main` first, copies only the shared `swarmforge/scripts/` and core constitution articles, then overlays the requested runnable branch. It fails fast if required scripts, role prompts, or core constitution articles are missing.

The helper in this fork defaults to `https://github.com/agrognetti/swarm-forge-kotlin`. Point it elsewhere with `SWARMFORGE_REPO_URL`, and choose a different shared-article branch with `SWARMFORGE_BASE_BRANCH`. Installing the pack branches from upstream would pair a Kotlin project with the Clojure constitution and the Clojure tool table, and every agent would then try to install `crap4clj` into a Gradle build.

The `--yolo` in that example is a `codex` flag. `claude` uses `--dangerously-skip-permissions`; other backends differ. Some non-interactive flag is effectively required here, because the Kotlin tools shell out to `./gradlew` and download tool jars on first use, and a backend that stops to ask about each of those will sit idle behind a prompt nobody is watching.

After copying a runnable branch, start the swarm from the target project:

```sh
./swarm
```

The `./swarm` wrapper launches `swarmforge/scripts/swarmforge.sh` from the composed project-local copy. Rerun `get-swarm-forge <branch>` to refresh shared scripts or switch pack branches.

Startup prints a **Dashboard:** URL (also written to `.swarmforge/dashboard-url`) and opens it in the browser when `open` is available. Pack roles default to `window-invisible`: agents run in tmux, but no Terminal window opens per role. The dashboard is the operator surface.

Set `SWARMFORGE_OPEN_BROWSER=0` before `./swarm` to skip the browser open. The dashboard still starts; visit the printed URL.

To stop the swarm, click **Teardown** in the dashboard header and confirm. That terminates agent sessions, tmux, `handoffd`, and the dashboard. Project files stay on disk.

While a swarm is active, SwarmForge tries to prevent the host from sleeping. On macOS it uses `caffeinate`; on Linux it uses `systemd-inhibit` when available. Display lock or manual sleep can still interrupt agents depending on the OS. Set `SWARMFORGE_PREVENT_SLEEP=0` before `./swarm` to disable this behavior.

## Pack Cockpit

The pack cockpit is a local web dashboard served from `main`'s scripts (`pack_web`). Pack branches do not fork it. At startup it reads `swarmforge/swarmforge.conf` and draws swimlanes from that file. The role whose worktree is `master` is the **master agent** (specifier on four-pack and six-pack, coder on two-pack): New Task and the chat rail talk to that agent.

Layout, top to bottom then left to right:

- **Header** — pack title, live marker, **New Task**, **Open** (master pane), **Teardown**.
- **Attention** — human gates: spec approvals and agent clarification requests.
- **Board** — one swimlane per conf role, left to right, plus a **Done** well. Cards are tasks, not stories. A card sits in the agent who currently holds it.
- **Work Queue** — one row per role: task name, role (click to open that agent's pane), live/idle, and a six-bar activity thermometer.
- **Chat** — follow-ups to the master agent.

### Operating the dashboard

**Start a task.** Click **New Task**, give a short stable **name** and the **task** text, then **OK**. That creates a card in the master lane and queues a `(New Task)` note to that agent (`task:` is the card name, payload is the text). The agent takes it with `ready_for_next.sh`. Downstream roles keep that name as `task:` on every `git_handoff`. Do not invent a second name in chat.

**Talk to the master agent.** Type in the chat composer (Enter sends, Shift+Enter newline). The dashboard stores a durable request, injects `[id] text` into the master pane, and shows the reply when the agent answers.

**Approve a specifier handoff.** When the specifier queues work for the next role, Attention shows **Approval** with the task, a **Documents** menu for artifacts, **Approve**, and **Reject**. Approve delivers the handoff and moves the card. Reject leaves the card with the specifier and notifies that agent. Two-pack has no specifier gate; those handoffs deliver immediately.

**Answer a clarification.** If an agent needs a human answer, Attention shows **Request clarification**, the question, and a text box. Submit injects the answer into that agent's pane. Do not use Approve/Reject for this.

**Watch the board.** Cards move when `handoffd` delivers a `git_handoff`. Click a card to open its task body in a resizable window. The card can show the agent's latest status sentence (the last pane line that contains `I'm`). The last role in every pack sends the **terminal** handoff: `to:` every other role. That, not merely several names, moves the card to **Done**. The Done well is always on the board; it fills when that handoff is delivered.

**Inspect an agent.** Click a Work Queue role name, or **Open** in the header / chat rail, to pop a live pane capture. Those windows are growable. Agents themselves stay in tmux; these views do not replace the dashboard.

**Stop.** **Teardown** asks for confirmation, then kills the swarm. If the dashboard says **Swarm disconnected**, the UI is no longer talking to a live pack.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session per configured role
- Serves a **pack cockpit** in the browser and, by default on the pack branches, skips a Terminal window per role (`window-invisible`)
- Reads behavior from project-local `swarmforge/roles/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, `copilot`, or `grok`
- Puts the shared `swarmforge/scripts/` directory on each agent's `PATH`, including handoff helpers for active swarm communication
- Creates git worktrees under `.worktrees/` for roles assigned to dedicated worktree names
- Initializes a git repository in a new working directory when needed
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/roles/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` directs agents to read article files under `swarmforge/constitution/articles/`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, `copilot`, or `grok`.
- **Pack Cockpit** — A local dashboard for New Task, Attention, the board, Work Queue, master-agent chat, and Teardown.
- **Observable Swarm** — Watch agents from the dashboard; open a live pane when you need the raw session. Optional `window` lines still open a Terminal surface per role.
- **Self-Hosted & Lightweight** — Runs locally in tmux and a browser, with optional Terminal windows.

## Kotlin Toolchain

The constitution names tools; `swarm_tool.sh` installs them. That contract is upstream's and unchanged:

```sh
swarm_tool.sh require <tool>   # fail with MISSING if it is not installed
swarm_tool.sh ensure <tool>    # install it, then make it available
```

Installed tools become executable wrappers in `.swarmforge/bin/`, which is on every agent's `PATH`. Upstream's Clojure, Go, and Java tools are still in the catalog and still work. This fork adds six local tools carried in `swarmforge/scripts/kotlin/`, so they are synced into each worktree rather than cloned from a repository at startup:

| Tool | What it does |
| --- | --- |
| `kover` | Runs the project's Kover coverage task and locates the XML report. Reports the author's code apart from generated code, and files that declare `@Composable` apart from the rest — a reading aid, not an exemption, since a Robolectric compose test covers them in the same task. |
| `crap4kotlin` | CRAP score from the Kover XML plus cyclomatic complexity. Excludes generated classes and compiler-generated members, and says how many of each it hid. Declares `kover` as a dependency, so `ensure crap4kotlin` installs both. |
| `dry4kotlin` | Duplicate detection with PMD CPD, over Kotlin **and** Swift. Kotlin is searched under `src`, where Gradle puts it; Swift is searched across the worktree, because Xcode does not put it under `src`. Both counts are printed. |
| `detekt` | Static analysis through the detekt CLI, over every Kotlin source set including `iosMain`. Applies a Kotlin Multiplatform baseline that corrects two things detekt is wrong about on this toolchain, and names every config that shaped the report. No upstream counterpart. |
| `mutate4kotlin` | Code mutation with PIT. Runs the pitest Gradle plugin's task where the plugin registers one, and drives PIT's own command line where it does not — which is every Kotlin Multiplatform module. |
| `aps-kotlin` | The acceptance pipeline: entry-point generator, runtime, test runner, and the `gherkin-mutator` runner adapter. |

### Pinned third-party versions

Each tool pins the release it downloads, in one named `def` at the top of its file. Nothing floats: an agent that reports a number has to be able to say which build produced it.

| Pinned | Where | Note |
| --- | --- | --- |
| detekt CLI 1.23.8 | `detekt.bb` | The last stable release. 2.x has been in alpha since 2025-09, and an agent must not measure against a moving alpha. |
| PMD 7.27.0 | `dry4kotlin.bb` | |
| PIT 1.30.0 | `mutate4kotlin.bb` | `gradle-pitest-plugin` 1.19.0 and `pitest-junit5-plugin` 1.2.3 alongside it. The plugin is published to the Gradle Plugin Portal only — Maven Central stops at 1.15.0 — so look it up through its marker artifact, not through Central. |
| Kover 0.9.9 | `kover.bb` | Only the setup snippet offered to a project that has no Kover; a project's own version is whatever it applies. |
| JUnit Platform 1.14.4 / 6.1.3 | `aps_kotlin.bb` | Two launchers, because a 1.x launcher cannot run a 6.x engine. The 1.x line is the default: it runs on Java 8+ and its bundled vintage engine executes the JUnit 4 tests `kotlin.test` produces on Android unit tests. |

A bump is not a version string. Every claim the tool's comments and output make about that release is re-measured against the artifact — for PMD, that renaming defeats CPD, with a known-duplicate control proving the detector ran at all; for PIT, that no `HistoryFactory` is registered, since several printed sentences are only true while that holds; for the JUnit launcher, that the jar still shades in `VintageTestEngine` and `JUnitCore` and that `ConsoleLauncher` is still class-file major 52. The PIT bump was also checked against the POC end to end: 1.25.9 and 1.30.0 produce the same 128 mutants, the same 100 exclusions with the same per-file attribution, and a byte-identical list of survivors.

Check for staleness against `https://repo1.maven.org/maven2/<path>/maven-metadata.xml`. The `search.maven.org` JSON index returned wrong maxima for two of these artifacts.

### Mutation

`mutate4kotlin` does not modify your build, and on Kotlin Multiplatform there is nothing it could ask you to add. `gradle-pitest-plugin` creates its extension and its task inside `plugins.withType(JavaPlugin)`, and a KMP module never applies the java plugin, so the plugin applies without complaint and registers no task at all. Instead of reporting that as a missing dependency, the tool asks Gradle once — through an injected init script, writing to no project file — for the test classpath, the module's own compiled output and its source directories, and then drives PIT's own command line. PIT has no such limitation: it wants a classpath, not a source set. Where the plugin *does* register a task, that task runs instead. Either way the number is produced by PIT inside the tool, never by a task the project wrote.

State lives in `.mutate4kotlin/`: `manifest.json` and `exclusions.txt`. No history file, and no differential runs. PIT's open-source build dropped its file-based history store after 1.22.1 — `ErroringHistoryFactory` replaced `DefaultHistoryFactory`, and no `HistoryFactory` is registered, still true in the pinned 1.30.0 — so incremental analysis is a commercial plugin now and asking for it ends the run before the first mutant. Every run is a full run.

`--module :shared` narrows a multi-module build to one module. `--scan` prints what the tool would do, including the candidate runs cached from the last real run, and performs no build.

It asks Gradle which class implements the `pitest` task, not merely whether the name resolves. A task named `pitest` that is a plain `JavaExec` is refused by name, class, and reason — not because the tool needs that task, but because it writes into the same `build/reports/pitest` directory, so leaving both in place makes it impossible to say which run a report came from. `kover` gates the same way on `kotlinx.kover.`.

Every line in `exclusions.txt` is a class glob followed by `# reason`. A line without a reason is a hard error, not a warning — an unexplained exclusion is a hole in the mutation gate that reads as a pass.

**Who wrote the line is read off the compiler's own table, not guessed.** The coverage tools answer "is this class generated" by asking whether a source file for it exists under any `src`, which catches every generator without naming one — but it cannot see inside a class that is partly the author's. The class the Compose compiler builds for a composable's lambdas is exactly that: on the POC, 32 of its 113 mutants sat on hand-written lines and 81 were `Column.kt`, `Layout.kt` and `Composer.kt` inlined into it. Excluding the class wholesale, which is what a coverage row leaves you no choice but to do, hid a surviving mutant on a hand-written `if` — a real missing test, reported as nothing at all. So mutation reads the `SourceDebugExtension` attribute instead: Kotlin writes a JSR-45 SMAP into every class that inlines anything, mapping each compiled line back to the file it was written in, and the excluded mutants are then counted **by source file name** in the report. Two strata are present and only the first is read; `KotlinDebug` maps an inlined line to the call site in the author's file, and taking it would call every inlined line hand-written. The one thing the table cannot describe is in-place rewriting — the `Composer` parameter and `$changed` mask the Compose plugin adds to a function that already existed — so that stays a narrow, named rule about one plugin.

**PIT mutates JVM bytecode.** `iosMain`, Kotlin/Native, and the Swift sources get no mutation coverage at all, and no tool in this space changes that today. That is why the constitution names Kotest and tells the architect that property tests are the primary mechanical evidence for the non-JVM half of the project. It is a real gap, stated rather than papered over.

### Static analysis

`detekt` ships a Kotlin Multiplatform baseline — `templates/detekt-kmp.yml` — and applies it on every run, layered under the project's own `detekt.yml` if it has one, on top of detekt's defaults. The order is the mechanism: detekt applies configs left to right and later ones win, so a project overrides the baseline by writing its own file, and the baseline can never override the project. `--config` adds a file to the chain; it does not replace the baseline, for the same reason `mutate4kotlin` refuses a hand-written `pitest` task. Which corrections apply is not a per-run decision for the role running the tool.

**The baseline corrects; it does not quieten.** It switches no rule off, and it holds exactly two entries:

- `FunctionNaming` skips `@Composable`. A composable that returns `Unit` is PascalCase because the Compose API guidelines require it, and Android Studio's own `ComposableNaming` lint reports the camelCase form detekt asks for — so obeying detekt here means failing the framework's own check. The exclusion is scoped to the annotation, not to a path: `fun MainViewController()` in `iosMain` has no `@Composable` and stays reported, because it is PascalCase for Swift's benefit and that is the author's call to defend.
- `androidHostTest` is added to the fifteen rules that ship detekt's test-source-set exclusion list. None of them mentions it — detekt 1.23.8 predates the `com.android.kotlin.multiplatform.library` plugin that named the source set. Measured with two byte-identical files, one in `androidHostTest` and one in `androidUnitTest`: two findings and none. The names are appended rather than replaced, so nothing detekt already excused is switched back on.

Everything else measured on a real Compose Multiplatform project is still reported, and the file lists what and why. `MatchingDeclarationName` on a `Platform.android.kt` is the interesting one: detekt already strips the platform suffix — its `multiplatformTargets` option lists `ios` and `android` — so it is asking for `AndroidPlatform.android.kt`, which keeps the suffix and satisfies the rule. A satisfiable request is a finding, not a false positive. If detekt is noisy on a project, that is the project's `detekt.yml` to write, with the reason next to each entry.

A config path containing a comma is refused before anything runs. detekt separates its config files with commas and refuses the option twice — measured: *"Can only specify option --config once"* — so such a path would be read as two file names that do not exist, and the report would be shaped by rules nobody chose.

### Flags the wrappers rewrite

Two wrappers rewrite arguments before the real tool sees them, so the constitution's limits hold even when an agent types something else:

- `mutate4kotlin` — drops `--mutate-all`, and pins `--max-workers 4` on any run that is not `--scan` or `--update-manifest`.
- `gherkin-mutator` — injects `--runner-worker "aps-kotlin worker"` and `--generated-dir $(aps-kotlin generated-dir)` when they are absent, downgrades `--level full` to `hard`, and pins `--workers 4`.

Both flags `gherkin-mutator` needs are things an agent cannot guess: `--runner-worker` is required by the specification and has no default, and `--generated-dir` defaults to `<work-dir>/generated`, which is never where a Kotlin test source set keeps generated code. An explicit `--runner-worker` or `--generated-dir` still wins; injection only fills a gap.

`--level full` is downgraded because it mutates every example value in every scenario. At roughly half a second per mutation that turns a review into an hour, and the four workers are pinned so one agent cannot saturate the machine the other five are sharing.

### The acceptance pipeline

`aps-kotlin` is the piece upstream leaves to each project. Its commands:

```sh
aps-kotlin scan            # what is wired, what is missing — start here
aps-kotlin scaffold        # write the runtime and the handler template, once
aps-kotlin generate <ir> <out-dir>
aps-kotlin acceptance      # run the generated tests through Gradle
aps-kotlin generated-dir   # print where the generated entry points live
aps-kotlin worker          # runner adapter for gherkin-mutator
```

Running acceptance tests is three commands, in this order:

```sh
gherkin-parser features/login.feature build/acceptance/login.json
aps-kotlin generate build/acceptance/login.json "$(aps-kotlin generated-dir)"
aps-kotlin acceptance
```

Parse into `build/acceptance/`, not a scratch directory: the generated tests read that IR file when they run.

`scaffold` writes the runtime and `ApsStepHandlers.kt` into a JVM test source set — `androidUnitTest` if the module has one, then `jvmTest`, then `test`. Not `commonTest`, because the runtime reads the IR from disk and `commonTest` cannot do file IO without an `expect`/`actual` pair.

`ApsStepHandlers.kt` is written once and never rewritten; it belongs to the coder. Everything under the generated directory is regenerated on every `generate`, so an edit there is lost.

Handler patterns capture the **placeholder name**, not the value. The runtime reads the value from the example row at run time, which is precisely what lets `gherkin-mutator` change a value and re-run the same already-compiled tests. A step with no matching handler fails; so does a step with two. Both are intended.

This is not Cucumber. The APS specification requires that generated entry points not parse the source `.feature` file, and a Cucumber runner parses the feature file on every run — so a Cucumber-JVM layer would satisfy the Gherkin syntax and violate the pipeline it was meant to implement, and `gherkin-mutator` would have nothing stable to mutate against.

## Acceptance Tiers

Kotlin gives you two honest places to run an acceptance test, and conflating them is how a mutation gate quietly stops meaning anything.

**Tier 1** is what `aps-kotlin` generates: plain JVM host tests, no emulator, no simulator. Fast enough to mutate. It is the tier `gherkin-mutator` measures and the tier that carries the specification. The coder builds it; on six-pack the hardender mutates it, and on four-pack the architect does.

**Tier 2** is the device tier: Espresso or Compose UI tests on an Android emulator, XCUITest on an iOS simulator. It proves the assembled app on a real runtime. Only six-pack has it, only QA owns it, and it comes from the specifier's end-to-end QA specification rather than from the Gherkin.

What decides the tier is the runtime, not the word "UI". A Compose test driven by Robolectric renders on the JVM in seconds, in the same host test source set and the same Gradle task, so it is Tier 1: `kover` measures it and `mutate4kotlin` mutates it. Composables are covered, not excused.

**Tier 2 is never mutated.** A mutation run needs one full suite execution per mutant; at minutes per scenario that never finishes. Worse, a device flake would be scored as a killed mutant — a pass that nothing earned. The constitution states this in `local-engineering.prompt` on both packs that could get it wrong.

Neither tier substitutes for the other, and neither substitutes for unit tests.

## Status Of This Fork

Honest about what has been exercised:

- The Babashka test suite passes: 235 tests, 1013 assertions, 0 failures, 0 errors. One pre-existing flake in `pack_ui_test` (`pack-board-serializes-concurrent-audit-increments`) reproduces on pristine upstream under parallel load and is not from this fork; measured here at roughly one failure in five runs, losing two of eight concurrent increments, so the name overstates what it does.
- One genuine upstream bug is fixed here: `test/swarmforge/handoff_test.clj` built an unquoted shell string, so any checkout path containing a space broke the handoff-daemon test.
- `test/swarmforge/kotlin_support_test.clj` covers the layer the Kotlin tools share: path globbing, telling generated code from the author's, and identifying a Gradle task by its class. All six tools are exercised end to end as subprocesses — against fixture reports, against a stand-in `gradlew` that replies with the block real Gradle prints, against a stand-in `pmd` that records the file list it was handed, and against a stand-in `java` that records the command line detekt was invoked with, so the Swift search and the config chain can both be asserted without a 50 MB download. detekt's baseline is additionally checked as data: every rule detekt ships with a test-source-set exclusion list must have `androidHostTest` added and none of detekt's own names removed, and the version it was measured against must still be the version the tool pins.
- `kover`, `crap4kotlin`, `dry4kotlin`, `detekt` and `mutate4kotlin` have been run against a real Kotlin Multiplatform project — Gradle 9.1.0, Kotlin 2.4.10, AGP 9.0.1, JVM 21, Compose Multiplatform — and three defects found that way are fixed: report globs that never matched a single-module project, coverage that counted generated code, and a task check that confirmed a plugin by the name of its task. That project has a hand-written `JavaExec` called `pitest`, so it is the case that proved the third one: `mutate4kotlin` used to accept it and would have reported PIT numbers from a task the plugin never registered.
- Composable coverage is measured, not assumed. A Robolectric compose test in `androidHostTest` took the `@Composable` file in that project from 0% to 100% line coverage, and the lambda-holder class the Compose compiler synthesises from 0 of 19 lines to 19 of 19, with no emulator and inside the ordinary `testAndroidHostTest` task. The constitution said covering a composable needed a UI test this toolchain does not run; that was wrong, and it now says how to cover one instead.
- **Two silent misses were found by running the tools on a project laid out the way a real one is, and both are the same mistake.** `dry4kotlin` asked for `.swift` files inside `src` directories; Xcode puts none there, so on every KMP project it analysed zero Swift files and printed `0 Swift` on the line above "this is the only constitution tool here that reaches iosMain and Swift". `aps-kotlin` looked for the host tests in `androidUnitTest`, `jvmTest` and `test`, and a module applying `com.android.kotlin.multiplatform.library` calls them `androidHostTest`; when none matched it invented a directory, so the scaffold succeeded, Gradle compiled nothing in it, and acceptance mutation would have reported a clean run over zero tests. Neither failure looked like a failure. Both are fixed, and `dry4kotlin` and `aps-kotlin scan` now print the search rule beside the count so a zero can be told from a blind spot.
- **`aps-kotlin` has never run a Gradle build**, and `aps-classpath.init.gradle` has never been executed at all. `aps-kotlin scan` has been run against a real Kotlin Multiplatform project — that is what found the source-set defect above — but `scan` starts no build. When Gradle cannot be reached, `aps-kotlin worker` fails with instructions to pass `--classpath-file <file>` instead; that manual path is the one the fixture tests exercise. Expect to shake out real-world details on first contact with a live build.
- **A tool can report an analysis it never performed, and detekt was doing it in the other direction.** Its defaults ask a `@Composable` to be camelCase, which the Compose API guidelines forbid and Android Studio's own lint reports, so an agent measuring a Compose project would have seen a finding on every composable it wrote and no way to tell those from real ones. The same read of detekt's shipped config found `androidHostTest` missing from all fifteen test-source-set exclusion lists, so fifteen rules detekt intends off in test code were on in the host tests of any `com.android.kotlin.multiplatform.library` module. Both are corrected by a baseline that ships with the tool; the four findings measured on a real project and deliberately left reported are listed in it, with reasons.
- **A comment can be false without anything breaking, and one here was.** `dry4kotlin` explained its use of `--report-file` by saying CPD writes its report to stderr when no report file is given. It does not, and did not when that was written: measured on both 7.26.0 and 7.27.0, the report goes to stdout as clean XML and only errors go to stderr. The decision was right, so nothing ever misbehaved and nothing ever contradicted the sentence — which is why it was believed twice while diagnosing something else during the version review. The reason is now the true one, and the same review found the test suite pinning PMD's version by hand: desynced from the tool on purpose, it downloads 50 MB of real PMD and finishes green, so it now reads the version out of the tool.
- **Versions were reviewed against Maven Central metadata, and four of the eight pinned artifacts were stale.** PMD 7.26.0 → 7.27.0, PIT 1.25.9 → 1.30.0, Kover 0.9.8 → 0.9.9, JUnit Platform launcher 1.12.2 → 1.14.4. The Kover pin had drifted because the version lived inside a string with a `Measured on Kover 0.9.9` comment beneath it and nothing tying the two together; it is one named `def` now. detekt 1.23.8, `gradle-pitest-plugin` 1.19.0, `pitest-junit5-plugin` 1.2.3 and the 6.x launcher 6.1.3 were already current.

## Constitution Structure

Each runnable branch contains a `swarmforge/` directory with this general layout:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-engineering.prompt
      local-workflow.prompt
      ...
  roles/
    <role>.prompt
    ...
```

`constitution.prompt` is the entry point. Runnable branches normally use it to tell agents to read every file in `swarmforge/constitution/articles/`.

Shared default articles live on `main` under:

```text
swarmforge/constitution/articles/
  engineering.prompt
  handoffs.prompt
  workflow.prompt
```

`get-swarm-forge` always copies shared articles from `main` (or `SWARMFORGE_BASE_BRANCH`). Packs must not ship `engineering.prompt`, `workflow.prompt`, or `handoffs.prompt`. Those filenames are law from `main`.

Pack-specific additions and exceptions use explicit local filenames:

- `project.prompt` for the workflow's project shape and local topology.
- `local-engineering.prompt` for workflow-specific engineering rules.
- `local-workflow.prompt` for workflow-specific flow rules.

The `local-*.prompt` naming convention means "add to or specialize the shared default article for this pack." Use it for extra requirements, exceptions, or narrower instructions. Do not replace a shared article by committing the same filename.

For example, `main` provides `workflow.prompt`, while `six-pack` adds `local-workflow.prompt` for QA-specific handoff behavior.

## Roles

Each role in `swarmforge/swarmforge.conf` maps to a corresponding `swarmforge/roles/<role>.prompt` file.

## How It Works

In a runnable branch:

1. SwarmForge reads `swarmforge/swarmforge.conf`.
2. The project is already composed by `get-swarm-forge`: shared helper scripts and `engineering.prompt` / `workflow.prompt` / `handoffs.prompt` from `main`, plus pack-owned files (`swarm`, `swarmforge.conf`, role prompts, `constitution.prompt`, `project.prompt`, `local-*.prompt`). Shared article filenames are never taken from the pack.
3. Startup uses that composed `swarmforge/constitution/articles/` tree. Pack specialization is `local-*.prompt` and other pack-owned files, not a same-name override of a shared article.
4. Startup validates the configured role prompts, helper scripts, and terminal adapters.
5. If the target directory is not already a git repository, startup initializes one and creates the first commit.
6. Startup creates one git worktree per configured role under `.worktrees/`, unless the role is assigned to `master` or `none`.
7. Startup copies the composed `swarmforge/scripts/` and `swarmforge/constitution/` trees into each role worktree and puts that local scripts directory on each agent's `PATH`, so agents use local handoff helpers without reaching back into the master checkout.
8. SwarmForge creates tmux sessions, launches each configured backend in its assigned worktree, starts the pack dashboard, and opens a Terminal surface only for `window` (visible) roles.
9. Startup starts an OS-specific sleep inhibitor when one is available, and cleanup stops it with the swarm.
10. Roles communicate through daemon-delivered handoff files. Agents create validated drafts with `swarm_handoff.sh`, accept work with `ready_for_next.sh`, and complete work with `done_with_current.sh`.

## Handoff Protocol

Startup syncs the shared helper scripts into every role worktree under `swarmforge/scripts/` and puts that local directory on the agent's `PATH`. Agents do not send tmux messages directly. The launcher starts `handoffd.bb`, which owns tmux socket access, watches each agent outbox, copies validated handoff files into recipient inboxes, and sends only generic wake-up notifications.

Agents interact with handoffs through three helper scripts:

- `swarm_handoff.sh <draft-file>` validates outbound handoffs. Notes queue
  immediately; Git handoffs use the audit gate described below.
- `ready_for_next.sh` accepts work using the role's configured receive mode.
- `done_with_current.sh` completes the current task or batch using the role's configured receive mode.

Outbound drafts use one of two message types. A git handoff points the recipient at a committed state. The commit abbreviation must be exactly 10 hexadecimal characters; `swarm_handoff.sh` validates that it resolves to a single commit and canonicalizes it before queuing the handoff. The first valid Git handoff call returns `AUDIT_REQUIRED` without queueing or completing the sender's current inbox item, and increments the task card's audit counter. The sender must re-read the complete task and referenced sources, trace every requirement and constraint to role-appropriate work and evidence, examine boundaries and failure cases, fix every finding, rerun applicable checks, and repeat the audit. Only an unchanged second call queues the handoff without another increment, after which any required approval is requested. A changed draft, task, sender, recipient set, or commit invalidates the earlier audit and creates a new counted challenge.

```text
type: git_handoff
to: <role>[,<role>...]
priority: NN
task: <short-stable-task-name>
commit: <10-character-commit-abbrev>
```

A note is one short freeform message:

```text
type: note
to: <role>[,<role>...]
priority: NN
message: <one line, max 80 chars>
```

The helper generates the delivered payload. Agents do not write long handoff bodies, branch names, queue filenames, or tmux commands.

Recipient agents run `ready_for_next.sh` when notified or after restart. It dispatches to the task or batch helper configured for that role. If it prints `NO_TASK`, they stop waiting for work. If it prints `TASK: <path>`, they treat the printed `TASK_NAME` and `PAYLOAD` as the task. If it prints `BATCH: <path>`, they process the printed `BATCH_ITEM` entries in helper-delivered order. If a wake-up arrives while an agent is already working, it can ignore the wake-up. `done_with_current.sh` completes the current item only: it prints `MAIL_WAITING` when more mail is queued, or `NO_TASK`. The agent then runs `ready_for_next.sh` if mail is waiting.

The durable handoff files and lifecycle headers replace the old logbook and resend queue. Runtime handoff state lives under `.swarmforge/handoffs/` in each worktree, with `outbox`, `sent`, `failed`, and `inbox` subdirectories. Agents should not hand-edit, merge, stage, or commit handoff runtime state. See [swarmforge/handoff-protocol.md](swarmforge/handoff-protocol.md) for the full protocol.

## The `swarmforge.conf` File

`swarmforge/swarmforge.conf` defines the swarm window-by-window. Each line has this form:

```conf
window-invisible <role> <agent> <worktree> [task|batch] [forward-only|back-one|back-all] [extra-cli-args...]
window <role> <agent> <worktree> [task|batch] [forward-only|back-one|back-all] [extra-cli-args...]
```

`window-invisible` starts the agent in tmux without a Terminal window (the pack default). `window` also opens a Terminal surface for that role.

The optional receive mode defaults to `task`. Use `batch` for roles that should consume all currently queued equal-priority handoffs as one batch.

The optional propagation token defaults to `forward-only`. `back-one` queues a merge-only copy to the previous window; `back-all` queues merge-only copies to every earlier window. Those copies do not move the card. The card goes Done only when the last window queues a `git_handoff`.

Any fields after receive-mode and the propagation token are passed directly to the agent CLI as additional arguments. If you omit those tokens, extra arguments may start at the fifth field:

```conf
window coder copilot wt-coder --yolo
window architect claude wt-arch task --dangerously-skip-permissions
```

You can define as many windows as your project needs. Each `role` maps to a corresponding prompt file at `swarmforge/roles/<role>.prompt`, so a config containing `architect`, `coder`, `reviewer`, `research`, and `release` windows would expect:

- `swarmforge/roles/architect.prompt`
- `swarmforge/roles/coder.prompt`
- `swarmforge/roles/reviewer.prompt`
- `swarmforge/roles/research.prompt`
- `swarmforge/roles/release.prompt`

This lets each project choose its own swarm shape instead of being locked to a fixed set of roles.

Example config (pack default is invisible):

```conf
window-invisible specifier grok master
window-invisible coder codex coder --yolo
window-invisible cleaner codex cleaner batch --yolo
window-invisible architect grok architect batch
```

In the example above, the agents run in these worktrees:

- `specifier` -> main working directory on `master` (master agent: New Task and chat)
- `coder` -> `.worktrees/coder`
- `cleaner` -> `.worktrees/cleaner`
- `architect` -> `.worktrees/architect`

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## tmux Behavior

SwarmForge uses a project-specific tmux socket recorded in `.swarmforge/tmux-socket`, so each project swarm is isolated from other tmux sessions. It also honors tmux `base-index` and `pane-base-index` settings when launching agents and sending notifications, so configurations that number windows or panes from `1` work without requiring users to change their tmux preferences.

## Terminal Behavior

Pack branches use `window-invisible`, so this adapter does not open a window per role. Visible `window` lines still open trackable terminal windows or tabs through a small terminal backend adapter.

Default detection:

- If AppleScript is available, SwarmForge opens macOS Terminal.app windows.
- Otherwise, if `wt.exe` is available, SwarmForge opens Windows Terminal windows.
- Otherwise, SwarmForge attaches the cleanup tmux session in the current shell.

After copying a runnable branch, set `SWARMFORGE_TERMINAL` to override detection:

```sh
SWARMFORGE_TERMINAL=ghostty ./swarm
SWARMFORGE_TERMINAL=terminal-app ./swarm
SWARMFORGE_TERMINAL=windows-terminal ./swarm
SWARMFORGE_TERMINAL=none ./swarm
```

Use `ghostty` when you want SwarmForge to open Ghostty tabs instead of the default Terminal.app windows. Use `windows-terminal` when you want SwarmForge to open Windows Terminal windows from WSL. Use `none` when you want SwarmForge to skip terminal automation and attach the cleanup tmux session in the current shell.

### Adding A Terminal Backend

The shared terminal backends are carried on `main` under `swarmforge/scripts/terminal-adapters/`. Runnable branches copy those scripts at startup. To add a new backend, update `main` by creating one file named after the backend:

```text
swarmforge/scripts/terminal-adapters/wezterm.sh
```

The file must define this small contract:

```sh
terminal_backend_label() {
  echo "WezTerm"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  # Open a terminal surface that runs:
  # cd "$WORKING_DIR" && exec tmux -S "$TMUX_SOCKET" attach-session -t "$session"
  #
  # Print a stable window/tab id to stdout.
}

terminal_window_exists() {
  local window_id="$1"

  # Return 0 if the id from terminal_open_session still exists.
  # Return nonzero otherwise.
}

terminal_close_window() {
  local window_id="$1"

  # Close the id from terminal_open_session.
}
```

If the terminal can open sessions but cannot return stable ids for open/check/close, keep `terminal_backend_can_open_sessions` as `return 0` and set `terminal_backend_tracks_windows` to `return 1`. SwarmForge will open one surface per session and skip the watchdog for that backend. `swarmforge/scripts/terminal-adapters/windows-terminal.sh` is an example of this launch-only style.

If the backend cannot open sessions at all, set both capability functions to `return 1`; SwarmForge will attach the cleanup tmux session in the current shell. Only edit `swarmforge/scripts/swarm-terminal-adapter.sh` when adding aliases or changing default auto-detection.

## Window Behavior

The usual shutdown path for a pack is **Teardown** on the dashboard, not closing a Terminal window.

If you use visible `window` lines, each agent window is attached to a tmux session. Terminal selection, copy, and paste may follow tmux and terminal-emulator rules rather than ordinary text-field behavior. If copy or paste feels unusual, check whether tmux copy mode is active before assuming the agent is stuck.

The first **visible** window in `swarmforge.conf` is the cleanup window. Closing that window shuts down tmux sessions, remaining tracked windows, and the swarm.

Closing any other tracked window is non-destructive. The watchdog reopens that window and attaches it back to the same tmux session, so the agent state and terminal history remain intact. This is often the simplest way to recover a window that has landed in an unfamiliar tmux mode or otherwise feels stuck.
