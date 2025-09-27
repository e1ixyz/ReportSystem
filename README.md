**ReportSystem (Velocity)**
===========================

A modern, staff-friendly report & triage system for Velocity proxies.

It captures context (chat), stacks duplicate reports, supports rich in-chat UI with hover tooltips, and can export HTML chat logs via a tiny built-in web server.

> **Status:** actively evolving. This README reflects the current feature set and the latest config/UX we've discussed.

* * * * *

**✨ Features**
--------------

-   **/report** with dynamic **types/categories** (fully configurable)

    -   player/* types accept a <target>; other types are reason-only (bug reports for example)

-   **Duplicate stacking** (same target/type/category) with a configurable time window

-   **Chat capture**

    -   Tracks targets mentioned in **open** reports

    -   On report creation, pulls a **rolling buffer of recent lines** (e.g., last 90s) so earlier context isn't lost

    -   View chat logs as **HTML** (web server enabled) or **inline with pagination** (web server disabled)

-   **Staff UI (/reports)**

    -   Sorting by priority: **stack count** desc, then tie-break by time

    -   Rich list line: (#ID) (Type / Category) Target [Assigned] [Server]

        with **hover tooltips** for Target/Assigned/Server

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

### **Staff**

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

### Open list line 

`#123 (Player / Chat) Notch [ModJane] [lobby-1]`

-   **Target** is shown **without a label** and has a hover tooltip:\

-   **Assigned** is shown in brackets with a hover tooltip:\

-   **Server** is shown in brackets with a hover tooltip:\

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

-   When a report is filed, staff with `reportsystem.notify` receive a summary with an **Expand** button.

-   Optional: you can wire a Discord webhook.

* * * * *

🗄️ Storage format
------------------

Each report is a YAML file containing all structured fields (id, reporter, reported, type/category, reason, count, timestamps, status, assignee, optional `sourceServer`, and optional `chat` entries with time/player/server/message).

Example path:

`plugins/ReportSystem/reports/123.yml`

* * * * *