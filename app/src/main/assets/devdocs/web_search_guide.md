<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Web Search Guide Complete Edition

> Trigger scenario: when the user asks "how do I turn on web search" "how do I configure a search engine" "search results are inaccurate" "search timed out" "what is the difference between web_search and web_fetch" "how do I choose between Bing/Tavily/SearXNG" "do I need an API key", refer to this document and answer truthfully.
> This document is based on the real implementation in the source files WebSearchService.kt / SkillExecutor.kt; do not fabricate providers or parameters that do not exist.

## 1. Overview

Shaheen 2's web search is carried by two tools:
- **web_search**: keyword search, returns a list of titles + URLs + snippets (for the LLM to choose from)
- **web_fetch**: fetches the body text of a given URL web page (to read the full text)

The search is implemented by three providers, switched according to the user's configuration: Bing (default, no key) / SearXNG (self-hosted, no key) / Tavily (API key required).

After turning on "Web Search" in the "+" menu of the chat input bar, the LLM decides on its own when to invoke these two tools; even without turning this switch on, the LLM can also invoke them as needed (the tools are always in the schema); the switch is only a hint that makes the model "more inclined to search".

## 2. Detailed Explanation of the Three Providers

### 1. BingProvider (Default)

| Item | Value |
|---|---|
| URL | `https://cn.bing.com/search?q={encoded query}&count={maxResults*2}` |
| Implementation | HTML fetching + regex parsing |
| API key | Not required |
| Parsing | Match `<li class="b_algo">` blocks; inside a block, `<h2><a href="URL">title</a></h2>` takes the title and link; the snippet prefers `<p class="b_lineclamp...">`, falling back to the `<p>` inside `<div class="b_caption">` |
| User-Agent | `Mozilla/5.0 (Linux; Android 12) muse/1.0` |
| Characteristics | Zero configuration, works out of the box; parsing depends on Bing's page structure, and a page redesign may break it (in that case it falls back to an empty result and will not crash) |

### 2. SearXNGProvider (Self-Hosted)

| Item | Value |
|---|---|
| URL | `{endpoint}/search?q={query}&format=json&categories=general&language=en` |
| Default endpoint | `https://searx.be` (public instance, may be unstable) |
| Implementation | JSON parsing of the `results` array, taking `title` / `url` / `content` |
| API key | Not required (self-hosted instance; public instances may rate-limit) |
| Configuration | Settings → Models & Services → Web Search, fill in the endpoint of your self-built instance |
| Characteristics | Privacy-friendly, self-controllable; only stable if you deploy your own instance |

### 3. TavilyProvider (API)

| Item | Value |
|---|---|
| URL | `POST {endpoint}/search`, default `https://api.tavily.com` |
| Body | `{ api_key, query, max_results, search_depth="basic" }` |
| API key | **Required**; without a key it returns an empty list |
| Configuration | Settings → Models & Services → Web Search, fill in the API key |
| Characteristics | High-quality, stable results; but you need to go to tavily.com to apply for a key (free quota) |

## 3. Provider Switching and Configuration

### Configuration Entry Points
1. **Settings → Models & Services → Web Search**: configure provider / apiKey / endpoint (advanced configuration)
2. **Settings → Chat → Default Search Engine**: quickly switch auto / searxng / tavily / bing

### Distribution Mechanism
- `CompositeWebSearchService` constructs the corresponding delegate according to `WebSearchConfig.providerName`
- Supported names: `"SearXNG"` / `"Tavily"` / `"Bing"` (default `"Bing"`)
- Switching the provider at runtime rebuilds the delegate (synchronized; the switch takes effect immediately)

## 4. web_fetch Implementation Details (Fetching Web Page Body Text)

- Uses OkHttpClient(named("chat")) to issue a GET, User-Agent: `Mozilla/5.0 (Android LLM client)`
- **Fetch limit 200,000 characters** (take(200_000))
- **Return limit 50,000 characters** (take(50_000))
- htmlToText processing chain: first remove script/style/noscript blocks → then remove all HTML tags → finally use Html.fromHtml to process entities (&amp; &lt;, etc.)
- Does not use Jsoup (to avoid increasing the APK size)
- Collapses consecutive whitespace

## 5. Timeout and Network Parameters

| Client | connectTimeout | readTimeout | writeTimeout | Purpose |
|---|---|---|---|---|
| named("chat") | 30s | 120s | 30s | web_fetch / general LLM requests |
| named("webSearch") | 8s | 15s | - | Dedicated to web_search |

web_search uses an 8s connection + 15s read; slow public search instances time out easily (shown as returning an empty result); in that case it is recommended to switch the provider or self-host SearXNG.

## 6. Common User Questions Q&A

1. **"How do I turn on web search"**: Chat input bar + menu → turn on "Web Search". After that, when you ask questions that need real-time information (news/prices/policies/versions), the LLM will automatically invoke web_search.
2. **"Search results are inaccurate / cannot be found"**: ① Confirm you are using Bing (default) and the network is normal; ② A Bing page redesign may affect parsing, wait for an update; ③ You can switch to Tavily (key required) or self-hosted SearXNG for greater stability; ④ Make search terms as specific as possible (keywords in the language of the question — English or Roman Urdu — avoid whole colloquial sentences).
3. **"Do I need an API key"**: Bing and self-hosted SearXNG do not need one; Tavily does.
4. **"What is the difference between web_search and web_fetch"**: web_search returns a list of titles/URLs/snippets (for locating); web_fetch fetches the full text of a given URL (for close reading). The correct approach is "search first, then read": web_search finds candidate URLs → web_fetch reads the content.
5. **"Are search results real-time"**: Yes, every invocation is a real-time request to the search engine, not a cache.
6. **"Will searching leak my privacy"**: The search request goes through the system network configuration; Bing queries are sent to Microsoft; if you care about privacy, you can use self-hosted SearXNG.
7. **"Why are the results in English"**: Results follow the language of the query — ask in English or Roman Urdu and the results come back in that language. Bing uses the cn domain; the SearXNG provider's preset sends `language=en` (a preset code value, see §2), so SearXNG results lean English even for non-English queries; Tavily results are mainly in English.
8. **"What should I do about a search timeout"**: Check the network/proxy; public SearXNG instances are unstable, switching is recommended; for Tavily, confirm the key is valid and the quota has not been exceeded.

## 7. Key Points for LLM Invocation

- **When to use web_search**: when the user asks for real-time information (news/prices/policies/weather/latest versions/recent status of a person), facts that need external verification, or the user explicitly asks to "look it up / search for it".
- **When to use web_fetch**: when, among the candidate URLs returned by web_search, the title/snippet is insufficient to answer; or when the user gives a specific URL and asks to read its content.
- **Parameters**: use refined keywords in the user's language for the query (not whole colloquial sentences); max_results defaults to 5, maximum 10.
- **Boundaries**: a single search returns at most 10 results; web page body text over 50,000 characters is truncated; when fetching fails (timeout/404/anti-scraping), state it truthfully and do not fabricate content.
- **Order**: first use web_search to locate → then web_fetch to read closely; do not skip the search and guess a URL directly.
- **Failure fallback**: if a search returns empty / times out, do not fabricate results; tell the user "the current search channel is unavailable", and suggest checking the network or switching provider.

## 8. Troubleshooting Table

| Symptom | Possible Cause | Troubleshooting |
|---|---|---|
| web_search always returns empty results | Bing parsing failure / network unreachable / proxy blocking | Turn on debug logs and look at WebSearchService; try switching provider |
| Switching provider has no effect | Configuration save failed | Confirm the Settings page saved successfully; CompositeWebSearchService is rebuilt at runtime, no restart needed |
| web_fetch cannot fetch body text | Target site anti-scraping / JS rendering (content loaded dynamically) | Static HTML can be fetched; being unable to fetch JS-rendered pages is expected; inform the user |
| Tavily empty results | Key invalid / quota exceeded / endpoint filled in incorrectly | Check the key; check usage in the Tavily console |
| Search is slow | Public SearXNG instance congestion | Self-host an instance; or switch to Bing (default, faster) |
