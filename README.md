# ReportSystem (Velocity)

A modern, staff-friendly reporting and system for [Velocity](https://velocitypowered.com/) proxies.

It captures player chat context, supports dynamic report types, stacks duplicate reports, and provides a rich in-chat and web-based UI for staff. The plugin is actively maintained and built for performance, usability, and configurability.

---

## 📚 Table of Contents

- [✨ Features](#-features)
- [🧰 Requirements](#-requirements)
- [📦 Installation](#-installation)
- [🧑‍💻 Commands](#-commands)
  - [Player / Staff](#player--staff)
  - [Staff](#staff)
- [🖱️ In-Chat UI](#️-in-chat-ui)
- [💬 Chat Capture](#-chat-capture)
- [🔐 Permissions](#-permissions)
- [⚙️ Configuration](#️-configuration)
- [🔎 Stacking Logic](#-stacking-logic)
- [🌐 Web Viewer & Auth](#-web-viewer--auth)
- [🔔 Notifications](#-notifications)
- [🤝 Contributors](#-contributors)
- [📄 License](#-license)

---

## ✨ Features

- `/report` with dynamic types/categories (fully configurable)
- Context-aware tab-completion (type → category → target → reason)
- Duplicate stacking with a configurable time window
- In-chat and HTML chat capture
- Chat UI with hover tooltips and quick actions
- Inline or web-based chat log viewer
- Closed report browser with reopen support
- Optional built-in HTTP server for HTML exports
- Optional Discord webhook notifications
- Persistent YAML-based storage (`plugins/ReportSystem/reports/<id>.yml`)
- Configurable multi-factor report priority system (scaffolding)

---

## 🧰 Requirements

- **Velocity**: 3.x
- **Java**: 17+
- **Permissions Plugin**: LuckPerms or equivalent (Velocity-compatible)

---

## 📦 Installation

1. Download the plugin `.jar`.
2. Drop it into the `plugins/` folder of your Velocity proxy.
3. Start the proxy once to generate `plugins/ReportSystem/config.yml`.
4. Edit the config to your liking.
5. Reload with `/reports reload` or restart the proxy.

---

## 🧑‍💻 Commands

### Player / Staff

\`\`\`
/report <type> <category> [<target>] <reason...>
\`\`\`

- **Target required** for `player/*` types.
- **No target** needed for types like `server/crash`.

#### Tab-Completion

- **Position 1**: Type IDs (from `report-types`)
- **Position 2**: Category IDs (based on type)
- **Position 3**: Online player names (if applicable)
- **Position 4+**: Placeholder for reason (`<reason...>`)

> Cooldown applies to non-staff. Bypassed with permission.

---

### Staff

\`\`\`
/reports
\`\`\`

Open the live report interface, sorted by:
- Stack count (desc)
- Then time (newest)

#### Subcommands

\`\`\`
/reports page <n>
/reports <type> [category]
/reports view <id>
/reports claim [<id>]
/reports claimed
/reports close <id>
/reports chat <id> [page]
/reports assign <id> <staff>
/reports unassign <id>
/reports assigntome <id>
/reports unassignme <id>
/reports search <query> [open|closed|all]
/reports reload
/reports auth
/reports logoutall
/reporthistory
/reporthistory page <n>
/reporthistory view <id>
/reporthistory reopen <id>
\`\`\`

---

## 🖱️ In-Chat UI

Example Line:
\`\`\`
#123 (Player / Chat) Notch [ModJane] [lobby-1]
\`\`\`

### Hover Tooltips

- **Target**: `"Target: %name%"`
- **Assigned**: `"Assigned: %name%"`
- **Server**: `"Server: %name%"`

### Expand Button

- Globally configurable: `messages.label-expand` (e.g., `"^"`)
- Hover: `messages.tip-expand: "Click to expand"`

Used across:
- New report notifications
- `/reports`, `/reporthistory`, `/reports claimed`, search results, expanded views

---

## 💬 Chat Capture

- **Live** capture of all chat for reported players while reports are open
- On creation, captures a rolling buffer (e.g., last 90s)
- Displayed:
  - As **HTML link** if web server enabled
  - **Inline with pagination** if not

\`\`\`
/reports chat <id> [page]
\`\`\`

Pagination size: `preview-lines`

---

## 🔐 Permissions

| Permission | Purpose |
|-----------|---------|
| \`reportsystem.reports\` | Staff access to \`/reports\`, bypass \`/report\` cooldown, use web auth |
| \`reportsystem.notify\` | Receive notifications for new reports |
| \`reportsystem.forceclaim\` | Override claim assignments |
| \`reportsystem.admin\` | Admin commands like \`/reports reload\`, \`/reports logoutall\` |

---

## ⚙️ Configuration

The full default config is well-commented and included in the plugin folder. Highlights:

\`\`\`yaml
stack-window-seconds: 600
export-html-chatlog: true
report-cooldown-seconds: 60
preview-lines: 10
http-server:
  enabled: false
  port: 8085
auth:
  enabled: false
discord:
  enabled: false
priority:
  enabled: true
  use-count: true
  use-severity: true
  sla-minutes:
    "player/cheat": 5
\`\`\`

> See full \`config.yml\` for more.

---

## 🔎 Stacking Logic

Reports are stacked if:

- **Same target**, **type**, and **category**
- Within \`stack-window-seconds\` (default: 600s)

Result:
- Count is incremented
- New reason is appended (\`prev | new\`)

---

## 🌐 Web Viewer & Auth

If enabled:
- \`/reports chat <id>\` exports to HTML under \`html-logs/\`
- Public URL generated using \`public-base-url\` or fallback
- **No local paths** shown in chat

### Auth System

- One-time codes via \`/reports auth\`
- Session stored in cookies
- TTL controlled via config
- Requires \`reportsystem.reports\` (if \`auth.require-permission: true\`)

---

## 🔔 Notifications

When a report is filed:
- Staff with \`reportsystem.notify\` get an in-game summary with an **expand button**
- Optional Discord webhook (\`discord.webhook-url\`) for external alerts

---

## 🤝 Contributors

- Project maintainers: _[Add your names here if public]_
- Contributions welcome via PR or issue reports.

---

## 📄 License

_This plugin is proprietary / open-source — **please clarify license if public**._
