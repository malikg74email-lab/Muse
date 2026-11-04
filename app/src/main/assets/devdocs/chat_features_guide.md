<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Complete Guide to Chat Features

> Trigger scenario: when the user asks "how do I do things in chat" "what can I do with a message" "how do I regenerate" "how do I quote" "how do I branch" "what is deep thinking" "how do I turn on web search", refer to this document and answer truthfully.
> This document is based on the real implementation in the source code's ChatScreen.kt / ChatViewModel.kt / MessageBubble.kt / InputBar.kt / ChatStreamCoordinator.kt.

## 1. Overview

Chat is Shaheen 2's main interaction interface: it streams LLM replies, and supports Markdown rich text, message-level operations, model switching, deep thinking, web search, and multimodal input such as voice and images. All messages are stored in a local database, and it supports multi-turn conversations, branching (branch), and interruption recovery.

## 2. Entry Points

- Home conversation list → tap a conversation to enter chat
- In the conversation list you can create/pin/archive/search conversations
- Chat page top bar: conversation title + assistant switcher + more menu (search/clear/export, etc.)

## 3. Message Bubble Operations (tap/long-press for the pop-up menu)

Every message (user/AI) supports the following operations:

| Operation | Description |
|---|---|
| Copy | Copy the full text of the message (including the original Markdown) |
| Resend/Regenerate | Regenerate an AI message (preserving the context); resend a user message |
| Delete | Delete this message (also clearing the FTS index and generation checkpoints, so it does not come back after a restart) |
| Edit | Edit a user message's content and resend it; an AI message can be edited and used as new context |
| Quote | Reply quoting this message (a quote block is shown at the top of the bubble) |
| Delegate | delegate_agent: delegate it to another assistant to handle |
| Translate | Call translate to translate this message |
| Read aloud | TTS read-aloud (system/cloud engine) |
| Favourite | Save it to favourites (can be looked up across conversations) |
| Branch | Copy the history from this message into a new conversation (branch) |
| Share | Export as Markdown via the system share panel |

## 4. Handling Long Messages

- Overly long AI messages are folded automatically; tap "Expand" to view the full text
- During streaming output, Markdown is rendered in real time (code highlighting/formulas/tables)
- It scrolls automatically to the bottom between messages; following pauses when the user scrolls up to view history

## 5. Input Bar Features

### 5.1 Text Input
- Multi-line input, Enter to send (configurable)
- **Paste detection**: pasted content automatically has common prefixes and suffixes recognised and stripped; over 5000 characters, you are prompted to convert it to a file
- Input drafts: saved automatically when you switch conversations, restored when you switch back

### 5.2 "+" Menu (Enhanced Features)
| Feature | Description |
|---|---|
| Deep thinking | Enables ReasoningLevel.HIGH for this conversation (8000 tokens reasoning budget), not persisted |
| Web search | When enabled, the LLM tends to call web_search / web_fetch |
| Send image | Choose an image to send (automatic compression/OCR text recognition) |
| Voice input | Hold to speak, ASR converts it to text |
| Quick messages | Pre-set chips sent with one tap ({{input}}/{{clipboard}}/{{date}} variables are rendered automatically) |
| Camera | Take a photo and send it |

### 5.3 Model Switching
- The model capsule in the input bar can **temporarily switch the current conversation's model** (history messages are unchanged; new messages use the new model)
- When the assistant has a dedicated model configured, a manual switch overrides the dedicated model (prompt: "The assistant's dedicated model has been overridden; it is restored when you switch conversations")

## 6. Streaming Output and Multi-Turn

- A waiting state is shown before the first token arrives
- Streaming UI updates (text/thinking/tool call status)
- Thinking tags (MOOD/thinking content) can be expanded and folded
- Tool call rounds execute automatically, and generation continues after the results are filled back in, until the final answer
- Multi-turn conversation context is managed automatically; when it is too long it is compressed automatically (keeping the most recent N items)

## 7. Interruption and Recovery

- Stop generation: the content generated so far is kept, and marked as regenerable
- App restart: an interrupted generation can be continued via "Resume"; completed messages are not incorrectly marked
- Generation checkpoints: progress is saved automatically when interrupted, and resumes automatically after a restart

## 8. Common Questions Q&A

1. **"How do I regenerate an AI reply"**: long-press/tap the AI message → Resend (regenerate).
2. **"How do I delete a message"**: long-press the message → Delete. It will not come back after deletion and a restart.
3. **"How do I reply quoting a sentence"**: long-press the message → Quote; a quote block appears in the input bar; enter your reply.
4. **"What is branching"**: copying the history before the current message into a new conversation, so you can continue chatting in a different direction without affecting the original conversation.
5. **"What is deep thinking useful for"**: it gives the model a higher reasoning budget, suitable for complex problems (code/analysis/planning).
6. **"How do I change the model"**: switch directly with the model capsule in the input bar; or Settings → Models and Services for the global default model.
7. **"Why doesn't the AI search the web"**: check whether "Web search" is enabled in the input bar's + menu; once enabled, the AI decides on its own when to search.
8. **"Messages are out of order/duplicated"**: this is usually caused by incorrect marking during interruption recovery in an old version, and has been fixed; if you encounter it, please update the version and report it.

## 9. Key Points for LLM Calls

- Streaming replies are rendered in real time; put thinking content in reasoning, and the main text in content
- Tool call rounds: after calling, wait for the result to be filled back in before continuing; do not fabricate tool results yourself
- When the user scrolls up to view history, new messages do not force a scroll to the bottom (handled by the UI)
- When generation is interrupted, keep the content produced so far, and continue after recovery
