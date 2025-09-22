# ReportSystem

ReportSystem
============

A Velocity proxy plugin for reporting **players, bugs, and custom types**—with **stacking**, **search**, **assignees**, **closed report history**, **chat-log capture & HTML export**, **rich tab-completion (Brigadier)**, and optional **Discord webhooks**.

Highlights
----------

*   /report ... with **dynamic types/categories** from config.yml
    
*   **Stacking**: multiple reports on the same target & category within a window merge ((xN))
    
*   **Open reports UI**: /reports with pagination, expand, close, chat logs, assign/unassign
    
*   **Search**: /reports search \[open|closed|all\]
    
*   **Assignee workflow**: /reports assign , /reports unassign
    
*   **Closed history**: /reporthistory with expand + **reopen**
    
*   **Chat report capture** (type player + category chat): live logs from the server the report was made on; **HTML export**
    
*   **Discord webhooks** (new/close/reopen/assign events)
    
*   **Brigadier** tab-complete for subcommands, IDs, categories, players
    
*   **Permissions**: staff visibility + notify channel
    
*   **Placeholders** for messages: %player% (reporter), %target% (reported)
    

Requirements
------------

*   **Velocity** 3.1.x (API target in pom.xml)
    
*   **Java 17+**
    
*   Maven to build
    

Install
-------

1.  mvn -U clean packageOutput: target/reportsystem-2.1.0-shaded.jar
    
2.  Drop the JAR into your Velocity plugins/ folder.
    
3.  Start the proxy once to generate plugins/ReportSystem/config.yml.
    
4.  Edit config.yml (see examples below). Restart the proxy.
    

Commands
--------

### Player reports & more

Plain textANTLR4BashCC#CSSCoffeeScriptCMakeDartDjangoDockerEJSErlangGitGoGraphQLGroovyHTMLJavaJavaScriptJSONJSXKotlinLaTeXLessLuaMakefileMarkdownMATLABMarkupObjective-CPerlPHPPowerShell.propertiesProtocol BuffersPythonRRubySass (Sass)Sass (Scss)SchemeSQLShellSwiftSVGTSXTypeScriptWebAssemblyYAMLXML`   /report           # for player-type categories  /report                    # for non-player types (e.g., bug)   `

### Staff tools (requires permission)

Plain textANTLR4BashCC#CSSCoffeeScriptCMakeDartDjangoDockerEJSErlangGitGoGraphQLGroovyHTMLJavaJavaScriptJSONJSXKotlinLaTeXLessLuaMakefileMarkdownMATLABMarkupObjective-CPerlPHPPowerShell.propertiesProtocol BuffersPythonRRubySass (Sass)Sass (Scss)SchemeSQLShellSwiftSVGTSXTypeScriptWebAssemblyYAMLXML`   /reports                               # list open reports (paged)  /reports page   /reports view                      # expand one  /reports close   /reports chat                      # HTML export, or inline preview  /reports assign   /reports unassign   /reports search  [open|closed|all]  /reports reload                        # hot-reload config and tab suggestions   `

### Closed history

Plain textANTLR4BashCC#CSSCoffeeScriptCMakeDartDjangoDockerEJSErlangGitGoGraphQLGroovyHTMLJavaJavaScriptJSONJSXKotlinLaTeXLessLuaMakefileMarkdownMATLABMarkupObjective-CPerlPHPPowerShell.propertiesProtocol BuffersPythonRRubySass (Sass)Sass (Scss)SchemeSQLShellSwiftSVGTSXTypeScriptWebAssemblyYAMLXML`/reporthistory  /reporthistory page   /reporthistory view   /reporthistory reopen` 

Permissions
-----------

NodePurposereportsystem.reportsAccess to /reports and /reporthistoryreportsystem.notifyReceive in-game notifications for new reports

> Permission node strings are configurable in config.yml.

Config
------

plugins/ReportSystem/config.yml (key parts shown):

Plain textANTLR4BashCC#CSSCoffeeScriptCMakeDartDjangoDockerEJSErlangGitGoGraphQLGroovyHTMLJavaJavaScriptJSONJSXKotlinLaTeXLessLuaMakefileMarkdownMATLABMarkupObjective-CPerlPHPPowerShell.propertiesProtocol BuffersPythonRRubySass (Sass)Sass (Scss)SchemeSQLShellSwiftSVGTSXTypeScriptWebAssemblyYAMLXML`   allow-self-report: false  stack-window-seconds: 600          # merge reports within 10 minutes  export-html-chatlog: true  html-export-dir: "html-logs"  reports-per-page: 10  staff-permission: "reportsystem.reports"  notify-permission: "reportsystem.notify"  discord:    enabled: false    webhook-url: "https://discord.com/api/webhooks/your-webhook"    username: "ReportSystem"    avatar-url: ""    timeout-ms: 4000  # Dynamic types & categories  report-types:    player:      display: "Player"      categories:        chat: "Chat Report"        cheating: "Cheating Report"    bug:      display: "Bug"      categories:        network: "Network Bug"        server: "Server Bug"  messages:    prefix: "[ReportSystem] "    # ... all user-facing messages are configurable   `

### Notes

*   **Dynamic types/categories** drive tab-completion for /report.
    
*   Chat capture is active for **type player + category chat** reports.
    
*   **Placeholders** available in messages: %player% (reporter), %target% (reported), %id%, etc.
    
*   HTML logs are exported to plugins/ReportSystem/html-logs//index.html when enabled.
    

How stacking works
------------------

If a report arrives for the **same target** and **same type/category** within stack-window-seconds, the plugin:

*   Increments the report’s **count** ((xN) in lists),
    
*   Appends the new **reason** to the existing reasons,
    
*   Emits notify messages once (with updated count).
    

This keeps staff views clean when a user gets mass-reported in short bursts.

Discord Webhooks
----------------

Enable in config.yml:

Plain textANTLR4BashCC#CSSCoffeeScriptCMakeDartDjangoDockerEJSErlangGitGoGraphQLGroovyHTMLJavaJavaScriptJSONJSXKotlinLaTeXLessLuaMakefileMarkdownMATLABMarkupObjective-CPerlPHPPowerShell.propertiesProtocol BuffersPythonRRubySass (Sass)Sass (Scss)SchemeSQLShellSwiftSVGTSXTypeScriptWebAssemblyYAMLXML`   discord:    enabled: true    webhook-url: "https://discord.com/api/webhooks/..."   `

Events sent:

*   New/stacked report
    
*   Closed
    
*   Reopened
    
*   Assigned / Unassigned
    

> Uses simple JSON POST; includes report id, type/category, reported, reporter, and assignee (when relevant).

Brigadier Tab-Completion
------------------------

*   Rich suggestions for:
    
    *   /report → types, categories, and players (for player-type categories)
        
    *   /reports → subcommands, IDs, scopes (open|closed|all), staff names
        
    *   /reporthistory → closed report IDs
        
*   Rebuilt on /reports reload so changes in types/categories take effect immediately.