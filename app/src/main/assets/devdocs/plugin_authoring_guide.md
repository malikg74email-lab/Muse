<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Complete Guide to Writing Plugins

> Trigger scenario: when the user asks "how do I write a plugin" "can I make a plugin" "what is the difference between a plugin and a skill", refer to this document and answer truthfully.
> This document is based on the real implementation of the data/plugin/ module in the source code (PluginPackageLoader/PluginManifest/PluginManager) + the tools/script/ engine.

## 1. Overview

Shaheen 2 plugins are **JS skill packages**, in the `.muse-plugin` (ZIP) file format: a directory containing manifest.json + main.js, packaged, then loaded into the app to provide new tool capabilities. Unlike install_skill (a Kotlin allowlist implementation), plugins are executed with a **JS engine** (WebViewSkillEngine), so their capabilities are more flexible (but still subject to sandbox limits).

> The `.skillpkg` preview format has been discontinued, and only one plugin package format, `.muse-plugin`, is retained.

## 2. .muse-plugin Package Structure

```
my-plugin.muse-plugin (ZIP)
├── manifest.json   # Package description (required)
├── main.js         # Main plugin logic (required, when kind=tool/provider; runtime=js)
├── main.py         # Python main plugin logic (when runtime=python, the entry is .py)
└── assets/         # Optional, plugin resources
```

### Complete Explanation of manifest.json Fields
| Field | Required | Description |
|---|---|---|
| id | ✅ | Unique plugin id (lowercase letters/numbers/underscores/hyphens) |
| name | ✅ | Display name |
| description | ✅ | Functional description (shown to the LLM, determines when it is called) |
| version | Optional | Version number, default 0.1.0 |
| entry | Optional | Entry file, default main.js (when runtime=python it should be .py) |
| runtime | Optional | Runtime: js (default, WebView V8 sandbox) / python (built-in Python 3.14) |
| kind | Optional | Plugin type: tool (default) / ui-skin / provider |
| trust | Optional | Trust level; external ZIPs must be sandboxed |
| capabilities | Optional | Declared capabilities (resource.read / resource.write / network / ui / ui.mood) |
| tools | Optional | Definitions of the exposed tools (name/parameters/description) |
| signature | Optional | Publisher signature envelope (public key + SHA256withECDSA signature) |

### main.js Authoring Rules
- Use APIs supported by the JS engine (see SkillEngine)
- Export tool handler functions; parameters are obtained from the calling context
- Return values (string/JSON) are filled back in for the LLM
- If IO capabilities (HTTP, etc.) are needed, you can use `fetch` / `XMLHttpRequest` / `WebSocket` directly (the sandbox has network access opened up), or return a `{ __bridge__: true, action: "...", params: {...} }` bridge request object, which is executed by the Kotlin side (either approach works; the bridge is more direct for capabilities such as files/clipboard)

### main.py Authoring Rules (runtime=python)
- The entry file defines a function with the **same name** as `tools[].functionName`; the function accepts a `dict` parameter (the tool call arguments)
- The return value may be a dict/list/str/number; it is JSON-serialised and filled back in for the LLM
- The **full standard library** is available (ssl/sqlite3/zlib/hashlib/json/re/urllib, etc.); file reading/writing and networking are directly available (subprocess isolation)
- You can `import` third-party pure-Python packages installed via pip (`python -m pip install <package-name>`)
- You can use `host.getConfig(key)` to read plugin configuration (same semantics as JS plugins)
- Example:
  ```python
  def fetch_stats(args):
      import json, urllib.request
      with urllib.request.urlopen(args["url"], timeout=30) as r:
          data = json.load(r)
      return {"count": len(data)}
  ```

## 3. Execution Model

| Engine | Description |
|---|---|
| WebViewSkillEngine | Executes in the WebView V8 sandbox (the production JS skill engine, implements the SkillEngine interface) |
| ProcessSkillEngine | Executes with the built-in Node.js runtime (can be selected when runtime=js, full capabilities) |
| PythonSkillEngine | Executes with the built-in Python 3.14 runtime (used when runtime=python; subprocess isolation) |
| SkillBridge | `__bridge__` bridge executor: JS returns a bridge request → Kotlin executes a safe implementation |

Plugin runtime selection: the manifest's `runtime` field determines which engine is used. `js` (default) uses the WebView V8
sandbox + `__bridge__` bridge; `python` uses the built-in Python runtime (subprocess, full standard library + pip third-party packages,
no WebView sandbox limits and no bridge layer — the subprocess itself already has full capabilities).

Security boundaries (the v2.x sandbox has been opened up):
- JS executes inside the sandbox and **can use the network directly** (fetch/XHR/WebSocket); only **page navigation** is prohibited (no pop-ups/page jumps)
- Reading local files via file:// / content:// remains isolated (to prevent cross-origin reading)
- Plaintext HTTP is restricted: the system's network_security_config prohibits plaintext, except localhost/127.0.0.1; everything else requires https
- `__bridge__` bridge actions are **all allowed by default** (capabilities are no longer declared item by item); of these, `fs_*` is locked to the plugin's own sandbox directory, and `http_*` goes through SSRF protection
- Capability declarations (the manifest's capabilities) are now used only for display in the install preview, and are no longer a hard installation threshold
- External packages must declare `trust: "sandboxed"`; the publisher signature is still verified (tamper prevention)
- **python runtime**: scripts execute in a separate subprocess (a crash does not affect the host), but have the full capabilities of that process
  (file reading/writing/network/subprocesses); the plugin tool itself still goes through the unified tool approval chain (HIGH risk requires user confirmation)

## 4. Loading/Distribution/Updates

- Packaging: .muse-plugin directory → zip (extension .muse-plugin)
- Distribution: share the zip file / install from the plugin marketplace
- Loading: PluginPackageLoader parses the manifest → checks the security constraints (ZIP bomb protection/path traversal/entry exists) → registers the tools in ToolRegistry (with a plugin prefix)
- Trust: publisher P-256 ECDSA signature + local trust root + built-in official catalogue; packages from unsigned or untrusted publishers are not automatically trusted
- Updates: re-import to overwrite (by id)

## 5. Differences Between Plugins and install_skill

| Dimension | install_skill (Kotlin allowlist) | Plugin (.muse-plugin, JS) |
|---|---|---|
| Implementation | Reuses 8 built-in Kotlin implementations | Custom-written JS logic |
| Flexibility | Low (only combines the allowlist) | High (can write complex logic) |
| Security | High (no arbitrary code) | Medium (JS sandbox + bridge audit) |
| Suitable for | Simple tools (query/read/write/search) | Complex plugins (workflows/calculations/multi-step) |
| Creator | User/LLM (created in conversation) | Developer (writes code and packages it) |

## 6. Common Questions Q&A

1. **"I don't know how to code, can I make a plugin"**: for simple tools, just have the AI create one in conversation (install_skill); complex plugins require a foundation in JS.
2. **"Are plugins safe"**: JS executes in a sandbox and cannot directly access the system; network requests go through Kotlin bridge auditing; be careful when importing plugins from unknown sources.
3. **"Which is better, a plugin or a skill"**: use a skill for simple scenarios (install_skill, no code); use a plugin when you need custom logic.
4. **"How do I share a plugin"**: package it as a .muse-plugin zip and send it to the other person to import.
5. **"Can a plugin use the network"**: through the __bridge__ bridge; HTTP requests are executed after SSRF protection on the Kotlin side.

## 7. Key Points for LLM Calls

- The user wants a "simple query/read/write tool" → use install_skill to create it on the spot (no code)
- The user wants a "complex custom plugin" → guide the user to explain that JS is needed; they can refer to the plugin development template
- Tools registered by a plugin are called like ordinary tools, with parameters as defined in the manifest
- If plugin execution fails/reports an error, report the error information truthfully; do not fabricate
