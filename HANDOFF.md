# ReportSystem — Handoff

Velocity proxy plugin for player reporting & staff moderation workflows. Maven, Java 17, single shaded jar.

_Last updated 2026-06-29._

## What it is

Proxy-side report & chat-log system for [Velocity](https://velocitypowered.com). Players file `/report`s; staff triage them via `/reports` (open queue) and `/reporthistory` (closed), with priority scoring, live chat-log capture, atomic YAML or MySQL persistence, optional Discord webhooks, and an optional built-in HTTP server that serves HTML chat logs behind cookie auth. Runs only on the proxy — not a backend (Paper/Bukkit) plugin.

Main class / plugin id: `io.github.e1ixyz.reportsystem.ReportSystem` / `reportsystem`. Plugin version `3.0.0` (pom.xml:8).

## Run it

```bash
mvn -DskipTests package        # -> target/reportsystem-3.0.0.jar (shaded)
```
Drop the jar in Velocity's `plugins/`, start the proxy (generates `plugins/ReportSystem/`), edit `config.yml`. Toolchain here: Java 21 (Temurin), Maven 3.9.11. No test suite exists — `package` is the only gate.

## Architecture & file map

`src/main/java/io/github/e1ixyz/reportsystem/`
- `ReportSystem.java` — `@Plugin` entry; wires services, registers commands, `onInit`/`onShutdown`/`reload`.
- `commands/` — `ReportCommand`, `ReportsCommand`, `ReportHistoryCommand`.
- `service/` — `ReportManager` (in-memory store + stacking + priority scoring + persistence), `ChatLogService` (per-player rolling chat buffer + live append), `AuthService` (web login codes/sessions), `Notifier` (Discord webhooks), `PlayerNotificationService`, `ReportMenuService`, `WebServer` (embedded `com.sun.net.httpserver`), `HtmlExporter`.
- `storage/` — `ReportStorage` interface + `FileReportStorage` (YAML, default) + `MysqlReportStorage`.
- `model/`, `util/` (`QuickActions.closingTag` is the canonical MiniMessage close-tag helper), `config/`.
- `src/main/resources/` — `velocity-plugin.json`, `config.yml` (documents every setting).

Threading: Velocity event threads, the scheduler, `ChatLogService`'s save thread, and HTTP handler threads all touch shared `Report` objects — see Gotchas.

## Current state

Builds clean on Velocity API `3.4.0` (latest **stable**; newer repo versions are only snapshots). 2026-06-29 session did a bug sweep + safe dep bumps:

- **Concurrency**: `Report.chat` → `CopyOnWriteArrayList`; `ReportManager` mutators (`appendChat/assign/unassign/close/reopen/updateSourceServer`) now `synchronized` (same monitor as `fileOrStack`); `getOpenReportsDescending()` snapshots priority scores before sorting (was crashing TimSort under live mutation).
- **Security**: `AuthService` session signing replaced `String.hashCode()` with HMAC-SHA256 + constant-time compare; warns once if `auth.secret` is unset/`change-me`. `WebServer` adds a `toRealPath()` symlink-escape check and `Secure` cookie when `external-base-url` is https.
- **Reliability**: `volatile` on reload-swapped config fields (`Notifier`, the 3 command classes); null-`typeId` guard in `ReportManager.fromMap`; `ProxyShutdownEvent` now stops `WebServer`; `Notifier` escapes JSON control chars; stack-badge close-tags reuse `QuickActions.closingTag`.
- **Off the event thread**: `ChatLogService` persists chat via a single-thread daemon executor (FIFO preserved — a generic async scheduler would reorder log lines).
- **Deleted**: `HttpServerService.java` (dead, unauth'd) and the entire stale `com/example/reportsystem` duplicate tree.
- **Deps bumped** (shaded/relocated): gson `2.14.0`, snakeyaml `2.6`, mysql-connector-j `9.7.0`. Velocity/Adventure/slf4j stay pinned to the platform.

Changes are in the working tree, **not committed**.

### Known gaps / deferred
- **MySQL opens a connection per operation** (`MysqlReportStorage`) — `appendChat` saves per chat line. No leak (try-with-resources), but HikariCP pooling was deferred as YAGNI until real MySQL scale.
- Cosmetic findings left as-is: pagination "clamped" message edge case; closed-report ordering for pre-`closedAt` legacy reports.

## Gotchas

- **`target/` is committed to git.** Build output (`.class` files, jars) is tracked, so a `package` dirties the tree and old artifacts linger (e.g. stale `target/classes/com/example/` from the now-deleted duplicate). Consider gitignoring `target/`.
- **Adventure is shaded but *not* relocated** into the jar (pom.xml has no `net.kyori` relocation) — pre-existing; Velocity provides Adventure at runtime. Don't bump minimessage to 5.x: Velocity 3.4.0 ships Adventure 4.x.
- All `Report` mutation funnels through `ReportManager`'s monitor; if you add a new mutator, make it `synchronized` too.
- HTML chat export iterates `Report.chat` in insertion order with no time-sort — that's why chat saves must stay FIFO-ordered.

## Where to look first / next

Start at `ReportSystem.onInit` to see how everything wires up, then `ReportManager` (the core). Likely next tasks: commit the sweep; gitignore `target/`; if MySQL is used at scale, add connection pooling.
