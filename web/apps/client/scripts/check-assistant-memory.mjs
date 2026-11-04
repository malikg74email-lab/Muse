/**
 * 助手与记忆页的断言：列表/详情渲染、只读参数、记忆分页与搜索空态、能力位缺失提示。
 *
 * 组件用 renderToStaticMarkup 直接渲染（详情视图单独导出，不依赖点击），
 * 交互路径（点行、滚到底、回车搜索）按源码接线断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".assistant-memory-check.mjs");

const ENTRY = `
export { AssistantsPage, AssistantDetailView } from "./src/host/AssistantsPage.tsx";
export { MemoryPage } from "./src/host/MemoryPage.tsx";
export { ASSISTANT_DETAIL_FLAGS, MEMORY_FLAGS, isUnsupportedCommandError, supportsFlag } from "./src/host/featureCapabilities.ts";
export { createElement } from "react";
export { renderToStaticMarkup } from "react-dom/server";
`;

let failures = 0;
const assert = (condition, message) => {
  if (!condition) {
    failures += 1;
    console.error(`✗ ${message}`);
    return false;
  }
  console.log(`✓ ${message}`);
  return true;
};

async function load() {
  await build({
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "assistant-memory-entry.tsx", loader: "tsx" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    loader: { ".css": "empty" },
    external: ["react", "react-dom", "react-dom/server"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const render = (element) => mod.renderToStaticMarkup(element);

const HOST_SETTINGS = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
const HOST_CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const ASSISTANTS = [
  { id: "a1", name: "PROTO-Muse", isDefault: true, avatarUri: "/api/media/face-1" },
  { id: "a2", name: "PROTO-Iris" },
];
const PROVIDERS = [{ id: "p1", name: "OpenAI", enabled: true, models: [{ id: "gpt-x", name: "PROTO-GPT-X" }] }];
const DETAIL = {
  id: "a1",
  name: "PROTO-Muse",
  description: "PROTO-简介",
  modelId: "gpt-x",
  providerId: "p1",
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 4096,
  personaSummary: "PROTO-人设摘要",
  systemPromptLength: 1234,
  toolCount: 12,
  contextMessageSize: 40,
  reasoningLevel: "medium",
  enabled: true,
};

try {
  // ── 1. 能力协商 ─────────────────────────────────────────────
  assert(mod.supportsFlag(undefined, mod.MEMORY_FLAGS) === true, "Old Host (no capability flags) gets one attempt first");
  assert(mod.supportsFlag([], mod.MEMORY_FLAGS) === false, "Capability flags sent but without memory → unsupported");
  assert(mod.supportsFlag(["memory"], mod.MEMORY_FLAGS) === true, "Declared capability flags are recognised");
  assert(mod.isUnsupportedCommandError("unsupported host command: memory.list") === true, "Runtime rejection is also recognised");
  assert(mod.isUnsupportedCommandError("focus_not_held") === false, "Business errors do not count as 'unsupported'");

  // ── 2. 助手列表 ─────────────────────────────────────────────
  const listHtml = render(
    mod.createElement(mod.AssistantsPage, {
      assistants: ASSISTANTS,
      providers: PROVIDERS,
      details: { a1: DETAIL },
      flags: ["assistants_detail", "providers"],
      loading: false,
      detailUnsupported: false,
      onRequestDetail: () => undefined,
      onRequestProviders: () => undefined,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(listHtml.includes("PROTO-Muse") && listHtml.includes("PROTO-Iris"), "List shows all assistant names");
  assert(listHtml.includes(">Default<"), "Default assistant has a badge");
  assert(listHtml.includes("PROTO-GPT-X"), "Detail + providers reveal the model name");
  assert((listHtml.match(/muse-listrow is-interactive/g) ?? []).length === 2, "Each row can be opened for details");
  assert(listHtml.includes('src="/api/media/face-1"'), "Avatar renders using the /api/media URL");

  const emptyList = render(
    mod.createElement(mod.AssistantsPage, {
      assistants: [],
      providers: [],
      details: {},
      flags: ["assistants_detail"],
      loading: false,
      detailUnsupported: false,
      onRequestDetail: () => undefined,
      onRequestProviders: () => undefined,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(emptyList.includes("No assistants yet"), "Empty state shown when there are no assistants");

  // ── 3. 助手详情 ─────────────────────────────────────────────
  const detailHtml = render(
    mod.createElement(mod.AssistantDetailView, {
      assistant: ASSISTANTS[0],
      detail: DETAIL,
      modelName: "PROTO-GPT-X",
      supported: true,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(detailHtml.includes("PROTO-简介"), "Detail shows the description");
  assert(detailHtml.includes("PROTO-人设摘要") && detailHtml.includes("host-persona"), "Persona summary is collapsible (truncated to 6 lines)");
  assert(detailHtml.includes("Show more"), "Persona summary offers a show-more control");
  assert(detailHtml.includes("Temperature") && detailHtml.includes("0.7"), "Temperature is shown read-only");
  assert(detailHtml.includes("4,096"), "Max tokens use Intl grouping");
  assert(detailHtml.includes("1,234 characters configured"), "Prompt shows only the character count, not the body");
  assert(!detailHtml.includes("System prompt body text"), "Prompt body does not appear on the page");
  assert(detailHtml.includes("12"), "Tool count is shown");
  assert(/role="switch"/.test(detailHtml) && detailHtml.includes("disabled"), "Enabled switch is read-only");
  assert(!/<input/i.test(detailHtml), "Read-only parameters have no input fields");
  assert(detailHtml.includes("Start a session with this assistant"), "A session can be started directly from the top");

  const unsupportedDetail = render(
    mod.createElement(mod.AssistantDetailView, {
      assistant: ASSISTANTS[0],
      detail: undefined,
      modelName: undefined,
      supported: false,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(unsupportedDetail.includes("This version can't show assistant details on the web"), "Missing capability flag shows a notice instead of a blank page");

  // ── 4. 记忆页 ───────────────────────────────────────────────
  const memoryPage = (overrides) =>
    render(
      mod.createElement(mod.MemoryPage, {
        memory: {
          items: [],
          hasMore: false,
          loading: false,
          error: undefined,
          query: undefined,
          unsupported: false,
          ...overrides,
        },
        supported: true,
        onRequest: () => undefined,
        onBack: () => undefined,
      }),
    );

  const items = [
    { id: "m1", content: "PROTO-记忆内容", createdAt: Date.now(), source: "conversation", pinned: true },
    { id: "m2", content: "PROTO-第二条", createdAt: Date.now() - 3 * 24 * 60 * 60 * 1000, source: "manual" },
  ];
  const listMemory = memoryPage({ items, hasMore: true });
  assert(listMemory.includes("PROTO-记忆内容") && listMemory.includes("PROTO-第二条"), "All memory contents are listed");
  assert(listMemory.includes("Pinned"), "Pinned badge");
  assert(listMemory.includes("conversation"), "Source label");
  assert(listMemory.includes("Just now") && listMemory.includes("3 days ago"), "Times use localised relative time");
  assert(listMemory.includes("Load more"), "Load-more control shown when there is more");
  const lastMemory = memoryPage({ items, hasMore: false });
  assert(lastMemory.includes("No more memories") && !lastMemory.includes("Load more"), "At the end it shows 'No more memories'");
  const emptyMemory = memoryPage({});
  assert(emptyMemory.includes("No memories yet"), "Guidance shown when there are no memories");
  const noHit = memoryPage({ items: [], query: "PROTO-关键词" });
  assert(noHit.includes("PROTO-关键词") && noHit.includes("Clear"), "Search with no hits shows the keyword and 'Clear'");
  assert(memoryPage({ unsupported: true }).includes("This version can't show memories on the web"), "Notice shown when unsupported");
  assert(memoryPage({ error: "PROTO-错误" }).includes("PROTO-错误"), "Read failure shows the error and a retry");

  // ── 5. 接线 ───────────────────────────────────────────────
  assert(HOST_SETTINGS.includes('openPage.entry.id === "assistants"') && HOST_SETTINGS.includes("<AssistantsPage"), "The 'Assistants' entry in Settings opens the assistants page");
  assert(HOST_SETTINGS.includes('openPage.entry.id === "memory"') && HOST_SETTINGS.includes("<MemoryPage"), "The 'Memory' entry opens the memory page");
  assert(HOST_CHAT.includes("onStartSession={startSessionWith}") && HOST_CHAT.includes('type: "session.new", assistantId'), "Detail page can start a session with the assistantId");
  assert(CONNECTION.includes('type: "assistants.detail", assistantId'), "Assistant detail is requested by assistantId");
  assert(CONNECTION.includes('type: "memory.list", limit: MEMORY_PAGE_SIZE') && CONNECTION.includes("cursor: options.cursor"), "Memory list paginates with limit and cursor");
  assert(CONNECTION.includes('type: "memory.search", query, limit: MEMORY_PAGE_SIZE'), "Memory search carries the query");
  assert(CONNECTION.includes("MEMORY_PAGE_SIZE = 30"), "30 per page (Host default)");
  assert(/host-assistant-actions|host-memory-list|host-persona/.test(CSS), "New style classes exist");

  if (failures > 0) throw new Error(`Assistant and memory assertions failed (${failures} failures)`);
  console.log("\nAll assistant and memory assertions passed (list / detail / pagination / search / capability flags).");
} finally {
  await rm(TEMP, { force: true });
}
