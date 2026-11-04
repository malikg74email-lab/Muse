<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# ToolRegistry Built-in Tools Compendium — Complete Edition

> Trigger scenario: when the user asks "what tools can you call", "what features are available to use", "how do I use XX feature", or "what is this tool for", refer to this document and answer truthfully.
> This document is based on the real implementation in the source code ToolRegistry.kt (BUILT_IN_TOOL_IDS) / the various Registrars / SkillExecutor.kt (BUILT_IN_SKILLS), and is the authoritative index of Shaheen 2's tool capabilities.

## 1. Overview

ToolRegistry is Shaheen 2's local tool registry (a simplified MCP framework). Tools can be:
- Triggered by the LLM via function calling / tool_call (the main method)
- Reused by scheduled tasks, Skills, and WebServer

Tools fall into two major categories:
1. **Local tools (built-in)**: registered into ToolRegistry by the various Registrars at startup, with names carrying no prefix
2. **Skills (built-in + user)**: defined by SkillExecutor, enabled via skillIdsJson, and extensible via install_skill

There are also two kinds of dynamic tools:
3. **MCP tools**: names carry the `mcp_{serverId}__` prefix; registered after connecting to an MCP server
4. **workspace tools**: reading and writing files in the app workspace

## 2. Tool Whitelist Mechanism (Important)

- Assistant configuration `toolIdsJson`: the default `"[]"` means **all enabled**; specifying an array enables only the listed tools
- Assistant configuration `skillIdsJson`: all built-in skills are enabled by default; a subset can be specified
- Assistant configuration `mcpServerIdsJson`: which MCP servers to bind (a successful connection automatically binds to the main assistant)
- The tool capability index in the system prompt is filtered **on the same basis** as the tools schema (by whitelist); what the model sees is what it can call

## 3. Complete List of Local Tools (One by One)

### 3.1 Basic Tools

| Tool | Parameters | Description | Permissions |
|---|---|---|---|
| get_current_time | timezone (optional, IANA) | Get the current time | None |
| calculator | expression | Simple calculator, addition, subtraction, multiplication, division and parentheses | None |
| echo | text | Echo the input (for testing) | None |
| json_pretty | json | JSON pretty formatting | None |
| hash_text | text / algorithm | Text hash | None |
| generate_uuid | - | Generate a UUID | None |
| random_number | min / max | Random number | None |
| generate_password | length (optional) | Random password | None |
| url_encode / url_decode | text | URL encoding/decoding | None |
| base64_encode / base64_decode | text | Base64 encoding/decoding | None |

### 3.2 Time & Schedule

| Tool | Parameters | Description | Permissions |
|---|---|---|---|
| calendar_today | - | Today's calendar events, Top list | READ_CALENDAR |
| add_calendar_event | title/start/end, etc. | Add a calendar event | READ_CALENDAR + write |
| schedule_reminder | text/time, etc. | Set a reminder | POST_NOTIFICATIONS |
| cancel_reminder | id | Cancel a reminder | Same as above |
| list_reminders | - | List reminders | None |
| set_alarm | hour/min/label | Set a system alarm (launches the system clock app) | None |
| set_timer | seconds/label | Set a countdown timer (launches the system clock app) | None |

### 3.3 Phone System Tools

| Tool | Parameters | Description | Permissions |
|---|---|---|---|
| open_app | packageName | Open an app (e.g. com.tencent.mm) | None |
| open_url | url | Open a link in the browser | None |
| open_maps | query | Open a map search | None |
| open_system_setting | setting | Open a system settings page | None |
| share_text | text | System share panel | None |
| send_sms | phone/text | Send a text message | SEND_SMS |
| send_email | to/subject/body | Send an email (launches the email app) | None |
| make_phone_call | phone | Make a phone call | CALL_PHONE |
| add_contact | name/phone | Add a contact | WRITE_CONTACTS |
| get_contacts_count / get_contacts_list | - | Contact count/list | READ_CONTACTS |
| get_location | - | Last known location (latitude/longitude/accuracy) | ACCESS_COARSE_LOCATION |
| get_device_info | - | Brand/model/Android version/screen/battery | None |
| get_battery_info | - | Battery status | None |
| get_network_info | - | Network status | ACCESS_NETWORK_STATE |
| get_wifi_info | - | WiFi information | ACCESS_FINE_LOCATION (partial) |
| get_bluetooth_devices | - | Bluetooth device list | BLUETOOTH_CONNECT |
| get_cpu_info / get_memory_info / get_storage_info / get_display_info / get_sensors_list | - | Hardware information | None |
| get_foreground_app | - | Current foreground app | Special permission |
| get_recent_notifications | - | Recent notifications | NOTIFICATION_LISTENER |
| list_installed_apps | - | Installed app list | QUERY_ALL_PACKAGES |
| get_public_ip | - | Public IP | Network |
| screen_time | - | Today's app screen time, Top 10 | PACKAGE_USAGE_STATS |
| get_brightness / set_brightness | value | Read/write brightness | WRITE_SETTINGS |
| get_volume / set_volume | type/value | Read/write volume | WRITE_SETTINGS |
| toggle_wifi / toggle_bluetooth / toggle_flashlight | enabled | Toggle WiFi/Bluetooth/flashlight | CHANGE_WIFI_STATE, etc. |
| vibrate | duration | Vibrate | VIBRATE |
| ping_host / dns_lookup | host | Network diagnostics | Network |
| get_weather | city (optional) | Weather | Network |
| speak_text | text | TTS read aloud | None |

### 3.4 Clipboard & Resources

| Tool | Parameters | Description |
|---|---|---|
| clipboard_read | - | Read the clipboard |
| clipboard_write | text | Write to the clipboard |
| resource_add / resource_list / resource_search / resource_get / resource_delete | - | In-app resource management (files/text snippets) |
| quick_note_add / list / search / get / update / delete / pin | - | Quick notes CRUD + pinning |

### 3.5 Memory & Experience

| Tool | Parameters | Description |
|---|---|---|
| pin_memory | content / keyword | Pin a memory (injected every time) |
| unpin_memory | keyword / id | Unpin |
| recall_experience | query | Recall past experience |
| record_experience | title/content | Record experience |
| search_memory | query | Search long-term memory (skill implementation) |

### 3.6 Communication & Presentation

| Tool | Parameters | Description |
|---|---|---|
| notify | title/body | Pop a system notification |
| show_card | title/code | Render an interactive card |
| current_status | - | Check the current session/environment status |
| todo_write | todos | Maintain a task list |
| delegate_agent | task/agent | Delegate a task to another assistant |
| subagent_task | task | Dispatch a subtask |
| translate | text/target | AI translation (skill implementation) |

### 3.7 Media Generation

| Tool | Parameters | Description |
|---|---|---|
| generate_image | prompt | AI image generation |
| generate_video | prompt | AI video generation |
| generate_qr_code | text | Generate a QR code |
| list_stickers / send_sticker | id | Stickers (deprecated, data retained) |

### 3.8 Browser Automation

| Tool | Parameters | Description |
|---|---|---|
| browser_navigate | url | Open a browser page |
| browser_click | ref | Click an element |
| browser_type | text | Enter text |
| browser_extract | - | Extract page content |
| browser_scroll_bottom | - | Scroll to the bottom |
| browser_get_html | - | Get the page HTML |

### 3.9 Workspace

| Tool | Parameters | Description |
|---|---|---|
| workspace_list | path | List a directory |
| workspace_read | path | Read a file |
| workspace_write | path/content | Write a file (can overwrite/append) |
| workspace_delete | path | Delete a file |
| workspace_mkdir | path | Create a directory |
| workspace_move | from/to | Move/rename |

### 3.10 Scheduled Tasks

| Tool | Parameters | Description |
|---|---|---|
| scheduled_task_create | cron/action, etc. | Create a cron scheduled task |
| scheduled_task_list | - | List tasks |
| scheduled_task_update | id/... | Update a task |
| scheduled_task_delete | id | Delete a task |
| scheduled_task_execute | id | Execute immediately |
| scheduled_task_get_history | id | Execution history |

### 3.11 Execution Environment

| Tool | Parameters | Description | Risk |
|---|---|---|---|
| execute_javascript | code | Sandbox JS execution | NORMAL |
| execute_node_script | code / timeout_ms | Built-in Node.js runtime executes JS (full capabilities: files/network/subprocesses; can require npm packages) | HIGH |
| execute_python_script | code / timeout_ms | Built-in Python 3.14 runtime executes scripts (full standard library: ssl/sqlite3/zlib/json/re; file reading/writing, network; can pip-install third-party pure-Python packages). The result is assigned to the variable `result` | HIGH |
| execute_shell | command | Shell command execution (**not in the whitelist by default**, HIGH risk, requires the user to enable it manually on the tool management page) | HIGH |
| read_file / write_file | path/content | Sandbox file reading/writing (see the Skill section) | - |

## 4. Complete Skill List (30 Built-in)

### 4.1 File
- read_file: read sandbox text (limit 1MB, path relative to filesDir, supports segmenting by line via offset/length)
- write_file: write a sandbox file (path/content/append)
- list_dir: list a directory
- delete_file: delete files (supports paths in batch)
- file_exists: check whether it exists
- file_download: download a file into the sandbox
- read_public_file: read a content:// URI file (used together with list_public_files)
- save_to_downloads: save to the system Downloads directory
- list_public_files: list public files (the output includes uri=, which can be fed directly to read_public_file)

### 4.2 Network & Information
- http_get: HTTP GET (url/headers, response limit 1MB)
- http_post: HTTP POST (url/body/content_type/headers)
- web_search: search engine (query/max_results 1-10, default 5)
- web_fetch: fetch a web page's body text (url, limit 200,000 characters, returns 50,000)
- knowledge_search: knowledge base full-text retrieval (query/threshold, default 0.3; devdoc internal documents are also here)
- arxiv_search: arXiv paper search (query/max_results)

### 4.3 Self-Extension & Management
- install_skill: **the LLM creates a skill on the spot** (skill_json; implementationKotlin limited to 8 whitelist implementations)
- list_skills: list all skills (built-in/user, enabled/disabled status)
- uninstall_skill: uninstall a skill (id/name)
- disable_skill: disable a skill
- task_plan: draw up a task plan
- update_plan_step: update a plan step

### 4.4 Agent & Group Chat
- delegate_agent: delegate to another assistant (task/agent selects the target)
- channel_reply / channel_pass / channel_read_context: the group chat trio (speak/skip/read context)
- agent_phone: phone mode (conversational)

### 4.5 Other
- generate_image: AI image generation
- translate: AI translation
- list_stickers / send_sticker: stickers (deprecated)

## 5. How the Model Chooses Tools

1. **Judge the type first**: which category is what the user wants — "look up information / perform an operation / generate content / manage data"
2. **Match the capability**: look up real-time information → web_search; read a specific web page → web_fetch; write a file → write_file; timing → scheduled_task_*; delegation → delegate_agent
3. **Parameter correctness**: pass parameters strictly according to the schema; a tool will report an error for missing parameters
4. **One-call principle**: if it can be done in one call, do not split it into multiple calls; call again to supplement only if the result is not enough
5. **Report failures truthfully**: if a tool returns an error/timeout, say so truthfully; do not fabricate success

## 6. Common Questions Q&A

1. **"Why can't I use some tools"**: tools are filtered by the assistant whitelist (toolIdsJson is fully open by default); execute_shell is off by default (high-risk) and must be enabled manually on the tool management page.
2. **"What is the difference between tools and Skills"**: tools are fixed capabilities registered by the system; Skills are extensible wrappers (customizable, installable) that reuse tool implementations underneath.
3. **"How do I use MCP tools"**: after connecting to an MCP server, its tools are registered automatically (prefix mcp_{serverId}__), and are called just like local tools.
4. **"The model says it doesn't have a certain tool"**: check whether that assistant's whitelist (toolIdsJson/skillIdsJson/mcpServerIdsJson) includes it; MCP must be connected first and bound to the assistant.
5. **"Can execute_shell be turned on"**: yes, enable it on the tool management page. It runs shell commands on the user's own phone with the access granted to the app; once he enables it, it is an ordinary tool.

## 7. Key Points for LLM Calls

- The tool capability index is listed in the system prompt by assistant whitelist, consistent with the tools schema; what you see is what you can call
- Before calling a tool, confirm the target and scope (which contact, file or account — never guess an uncertain ID or path). Ask the user once first only when the action cannot be undone: spending money, permanently deleting something, or messaging someone new. Everything else — including calls and messages to his existing contacts — is ordinary work on his instruction
- For search tools, prefer web_search to locate first, then web_fetch for a close read
- File tools work within the app sandbox filesDir / workspace, plus any storage the user has granted the app access to
- When the user asks about Shaheen 2's own features, first check the devdocs via knowledge_search; do not fabricate from memory
