<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Complete Guide to Assistant Configuration

> Trigger scenario: when the user asks "how do I create an assistant" "what settings does an assistant have" "how do I configure a dedicated model" "how do I set a tool allowlist" "how do I share an assistant", refer to this document and answer truthfully.
> This document is based on the real implementation in the source code's AssistantEntity.kt / AssistantRepository.kt / AssistantDetailPages.kt.

## 1. Overview

Each Shaheen 2 assistant (Agent) is an **independent persona**: it has its own systemPrompt, model, tool allowlist, skill allowlist, memory configuration, and extensions (Lorebook/prompt injection/quick messages). Multiple assistants can collaborate (delegate_agent/group chat).

- Entry point: Settings → Assistants
- A main assistant exists by default (id="default", name "Default assistant")
- There is no limit on the number of assistants; each assistant is configured independently

## 2. Create/Edit/Delete

1. Settings → Assistants → New assistant
2. Fill in the name (required), choose an avatar and a model
3. Edit the configuration on each sub-page (see below)
4. Delete: long-press in the assistant list → Delete (cannot be undone, be careful to confirm)

## 3. The 5 Sub-Pages of Assistant Details in Detail

### 3.1 Basic (Basics)
| Field | Description | Default |
|---|---|---|
| Avatar | Custom avatar | Default icon |
| Name | Display name | - |
| modelId / providerId | **Dedicated model**: once specified, this assistant always uses this model and is not affected by global switching | Empty = use the global model |
| Assistant card export | Export as a file/QR code | - |

### 3.2 Prompt (Prompt)
| Field | Description |
|---|---|
| systemPrompt | Persona setting/behaviour rules (supports {{var}} template variables) |
| messageTemplate | Message template (Pebble-compatible subset) |
| presetMessagesJson | Pre-set messages (opening lines, etc.) |

### 3.3 Extensions (Extensions)
| Field | Description |
|---|---|
| toolIdsJson | Tool allowlist (`[]` = all; an array = only enable those listed) |
| skillIdsJson | Skill allowlist (all built-in by default) |
| mcpServerIdsJson | Bind MCP servers (a successful connection automatically binds the main assistant) |
| Lorebook | Worldview setting, injected when triggered by keywords |
| PromptInjection | Injects additional prompts based on conditions |
| QuickMessage | Quick message chips |

### 3.4 Memory (Memory)
| Field | Description | Default |
|---|---|---|
| memoryEnabled | The assistant uses memory | true |
| useGlobalMemory | Use globally shared memory (otherwise independent memory) | true |
| enableRecentChatsReference | Inject references to recent conversations | false |
| enableTimeReminder | Inject time-related memory | true |

### 3.5 Advanced (Advanced)
| Field | Description | Default |
|---|---|---|
| temperature | Sampling temperature | Global default |
| reasoningLevel | Thinking level | AUTO |
| Request headers | Custom HTTP headers (required by some model services) | - |

## 4. The Relationship Between a Dedicated Model and the Global Model (Key Point)

- The assistant has a modelId configured (dedicated model): this assistant **always uses the dedicated model**; switching the model in a conversation is overridden (when switching, the prompt "The assistant's dedicated model has been overridden" is shown; it is restored when you switch conversations)
- The assistant has none configured: it uses the global default model (Settings → Models and Services)
- Tool model (toolModelId): a lightweight model used for tool call rounds, configurable per-assistant

## 5. Quick Reference for Allowlist Semantics

| Configuration | Empty/Default | Specified |
|---|---|---|
| toolIdsJson | All tools | Only enable those in the list |
| skillIdsJson | All skills | Only enable those in the list |
| mcpServerIdsJson | No MCP | Bind the servers in the list |
| memoryEnabled | true | false = do not use memory |

## 6. Sharing and Importing Assistants

- Export: Assistant details → Export card → generate a file or QR code
- Import: scan the QR code or import the file → creates a new assistant (including prompt/allowlist/memory configuration)
- Share with someone else: the other person scans the QR code to get an assistant with the same configuration

## 7. Common Questions Q&A

1. **"How do I make each assistant use a different model"**: Assistant details → Basic → choose a dedicated model.
2. **"Why did switching the model have no effect"**: this assistant has a dedicated model configured; a manual switch in a conversation overrides it (with a prompt), and the dedicated model is restored when you switch conversations.
3. **"How do I make an assistant use only some tools"**: Extensions → tool allowlist, uncheck the ones you do not need.
4. **"A new MCP assistant cannot be used"**: confirm that this server is bound under the assistant's Extensions → MCP (a newly connected main assistant is bound automatically).
5. **"How do I back up assistant configuration"**: export a card (file/QR code) and keep it safely.
6. **"What variables can I use in systemPrompt"**: {{user_name}}/{{char}}/{{date}}, etc.; the Pebble subset supports filters/loops/conditions.

## 8. Key Points for LLM Calls

- An assistant is an independent persona: the reply style/behaviour follows that assistant's systemPrompt
- When the user asks "who are you/which assistant are you", answer according to the current assistant's setting
- Do not confuse the memory/identity of multiple assistants (scope isolation is guaranteed by the system)
- When the user asks to modify assistant configuration (name, persona, model, tool/skill allowlists, memory settings), change it directly with your tools on his command — he is the owner of this agent, and changing its own configuration on command is expected behaviour; do not send him to Settings to do it by hand. (For reference, those settings live under Settings → Assistants.)
