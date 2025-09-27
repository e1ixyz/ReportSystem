# ReportSystem (Velocity)

ReportSystem is a full-featured player reporting and moderation workflow plugin for [Velocity](https://velocitypowered.com) proxy networks. It streamlines how staff capture incident reports, inspect live evidence, triage and resolve cases, and optionally integrate web views or Discord notifications—all while persisting data safely on the proxy.

## Feature Highlights

- **Dynamic reporting UX** – `/report <type> <category> …` supports configurable report types/categories, automatic stacking of duplicate reports, and context-aware tab completion.
- **Live evidence capture** – chat logs are buffered for every player and appended to open reports in real time, with HTML export and web viewer support when enabled.
- **Staff consoles** – `/reports` (open queue) and `/reporthistory` (closed queue) expose pagination, filters, quick actions (claim, assign, close, reopen) and MiniMessage-powered UI components.
- **Priority scoring** – a multi-factor system ranks open reports using stack count, recency decay, severity mappings, captured evidence, assignment state, aging, and SLA breaches. `/reports debug <id>` explains the exact score breakdown.
- **Persistence & reload safety** – each report is stored atomically as YAML under the plugin data directory, allowing restarts without data loss. `/reports reload` hot-reloads config and updates command instances.
- **Notifications** – staff receive in-game alerts with hover/click actions, and optional Discord webhook delivery occurs asynchronously.
- **Optional built-in web server** – serve HTML chat logs through a small HTTP server with cookie-based auth or via any external web stack.

## Getting Started

1. **Build the plugin**
   ```bash
   mvn -DskipTests package
   ```
   The shaded jar is written to `target/reportsystem-<version>.jar`.

2. **Install**
   - Copy the jar into your Velocity proxy’s `plugins/` directory.
   - Start (or restart) Velocity to let the plugin generate its data folder (`plugins/ReportSystem/`).

3. **Configure**
   - Edit `plugins/ReportSystem/config.yml`. The defaults in `src/main/resources/config.yml` document every setting.
   - If you plan to serve chat logs on `http://localhost:8085`, enable the HTTP server and set:
     ```yaml
     http-server:
       enabled: true
       external-base-url: "http://localhost:8085"
       bind: "127.0.0.1"
       port: 8085
       base-path: "/"
     ```
     Staff can generate login codes via `/reports auth` if auth is enabled.

4. **Permissions** (Velocity-style strings)
   - `reportsystem.reports` – access `/reports`, `/reporthistory`, bypass cooldown, and request auth codes.
   - `reportsystem.notify` – receive in-game notifications when new reports arrive or when reloads occur.
   - `reportsystem.forceclaim` – override another staff member’s claim via `/reports claim <id>`.
   - `reportsystem.admin` – perform `/reports reload`, `/reports logoutall`, and bypass force-claim restrictions.

5. **Use the commands**
   - Players: `/report <type> <category> [<target>] <reason…>` (configured types appear in tab completion). The plugin enforces a configurable cooldown for non-staff.
   - Staff queue: `/reports` with subcommands `claim`, `assign`, `unassign`, `close`, `chat`, `view`, `search`, `debug`, `reload`, `auth`, `logoutall`, plus filters like `/reports <type> [category]`.
   - History: `/reporthistory` with analogous subcommands `page`, `view`, `chat`, `reopen`.

## Priority Scoring System

Enable or disable factors in `config.yml` under the `priority` section. Each factor contributes `weight × value` to the final score:

| Factor            | Value Definition                                                                                                    |
|-------------------|----------------------------------------------------------------------------------------------------------------------|
| `use-count`       | Number of stacked reports (minimum 1).                                                                               |
| `use-recency`     | Exponential decay (`exp(-age / tau-ms)`), using the latest update time.                                             |
| `use-severity`    | Looks up `severity-by-key` entries keyed by `type/category` (defaults to 1 if unset).                                |
| `use-evidence`    | Normalised chat evidence (`min(1, chat_lines / 10)`); zero when no captured chat exists.                            |
| `use-unassigned`  | Adds the weight if the report has no assignee.                                                                       |
| `use-aging`       | `log1p(age minutes)` for broad escalation over time.                                                                 |
| `use-sla-breach`  | Compares age against configured `sla-minutes` per type/category and scales up to 1 once the threshold is doubled.    |

Run `/reports debug <id>` to view a breakdown showing each factor’s raw value, applied weight, contribution, and explanatory text, plus the tie-breaker used when scores tie.

## Data Storage

- Reports live in `plugins/ReportSystem/reports/<id>.yml`.
- HTML exports (if enabled) are written under `plugins/ReportSystem/<html-export-dir>/<id>/index.html`.
- The plugin keeps an in-memory cache of reports and chat logs, refreshing from disk on startup and saving atomically after every change.

## Web & Authentication

- **HTML exporter** – `/reports chat <id>` automatically exports to HTML when the HTTP server is enabled or when staff request the inline page.
- **HTTP server** – uses Java’s built-in `com.sun.net.httpserver` with support for authenticated sessions, cookie names, open-path exceptions, and login code issuance via `/reports auth`. When `public-base-url` or `http-server.external-base-url` is configured, MiniMessage buttons link to the appropriate public URL.

## Discord Notifications

`discord.enabled: true` triggers webhook delivery on report creation, assignment, closure, and reopen. Delivery runs asynchronously through Velocity’s scheduler to avoid blocking command execution. Configure username, avatar, and webhook URL in `config.yml`.

## Development Notes

- **Build** – `mvn package` (the project is a standard Maven module targeting Java 17).
- **Testing** – No automated tests are included; run the plugin on a Velocity test network to validate changes.
- **Code style** – MiniMessage is used for all chat output. Whenever you add new MiniMessage strings or commands, ensure tab completion mirrors the runtime arguments (see `ReportsCommand` for examples).
- **Persistence Safety** – `ReportManager` writes changes via temporary files and atomic move operations. Avoid editing report YAML directly while the proxy is live to prevent race conditions.

## Contributing

Contributions are welcome—feel free to open merge requests that improve documentation, add tests, or extend command functionality. Please run `mvn -DskipTests compile` to ensure the project still builds.

---

For questions or suggestions, open an issue on the repository or reach out to the maintainers. Happy moderating!
