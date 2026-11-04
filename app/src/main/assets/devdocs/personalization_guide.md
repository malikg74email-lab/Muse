<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Complete Guide to Personalization Settings

> Trigger scenario: When the user asks "How do I change the theme?", "How do I change the font?", "What is the user profile for?" or "How do I let the AI understand me better?", refer to this document and answer truthfully.
> This document is based on the real implementation in the source code AppearanceSettingsStore.kt / SettingsScreen.kt.

## I. Overview

Personalization is divided into two major parts:
1. **Appearance**: Theme (dynamic colour/custom/preset), font, Markdown whitespace
2. **User profile**: The user's background information, injected into the system prompt so the AI understands the user better

## II. The Theme System (Three-Layer Fallback)

In order of priority, from high to low:
1. **Material You dynamic colour** (dynamicColor): Takes its colours from the system wallpaper (Android 12+)
2. **Custom theme** (customTheme): Generates a ColorScheme from a seed colour; primary/secondary/tertiary can be customised
3. **Preset theme** (presetTheme): Multiple built-in colour schemes

Fallback chain: If dynamic colour is available → use it; otherwise the custom theme; otherwise the preset theme.

### Configuration
- Settings → Appearance → Theme
- Custom theme: Select a seed colour → a full colour scheme is generated automatically; the three main colours can be fine-tuned
- Preset theme: Multiple built-in schemes (dark/light/colourful, etc.)

## III. Fonts and Typography

- Settings → Appearance → Font
- Options: System default / multiple built-in fonts
- Markdown whitespace is adjustable (for reading comfort)

## IV. User Profile

### 4.1 Fields
| Field | Description |
|---|---|
| Form of address | The name the AI uses for the user (e.g. "Ziqi") |
| Age | - |
| City | - |
| MBTI | Personality type |
| Occupation | - |
| Interests | A comma-separated list of hobbies |

### 4.2 Injection Mechanism
- The profile content is injected into the system prompt, so the AI reflects it naturally in conversation (form of address/city topics/interest recommendations)
- After a change, it **takes effect for new messages** (historical messages are unchanged)

## V. Chat Appearance Options

| Option | Description |
|---|---|
| Default expanded | Whether long AI messages are expanded/collapsed by default |
| Message display | Display options for bubbles/timestamps/avatars, etc. |
| Temperature | The global default sampling temperature (0-2) |
| Advanced | Context length, etc. |

## VI. Frequently Asked Questions Q&A

1. **"I changed the theme but nothing happened"**: Confirm that the app has not locked the theme; dynamic colour requires Android 12+ and Material You enabled in the system.
2. **"I want the AI to call me by a nickname"**: Settings → Appearance → User Profile → fill in the form of address; it takes effect for new messages.
3. **"How do I restore the font to the default"**: Appearance → Font → select the system default.
4. **"What will the AI use my user profile for"**: It is injected into the system prompt so the AI uses your form of address and talks about topics related to your city/interests; it is not uploaded to the cloud.
5. **"Is the profile the same for every assistant"**: The user profile is global; differences between assistants rely on their respective systemPrompt settings.

## VII. Key Points for LLM Calls

- For the form of address/city/interests, etc. in the user profile, use them naturally in conversation (use the form of address/mention the city's weather/recommend interest topics)
- Do not stiffly recite the profile content (e.g. "According to your MBTI you are INTJ"); blend it in naturally
- After the user modifies their profile, converse according to the new information
- Do not ask the user "What are your interests?" when the profile already contains them
