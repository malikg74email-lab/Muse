<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Skill System User Guide Tool Invocation function calling

Shaheen 2's Skill system is the LLM's tool set for function calling. Refer to this document when the user asks "what tools do you have" "what can you invoke" "what is a skill" "how do I customise a tool" "how do I create a skill" "can you make a skill for me".

There are 23 built-in skills in total (based on BUILT_IN_SKILLS in the source file SkillExecutor.kt). Of these, 8 are on the install_skill whitelist (the implementationKotlin of a user-customised / LLM-self-installed skill may only reference these 8):

The 8 whitelisted skills (core capabilities):

1. read_file — Reads a text file inside the app sandbox (limit 1MB). Parameters: path (relative to filesDir), offset/length (optional, segmented by "line", offset = starting line number, length = number of lines to read, not bytes). When to invoke: when you need to read notes/data files stored by the user.
2. write_file — Writes text to a sandbox file. Parameters: path, content, append (optional, default false = overwrite). The return value includes a content preview (first 200 characters). When to invoke: when you need to persist notes/data/generated code.
3. http_get — Issues an HTTP GET. Parameters: url, headers (optional JSON). Response limit 1MB. When to invoke: calling a REST API to fetch data.
4. http_post — Issues an HTTP POST. Parameters: url, body (string; serialise the JSON yourself), content_type (default application/json), headers (optional). When to invoke: submitting data to an API.
5. web_search — Searches the web with the configured search engine (SearXNG/Tavily/Bing), and returns title/URL/snippet. Parameters: query, max_results (1-10, default 5). When to invoke: the user needs real-time information, the latest news, or an explanation of a concept.
6. web_fetch — Fetches the body text of a given URL web page (strips HTML tags, returns plain text). Parameters: url, headers (optional). Fetch limit 200,000 characters, return limit 50,000 characters. When to invoke: reading the full text of a URL returned by web_search (two steps: search first, then read).
7. knowledge_search — Full-text search of the user's knowledge base (title + content). Parameters: query, threshold (optional, 0-1 decimal, default 0.3). When to invoke: the user asks about the project's own features / questions related to imported documents (development documents with fileType=devdoc are also here).
8. arxiv_search — arXiv academic paper search. Parameters: query (English preferred), max_results. When to invoke: academic research / paper questions.

The remaining built-in skills (not on the install_skill whitelist, registered directly by the system): delete_file (supports batch paths), file_exists, list_dir, file_download, read_public_file, save_to_downloads, list_public_files, list_skills, uninstall_skill, install_skill, delegate_agent, channel_reply, channel_pass, channel_read_context, task_plan, update_plan_step, generate_image, translate, generate_qr, list_stickers, send_sticker, etc. See BUILT_IN_SKILLS in SkillExecutor.kt for the complete list.

---

# Complete Tutorial on Creating Skills (for Users / LLM in General)

## What Is a Skill

A skill is a definition that "packages a common operation into a reusable tool". After a user creates a skill, the LLM will automatically invoke it when it sees a suitable scenario in later conversations, without needing the steps described again each time.

## Three Creation Paths

| Path | Who Operates It | Method | Suitable Scenarios |
|---|---|---|---|
| A. Have the LLM create it for you | User, in conversation | Tell the LLM "create an XX skill for me", and the LLM completes it with install_skill | Most common, recommended |
| B. Import a .skill.json file | User | Settings → Skill page, import the file | Sharing / backup / migration |
| C. Write code and package a .muse-plugin | Developer | manifest.json + JS script | Complex skills / JS engine |

**Path A is the recommended method**: the user does not need to know any format; they simply tell the LLM "make me a 'recipe lookup' skill", and the LLM is responsible for generating the .skill.json and installing it.

## Complete .skill.json Format (Common to install_skill / File Import)

```json
{
  "id": "fetch_recipe",
  "name": "Recipe Lookup",
  "description": "Look up home-style recipes by dish name for the cooking method and ingredient list. Use when the user asks how to make a certain dish.",
  "parametersJson": "{\"type\":\"object\",\"properties\":{\"dish\":{\"type\":\"string\",\"description\":\"Dish name, e.g. 'braised pork'\"}},\"required\":[\"dish\"]}",
  "requiredJson": "[\"dish\"]",
  "implementationKotlin": "web_search",
  "category": "custom"
}
```

## Field Descriptions

| Field | Required | Description | Rules |
|---|---|---|---|
| id | ✅ | Unique identifier of the skill | Lowercase letters/numbers/underscores/hyphens, e.g. `fetch_recipe`; must not have the same name as a built-in skill/tool (that would conflict) |
| name | ✅ | Display name | Write it in the user's language (English or Roman Urdu), e.g. "Recipe Lookup" |
| description | ✅ | Description for the LLM to read | **Most important**: write clearly "in what scenario to use it, what the input is, what the output is"; only if it is well written will the LLM know when to invoke it |
| parametersJson | Optional | Parameter definition | The string form of a JSON Schema (see the parameter syntax below) |
| requiredJson | Optional | Required parameters | Array string, e.g. `["dish"]` |
| implementationKotlin | ✅ | Underlying implementation | **May only choose one of the 8 whitelist entries**: read_file / write_file / http_get / http_post / web_search / web_fetch / knowledge_search / arxiv_search |
| category | Optional | Category | Default `custom` |

## parametersJson Parameter Syntax (JSON Schema)

```json
{
  "type": "object",
  "properties": {
    "parameter_name_1": { "type": "string", "description": "Parameter description, in the user's language" },
    "parameter_name_2": { "type": "integer", "description": "Maximum quantity" },
    "parameter_name_3": { "type": "boolean", "description": "Whether it is enabled" }
  },
  "required": ["parameter_name_1"]
}
```

- Supported types: string / integer / number / boolean / array / object
- Write the description clearly in the user's language (English or Roman Urdu); the LLM relies on it to decide what value to pass
- required lists the required parameters; do not put non-required parameters in it

## Choosing the Implementation by Purpose (The Most Critical Step)

| Skill You Want to Make | Choose This Implementation | Examples |
|---|---|---|
| Look up information / search the web | web_search | Look up recipes, look up films, look up guides |
| Read the full text of a web page | web_fetch | Read articles, read announcements |
| Call an API to get data | http_get | Check exchange rates, check parcel delivery, check a weather API |
| Submit data to an API | http_post | Send a message, submit a form |
| Read/write local files | read_file / write_file | Ledger, to-do list, favourites |
| Search the knowledge base / internal documents | knowledge_search | Look up documents imported by the user |
| Search academic papers | arxiv_search | Paper retrieval |

## install_skill Invocation Example (From the LLM's Perspective)

When the user says "create a skill for me that can look up recipes", the LLM should:

1. Determine the implementation: recipe lookup → web_search
2. Construct the skill_json (note that parametersJson is a string and needs escaping):
```
install_skill(skill_json = {"id":"fetch_recipe","name":"Recipe Lookup","description":"Look up home-style recipes by dish name for the cooking method and ingredients. Call when the user asks how to make a certain dish.","parametersJson":"{\"type\":\"object\",\"properties\":{\"dish\":{\"type\":\"string\",\"description\":\"Dish name\"}},\"required\":[\"dish\"]}","requiredJson":"[\"dish\"]","implementationKotlin":"web_search","category":"custom"})
```
3. After successful installation, tell the user: "Created the 'Recipe Lookup' skill. You can view/disable it in Settings → Skill page. Now you can ask me how to make any dish."

## Common Errors and Fixes

| Error | Cause | Fix |
|---|---|---|
| "Missing id field" | The id was not written | Add an id, using a lowercase slug |
| "Missing implementationKotlin field" | The implementation was not written | Add one of the 8 whitelist entries |
| "Implementation not allowed" | implementationKotlin is not on the whitelist | Switch to a whitelisted implementation, or split it into multiple skills |
| "id conflicts with a built-in" | The id collides with read_file/calculator, etc. | Change to an id that is not duplicated |
| "Parameter format error" | parametersJson is not a valid JSON Schema | Rewrite it following the parameter syntax above |
| Skill installed but has no effect | The description is written too vaguely, and the LLM does not know when to invoke it | Rewrite the description: what scenario, input, output |
| Want the skill to execute "arbitrary code" | Not via a Skill | A Skill's implementation can only be one of the 8 whitelisted entries — that is a limit of the Skill format. For arbitrary code, use the built-in Python / Node.js runtimes, or a Plugin (real JS) |

## Management After Installation

- View / enable/disable: Settings → Skill page
- Delete: Settings → Skill page (or the LLM uses uninstall_skill)
- Share: Export the .skill.json file to others, and they import it on the Skill page

## Prompt Skills (prompt skill)

In addition to "whitelisted implementation" skills, you can also create a **prompt skill**: pass `"prompt"` as `implementationKotlin`, and put the instruction text in the `prompt` field. It is not code, but a "fixed instruction template"; when invoked, it is handed back to the model as-is for execution — suitable for packaging frequently used prompts as skills (e.g. "write a weekly report in a fixed format", "rewrite copy in a specific style").

- Invocation parameters: when the model invokes it, it may pass an optional `input` (the specific request text). If the instruction contains the `{{input}}` placeholder, it will be replaced with the input; if there is no placeholder, the input is appended to the end of the instruction.
- Suitable for: tasks whose output style is fixed and which need no external data source; for tasks that need internet access / file reading and writing, still use an ordinary skill with a whitelisted implementation.

## Skill or Plugin? How to Choose

| Need | Choice | Reason |
|---|---|---|
| Package frequently used prompts / writing formats for reuse | Skill (prompt skill) | Zero code, works on installation, can share a .skill.json |
| Standard operations such as checking web pages/APIs, reading/writing files | Skill (whitelisted implementation) | Reuse built-in capabilities; no code to write |
| Need arbitrary JS logic, complex control flow, access to plugin APIs | Plugin | Can write arbitrary JS, capability close to a small program; takes effect after the user signs and enables it (the app's enablement mechanism) |
| High-risk actions | Either | The app's own tool-permission settings govern the call; a Plugin additionally requires the user's signature before it is enabled |

In one sentence: **A Skill is a "template + whitelisted tools", a Plugin is a "programmable extension"**. Lightweight, shareable, zero-code → Skill; complex, needs programming, willing to go through the signing process → Plugin. When unsure, recommend a Skill first (lighter, no code).

## System Prompt Injection (Boundaries for the LLM)

- A skill is a tool that is "invoked on demand"; it is not automatically invoked in every conversation; judge from the user's intent
- When the user wants to customise a tool, proactively offer "I can create a skill for you", and complete it with install_skill; do not only give steps
- The whitelisted-implementation limit belongs to the Skill format only; it is not a limit on what you can do for the user. When a task needs arbitrary code, say so plainly and use the built-in Python / Node.js runtimes or draft a Plugin — never present the Skill format's limit as a refusal
