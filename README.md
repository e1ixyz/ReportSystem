**ReportSystem (Velocity)**
===========================

A modern, staff-friendly report & triage system for Velocity proxies.

It captures context (chat), stacks duplicate reports, supports rich in-chat UI with hover tooltips, and can export HTML chat logs via a tiny built-in web server.

> **Status:** actively evolving. This README reflects the current feature set and the latest config/UX we've discussed.

* * * * *

**✨ Features**
--------------

-   **/report** with dynamic **types/categories** (fully configurable)

    -   player/* types accept a <target>; other types are reason-only

    -   Tab-completion guides the flow and shows a **<reason...> placeholder**

-   **Duplicate stacking** (same target/type/category) with a configurable time window

-   **Chat capture**

    -   Tracks targets mentioned in **open** reports

    -   On report creation, pulls a **rolling buffer of recent lines** (e.g., last 90s) so earlier context isn't lost

    -   View chat logs as **HTML** (web server enabled) or **inline with pagination** (web server disabled)

-   **Staff triage UI (/reports)**

    -   Sorting by priority: **stack count** desc, then tie-break by time

    -   Rich list line: (#ID) (Type / Category) Target [Assigned] [Server]

        with **hover tooltips** for Target/Assigned/Server

    -   Fully configurable **Expand** button label (messages.label-expand, e.g. "^") used **everywhere**

    -   Quick claim/assign actions, search, paging

    -   **Jump to server** button (only shown if the server exists)

-   **Closed report browser (/reporthistory)**

    -   Same look/feel as /reports, with **Expand** + **Reopen** actions

-   **Web viewer** (optional)

    -   Tiny HTTP server to serve **exported HTML chat logs**

    -   Lightweight auth with one-time codes (/reports auth) and cookie sessions

-   **Discord webhook** integration (optional)

-   **Persistent storage**

    Each report is a readable YAML file under plugins/ReportSystem/reports/<id>.yml.

> **Note on advanced priority:** The default open-list sort is *count desc, then time*. A richer, multi-factor priority section already exists in config and can be evolved/enabled later.

* * * * *

**🧰 Requirements**
-------------------

-   **Velocity** 3.x

-   **Java 17+**

-   Permissions system compatible with Velocity (LuckPerms etc.)

* * * * *

**📦 Installation**
-------------------

1.  Drop the plugin jar into your Velocity plugins/ folder.

2.  Start the proxy once to generate plugins/ReportSystem/config.yml.

3.  Edit the config (see **Configuration** below).

4.  /reports reload (requires admin permission) or restart the proxy.

* * * * *

**🧑‍💻 Commands**
------------------

### **Player / Staff**

#### **/report <type> <category> [<target>] <reason...>**

-   For player/* types, you **must** include <target>.

-   Non-player types (e.g., server/crash) **do not** take a target; everything after category is the reason.

-   **Tab-completion**:

    -   Position 1: type ids (from report-types)

    -   Position 2: category ids (for the chosen type)

    -   If player type: suggests **online player names** for <target>

    -   At the reason position, suggests **<reason...>** placeholder

-   **Cooldown** applies to non-staff (configurable).

### **Staff Triage**

#### **/reports**

Opens the open-reports list, sorted by priority.

**Subcommands:**

-   /reports page <n> --- paginate the list

-   /reports <type> [category] --- quick filter by type/category

-   /reports view <id> --- expanded view with actions

-   /reports claim [<id>] --- claim the highest-priority open report, or a specific id

-   /reports claimed --- list reports you've claimed

-   /reports close <id> --- close a report

-   /reports chat <id> [page] --- view chat logs

    -   If web server **enabled** → exports/links HTML, **no inline chat** is sent

    -   If web server **disabled** → shows inline chat lines with **pagination**

        ([page] is optional; page size = preview-lines)

-   /reports assign <id> <staff> --- assign someone

-   /reports unassign <id> --- clear assignee

-   /reports assigntome <id> --- quick assign to self

-   /reports unassignme <id> --- quick unassign self

-   /reports search <query> [open|closed|all] --- lightweight search

-   /reports reload --- reload config (admin)

-   /reports auth --- issue one-time code & login link (player)

-   /reports logoutall --- revoke web sessions (admin, player context)

#### **/reporthistory**

Browse **closed** reports.

**Subcommands:**

-   /reporthistory page <n>

-   /reporthistory view <id> --- expanded closed report view

-   /reporthistory reopen <id> --- reopens a closed report

* * * * *

🖱️ In-Chat UI & Behavior
-------------------------

### Open list line (consistent everywhere)

`#123 (Player / Chat) Notch [ModJane] [lobby-1]`

-   **Target** is shown **without a label** and has a hover tooltip:\
    `messages.tip-target: "Target: %name%"`

-   **Assigned** is shown in brackets with a hover tooltip:\
    `messages.tip-assigned: "Assigned: %name%"`

-   **Server** is shown in brackets with a hover tooltip:\
    `messages.tip-server: "Server: %name%"`

-   **Expand** button uses the **global label**: `messages.label-expand` (e.g., `"^"`),\
    with hover `messages.tip-expand`. This applies to all expand buttons:

    -   New-report notifications

    -   `/reports` list

    -   `/reports search` results

    -   `/reports claimed`

    -   `/reporthistory` list

### Expanded view

Shows details (reporter, target, time, count, reason, status, assignee, server).\
Includes action buttons:

-   **Close**

-   **Chat Logs** (link or inline/paginated, depending on web server setting)

-   **Jump to server** (`messages.jump-command-template`, e.g. `/server %server%`)\
    Only shown if the proxy has a server with that name.

### Server detection (for list + expanded view)

1.  The **target's current server** (if target is online)

2.  The report's **sourceServer** (where it was filed from), if present

3.  The **newest chat line's** server

4.  Fallback: `UNKNOWN`

* * * * *

💬 Chat Capture
---------------

-   **Live capture** for any player who is the target of an **open** report.

-   **Rolling buffer**: when a report is created, the last chunk of the target's recent chat (e.g., **last 90 seconds**) is attached so context isn't lost if staff file the report a bit later.

-   **/reports chat <id>**

    -   **Web server enabled** → exports HTML and shows a **browser link** (no local paths)

    -   **Web server disabled** → shows chat in Minecraft with **pagination**

        -   Page size = `preview-lines`

        -   Each line is truncated to `preview-line-max-chars`

* * * * *

🔐 Permissions
--------------

| Permission | Purpose |
| --- | --- |
| `reportsystem.reports` | Staff access to `/reports` suite, bypass `/report` cooldown, web auth (if `auth.require-permission` true) |
| `reportsystem.notify` | Receive in-game notifications for new reports |
| `reportsystem.forceclaim` | Override an existing claim when claiming/assigning |
| `reportsystem.admin` | Admin actions: `/reports reload`, `/reports logoutall`, and force-claim fallback |

> You'll usually grant **`reportsystem.reports`** to moderators, **`reportsystem.admin`** to administrators, and **`reportsystem.notify`** to anyone who should see alerts.

* * * * *

⚙️ Configuration (default)
--------------------------

Below is the current default `config.yml` (GitHub-friendly YAML).\
Use this as a reference; your generated file will include the same keys and comments.

`# === ReportSystem -- Default Configuration ===

# ------------------------------------------------------------------------------------
# CORE
# ------------------------------------------------------------------------------------
allow-self-report: true                 # Allow players to report themselves?
stack-window-seconds: 600               # Seconds to merge ("stack") identical reports (same target/type/category)
export-html-chatlog: true               # If true, chat logs export to HTML (used when web viewer is enabled)
html-export-dir: "html-logs"            # Folder under the plugin data directory for HTML exports
reports-per-page: 10

# Permissions
staff-permission: "reportsystem.reports"         # Staff can use /reports, bypass cooldown, view protected web pages, etc.
notify-permission: "reportsystem.notify"         # Staff who should receive ingame notifications about new reports
force-claim-permission: "reportsystem.forceclaim"  # Staff who may override someone else's claim
admin-permission: "reportsystem.admin"           # Admin-only actions: /reports reload, /reports logoutall, force-claim override fallback

# ------------------------------------------------------------------------------------
# REPORT COOLDOWN
# ------------------------------------------------------------------------------------
# Players must wait this long between /report commands.
# Users with staff-permission bypass this cooldown.
report-cooldown-seconds: 60

# ------------------------------------------------------------------------------------
# INLINE PREVIEW (safety for ingame chat previews)
# ------------------------------------------------------------------------------------
preview-lines: 10
preview-line-max-chars: 200

# ------------------------------------------------------------------------------------
# PUBLIC URLS FOR LINKS SHOWN IN CHAT
# ------------------------------------------------------------------------------------
# Leave blank by default. If you have a public domain later,
# set EITHER public-base-url OR http-server.external-base-url (prefer public-base-url).
public-base-url: ""                      # e.g. "https://reports.example.com"

http-server:
  enabled: false                         # Built-in tiny web server to serve html-logs/
  external-base-url: ""                  # e.g. "https://reports.example.com" (links when public-base-url is blank)
  bind: "0.0.0.0"
  port: 8085
  base-path: "/"

# ------------------------------------------------------------------------------------
# LIGHTWEIGHT WEB AUTH FOR HTTP SERVER
# ------------------------------------------------------------------------------------
auth:
  enabled: false
  cookie-name: "rsid"
  session-ttl-minutes: 1440              # Sliding session TTL (minutes)
  code-ttl-seconds: 120                  # One-time code lifetime (seconds) for /reports auth
  code-length: 6
  secret: "change-me"                    # Change in production; used for signing session ids
  open-paths:                            # Paths accessible without auth (when auth is enabled)
    - "/login"
    - "/favicon.ico"
  require-permission: true               # Require staff-permission to run /reports auth and receive a code

# ------------------------------------------------------------------------------------
# DISCORD WEBHOOK (optional)
# ------------------------------------------------------------------------------------
discord:
  enabled: false
  webhook-url: ""
  username: "ReportSystem"
  avatar-url: ""
  timeout-ms: 4000

# ------------------------------------------------------------------------------------
# MULTI-FACTOR PRIORITY SCORING (scaffolding)
# ------------------------------------------------------------------------------------
# NOTE: Current default open-list sort is: count desc, then time (newest).
# This block defines weights & targets for a future richer scorer.
priority:
  enabled: true

  # Enable/Disable individual factors
  use-count: true
  use-recency: true
  use-severity: true
  use-evidence: true
  use-unassigned: true
  use-aging: true
  use-sla-breach: true

  # Weights (relative importance)
  w-count: 2.0
  w-recency: 2.0
  w-severity: 3.0
  w-evidence: 1.0
  w-unassigned: 0.5
  w-aging: 1.0
  w-sla-breach: 4.0

  # Shaping for recency
  tau-ms: 900000         # 15 minutes

  # Severity baseline by type/category
  severity-by-key:
    "player/cheat": 3.0
    "server/crash": 2.5
    "player/chat": 1.0
    "player/ad": 1.5

  # SLA targets (minutes)
  sla-minutes:
    "player/cheat": 5
    "server/crash": 2
    "player/chat": 15

# ------------------------------------------------------------------------------------
# MESSAGES / LOCALIZATION
# ------------------------------------------------------------------------------------
messages:
  prefix: "<gray>[<blue>Reports</blue>]</gray> "
  no-permission: "<red>You don't have permission.</red>"

  # /report UX
  usage-report: "Usage: /report <type> <category> [<target>] <reason...>"
  invalid-type: "Unknown type or category: %type%/%cat%"
  self-report-denied: "You cannot report yourself."
  report-stacked: "Report stacked into #%id% (now x%count%)"
  report-filed: "Report filed! (ID #%id%)"

  # Common
  not-found: "No such report: #%id%"
  closed: "Closed report #%id%"
  assigned: "Assigned report #%id% to %assignee%"
  unassigned: "Unassigned report #%id%"
  already-unassigned: "Report #%id% is not assigned."
  search-empty: "No matching reports."
  search-header: "Search: %query% (%scope%)"
  page-empty: "No open reports."
  page-header: "Reports Page %page%/%pages%"
  page-header-filtered: "Reports (%type%/%cat%) Page %page%/%pages%"
  reloaded: "ReportSystem reloaded."
  chatlog-none: "No chat messages were captured for this report."

  # Buttons / tooltips
  label-expand: "^"                        # unified EXPAND label --- used everywhere
  tip-expand: "Click to expand"
  tip-open-browser: "Open in browser"
  tip-open-login: "Open login page"
  tip-prev: "Previous page"
  tip-next: "Next page"
  tip-reload: "Reload reports"
  tip-close: "Close this report"
  tip-chat: "View chat logs"
  tip-jump-server: "Connect to this server"
  tip-assign-me: "Assign to me"
  tip-unassign: "Unassign"
  tip-force-claim: "Force-claim"
  tip-target: "Target: %name%"
  tip-assigned: "Assigned: %name%"
  tip-server: "Server: %name%"

  # Expanded view
  expanded-header: "Report #%id% (%type% / %category%)"
  expanded-server-line: "<gray>Server:</gray> <white>%server%</white>"
  expanded-lines:
    - "<gray>Reported:</gray> <white>%target%</white> <gray>by</gray> <white>%player%</white>"
    - "<gray>When:</gray> <white>%timestamp%</white>"
    - "<gray>Count:</gray> <white>%count%</white>"
    - "<gray>Reason(s):</gray> <white>%reasons%</white>"
    - "<gray>Status:</gray> <white>%status%</white>"
    - "<gray>Assignee:</gray> <white>%assignee%</white>"
  open-chatlog-label: "Open chat log"
  auth-code-ttl-s: "120"
  jump-command-template: "/server %server%"

# ------------------------------------------------------------------------------------
# DYNAMIC REPORT TYPES
# ------------------------------------------------------------------------------------
report-types:
  player:
    display: "Player"
    categories:
      chat: "Chat"
      cheat: "Cheating"
      ad: "Advertising"
      grief: "Griefing"
  server:
    display: "Server"
    categories:
      crash: "Crash"
      lag: "Lag"
      dupe: "Duplication Bug"

# ------------------------------------------------------------------------------------
# STACK BADGE THRESHOLDS & COLORS (ui only)
# ------------------------------------------------------------------------------------
stack-thresholds:
  yellow: 3
  gold: 5
  red: 10
  dark-red: 15

stack-colors:
  yellow: "<yellow>"
  gold: "<gold>"
  red: "<red>"
  dark-red: "<dark_red>"`

* * * * *

🔎 How stacking works
---------------------

-   When a new report matches an **open** report by `(reported + typeId + categoryId)` and is within `stack-window-seconds`, we **increment `count`** and **append the new reason** (`prev | new`).

-   Outside the window, a **new report** is created.

* * * * *

🌐 Web Viewer & Auth
--------------------

-   If `http-server.enabled: true`:

    -   `/reports chat <id>` **exports** an HTML page under `html-export-dir` and shows a **clickable link**.

    -   No local file paths are printed in chat.

-   Public link base is chosen from:

    1.  `public-base-url` (preferred)

    2.  `http-server.external-base-url`

-   **Auth** (if enabled):

    -   `/reports auth` (players) issues a **one-time code** and login link.

    -   Sessions are cookie-based with a sliding TTL.

    -   `auth.require-permission: true` requires `reportsystem.reports` to use `/reports auth`.

* * * * *

🔔 Notifications
----------------

-   When a report is filed, staff with `reportsystem.notify` receive a summary with an **Expand** button (label from `messages.label-expand`).

-   Optional: you can wire a Discord webhook; the plugin also supports a generic notifier via reflection (e.g., `notifyNew`, `notifyAssigned`, `notifyUnassigned`, `notifyClosed`, `notifyReopened`) if you add an integration module.

* * * * *

🗄️ Storage format
------------------

Each report is a YAML file containing all structured fields (id, reporter, reported, type/category, reason, count, timestamps, status, assignee, optional `sourceServer`, and optional `chat` entries with time/player/server/message).

Example path:

`plugins/ReportSystem/reports/123.yml`

* * * * *

🧪 Tips & Troubleshooting
-------------------------

-   **Jump to server points at a username?**\
    Ensure the server inference is correct (see **Server detection**) and that a server with that name exists. The **Jump** button is shown **only** if the proxy has a server matching the inferred name.

-   **Chat logs missing right after filing?**\
    The rolling buffer should capture recent lines; verify chat capture is active and the target is being watched (open report), and that your buffer window is sufficient.

-   **Public links aren't clickable?**\
    Set either `public-base-url` **or** `http-server.external-base-url`. If both are blank, web export can still occur but no public link will be emitted.

-   **Self-reporting blocked?**\
    Toggle `allow-self-report` as needed.

-   **Inline chat is truncated or too long?**\
    Adjust `preview-lines` (page size) and `preview-line-max-chars`.