<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Complete Guide to Multi-Agent Collaboration

> Trigger scenario: When the user asks "How do I use multiple assistants together?", "Can I delegate a task to another assistant?", "What is a group chat?" or "How do I get the assistants to discuss something?", refer to this document and answer truthfully.
> This document is based on the real implementation in the source code AgentRouter.kt / AgentToolsRegistrar.kt / GroupChatScheduler.kt / GroupChatScreen.kt.

## I. Overview

Shaheen 2 supports collaboration between multiple assistants (Agents):
1. **delegate_agent delegation**: The current assistant delegates a task to another assistant to run for one round of LLM, takes the result, and continues
2. **Group chat (GroupChat)**: Multiple assistants take turns speaking and discussing in a channel, and the user can join in
3. **DM**: A private chat between two assistants

## II. The delegate_agent Delegation Mechanism

### How to Call It (LLM Perspective)
```
delegate_agent(task = "Help me calculate the budget", agent = "Finance Assistant")
```
- **task**: The description of the delegated task (full context)
- **agent**: The target assistant's id (chosen from the list of assistants in the system prompt)

### Flow
1. The current assistant calls delegate_agent and specifies the target assistant
2. The target assistant runs for one round using **its own** systemPrompt/model/memory/tools
3. The result is returned to the current assistant, which integrates it and then replies to the user

### Key Points
- Delegate to a specialist assistant (e.g. translation/code/copywriting) to make use of each one's persona strengths
- Write the context clearly in the task; the target assistant cannot see the current conversation
- Once the result is returned, integrate it truthfully; do not falsify the sub-assistant's conclusions
- Sub-assistants use isolated sub-sessions and are not injected with the main conversation's long-term memory (to avoid recursion)

## III. Group Chat (GroupChat)

### 3.1 Types
| Type | Description |
|---|---|
| DM | Private chat between two assistants |
| Group | Multi-person group (≥3 people) |

### 3.2 Creating One
1. Home page, Group Chat Tab → New
2. Select the participating assistants (2 or more)
3. Select the type (DM/Group)
4. After creation, the assistant bots automatically start speaking

### 3.3 Message Scheduling (GroupChatScheduler)
- The assistants take turns speaking, and the scheduler decides the speaking order (rotation/response)
- The user can join the channel and speak (@ a particular assistant to target it)
- Scheduling modes such as invokeAgent / vote / summary

### 3.4 The Three Group-Chat Tools (LLM Perspective)
| Tool | Purpose |
|---|---|
| channel_reply | Speak (identity is bound to the current assistant; the parameter contains the message content) |
| channel_pass | Skip this round (when there is nothing to say) |
| channel_read_context | Read more of the historical context |

Note: The channel_* tools are only available in a group-chat context; they cannot be called in the main session (to prevent identity impersonation).

## IV. Assistant-Specific Models (resolveAssistantModel)

- Each assistant can be configured with its own dedicated model (modelId+providerId)
- During group-chat speaking/delegation/voting, the target assistant uses the model configured for it
- If none is configured, the global default model is used

## V. @mention and Targeting

- In a group chat, @ a particular assistant → that assistant is named and responds with priority
- The user can @ multiple assistants in one message

## VI. Frequently Asked Questions Q&A

1. **"How do I create multiple assistants"**: Settings → Assistants → New, and configure the persona/model/allowlist.
2. **"How do I get an assistant to look something up for me before answering"**: Delegate to an assistant that is good at searching, or the current assistant can use web_search directly (delegation is not essential).
3. **"The group-chat assistants aren't speaking"**: Check whether the assistants are enabled and whether their models are usable; channel_pass is normal (it skips when there is nothing to say).
4. **"Can a sub-assistant see my chat history"**: It cannot see the full record; it only receives the delegated task, and long-term memory is isolated as well.
5. **"How do I save group-chat results"**: Group-chat messages are stored in the database and can be reviewed; for important conclusions, you can ask an assistant to summarise them.
6. **"Can the main assistant delegate to a group chat"**: Not directly; delegation is a point-to-point, single-assistant task.

## VII. Key Points for LLM Calls

- Use delegate_agent when a specialist division of labour is needed (not every task is worth delegating; answer simple questions yourself)
- In a group chat you must speak using the channel_* trio of tools; do not output stray text
- Write the delegated task with a clear background/goal/expected format to reduce back-and-forth
- Keep group-chat messages short and opinionated; do not flood the channel
- When the user @s you, respond with priority
