/**
 * 知识库页与定时任务页的断言：两页渲染、文档分页、任务字段格式化、空态、能力位。
 *
 * 组件用 renderToStaticMarkup 直接渲染；交互（点知识库、滚到底）与请求接线按源码断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".knowledge-tasks-check.mjs");

const ENTRY = `
export { KnowledgePage, KnowledgeDocumentsView } from "./src/host/KnowledgePage.tsx";
export { TasksPage } from "./src/host/TasksPage.tsx";
export { KNOWLEDGE_FLAGS, TASK_FLAGS, supportsFlag } from "./src/host/featureCapabilities.ts";
export { formatRelativeTime, formatBytes } from "./src/i18n/format.ts";
export { ZH, EN } from "./src/i18n/messages.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "knowledge-tasks-entry.tsx", loader: "tsx" },
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
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const KNOWLEDGE = {
  bases: [
    { id: "kb1", name: "PROTO-知识库", documentCount: 12, chunkCount: 345, createdAt: 1, updatedAt: 2, embeddingBackend: "PROTO-bge-m3" },
    { id: "kb2", name: "PROTO-无向量库", documentCount: 0, chunkCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  loading: false,
  error: undefined,
  unsupported: false,
};

const DOCUMENTS = (items, hasMore) => ({ kbId: "kb1", items, hasMore, loading: false, error: undefined, unsupported: false });

const knowledgePage = (overrides = {}) =>
  render(
    mod.createElement(mod.KnowledgePage, {
      knowledge: KNOWLEDGE,
      documents: DOCUMENTS([], false),
      supported: true,
      onRequestBases: () => undefined,
      onRequestDocuments: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

const tasksPage = (overrides = {}) =>
  render(
    mod.createElement(mod.TasksPage, {
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      supported: true,
      onRequest: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

try {
  // ── 1. 知识库列表 ───────────────────────────────────────────
  const listHtml = knowledgePage();
  assert(listHtml.includes("PROTO-知识库") && listHtml.includes("PROTO-无向量库"), "Lists all knowledge bases");
  assert(listHtml.includes("12 docs · 345 chunks"), "Document and chunk counts use localised formatting");
  assert(listHtml.includes("PROTO-bge-m3"), "Shows the embedding backend");
  assert(listHtml.includes("Not set"), "Placeholder shown when there is no vector backend");
  assert((listHtml.match(/muse-listrow is-interactive/g) ?? []).length === 2, "Each knowledge base can be opened to its document list");
  assert(listHtml.includes("Import and manage knowledge bases on your phone"), "Clearly states read-only: import and manage on your phone");

  const emptyKnowledge = knowledgePage({ knowledge: { ...KNOWLEDGE, bases: [] } });
  assert(emptyKnowledge.includes("No knowledge bases yet"), "Empty state shown when there are no knowledge bases");
  const unsupportedKnowledge = knowledgePage({ supported: false });
  assert(unsupportedKnowledge.includes("This version can't show knowledge bases on the web"), "Notice shown when the capability flag is missing");
  const failedKnowledge = knowledgePage({ knowledge: { ...KNOWLEDGE, error: "PROTO-错误" } });
  assert(failedKnowledge.includes("PROTO-错误") && failedKnowledge.includes(">Retry<"), "Read failure shows the error and a retry");

  // ── 2. 文档列表与分页 ───────────────────────────────────────
  const doc = (id, title, chunks, size, status) => ({ id, title, chunkCount: chunks, sizeBytes: size, createdAt: Date.now(), status });
  const docsView = (items, hasMore, overrides = {}) =>
    render(
      mod.createElement(mod.KnowledgeDocumentsView, {
        base: KNOWLEDGE.bases[0],
        documents: { kbId: "kb1", items, hasMore, loading: false, error: undefined, unsupported: false },
        supported: true,
        onRequestDocuments: () => undefined,
        onBack: () => undefined,
        ...overrides,
      }),
    );
  const docsWithMore = docsView([doc("d1", "PROTO-文档一", 12, 2048, "indexed"), doc("d2", "PROTO-文档二", 3, 300)], true);
  assert(docsWithMore.includes("PROTO-文档一") && docsWithMore.includes("PROTO-文档二"), "Document titles are listed");
  assert(docsWithMore.includes("12 chunks"), "Document chunk count");
  assert(/KB|kB/.test(docsWithMore), `Document size uses Intl units: ${docsWithMore.includes("2 KB") ? "2 KB" : "see page"}`);
  assert(docsWithMore.includes("indexed"), "Document status is shown as-is");
  assert(docsWithMore.includes("Load more"), "Load-more control shown when there is a next page");
  const docsEnd = docsView([doc("d1", "PROTO-文档一", 12, 2048, "indexed")], false);
  assert(docsEnd.includes("No more") && !docsEnd.includes("Load more"), "At the end it shows 'No more'");
  const docsEmpty = docsView([], false);
  assert(docsEmpty.includes("No documents in this knowledge base yet"), "Empty state shown when a knowledge base has no documents");
  assert(docsView([], false, { supported: false }).includes("This version can't show knowledge bases on the web"), "Documents page also shows a notice when the capability flag is missing");
  assert(mod.formatBytes(300) === "300 byte" || mod.formatBytes(300).includes("300"), `Byte formatting: ${mod.formatBytes(300)}`);
  assert(/KB|kB/.test(mod.formatBytes(2048)), `KB formatting: ${mod.formatBytes(2048)}`);
  assert(/MB/.test(mod.formatBytes(5 * 1024 * 1024)), `MB formatting: ${mod.formatBytes(5 * 1024 * 1024)}`);
  assert(CONNECTION.includes('type: "knowledge.documents"') && CONNECTION.includes("cursor: options.cursor"), "Document pagination carries the cursor");
  assert(CONNECTION.includes("KNOWLEDGE_PAGE_SIZE = 20"), "20 per page (Host default)");
  assert(/host-memory-more/.test(readFileSync(join(ROOT, "src", "host", "KnowledgePage.tsx"), "utf8")), "Load button shown when there is more");

  // ── 3. 定时任务 ─────────────────────────────────────────────
  const now = Date.now();
  const task = {
    id: "t1",
    name: "PROTO-每日简报",
    cron: "0 8 * * *",
    enabled: true,
    nextRunAt: now + 3 * 60 * 60 * 1000 + 5 * 60 * 1000,
    lastRunAt: now - 2 * 60 * 60 * 1000,
    lastResult: "PROTO-上次结果",
  };
  const tasksHtml = tasksPage({ tasks: { items: [task], loading: false, error: undefined, unsupported: false } });
  assert(tasksHtml.includes("PROTO-每日简报"), "Task name");
  assert(tasksHtml.includes("0 8 * * *"), "cron is shown as-is");
  assert(tasksHtml.includes("Enabled"), "Enabled state");
  assert(tasksHtml.includes("in 3 h"), "Next run uses future relative time");
  assert(mod.formatRelativeTime(now + 3 * 60 * 60 * 1000 + 5 * 60 * 1000, now, "zh") === "in 3 h", "Future hours are rounded without crossing buckets (stable boundary)");
  assert(tasksHtml.includes("2 h ago"), "Last run uses relative time");
  assert(tasksHtml.includes("PROTO-上次结果"), "Last result");
  assert(tasksHtml.includes("Manage scheduled tasks on your phone"), "Clearly states read-only: manage on your phone");

  const disabledTask = tasksPage({ tasks: { items: [{ ...task, enabled: false, nextRunAt: undefined, lastRunAt: undefined, lastResult: undefined }], loading: false, error: undefined, unsupported: false } });
  assert(disabledTask.includes("Paused"), "Disabled tasks show 'Paused'");
  assert((disabledTask.match(/Never run yet/g) ?? []).length >= 2, "When there is no next/last run it shows 'Never run yet'");

  const emptyTasks = tasksPage();
  assert(emptyTasks.includes("No scheduled tasks yet"), "Guidance shown when there are no tasks");
  const unsupportedTasks = tasksPage({ supported: false });
  assert(unsupportedTasks.includes("This version can't show scheduled tasks on the web"), "Notice shown when the capability flag is missing");
  const failedTasks = tasksPage({ tasks: { items: [], loading: false, error: "PROTO-错误", unsupported: false } });
  assert(failedTasks.includes("PROTO-错误") && failedTasks.includes(">Retry<"), "Task read failure can be retried");

  // ── 4. 未来相对时间（zh / en）──────────────────────────────
  assert(mod.formatRelativeTime(now - 30 * 1000, now, "zh") === "Just now", "30 seconds ago is still Just now");
  assert(mod.formatRelativeTime(now + 30 * 1000, now, "zh") === "Any moment", "Half a minute in the future is 'Any moment'");
  assert(mod.formatRelativeTime(now + 12 * 60 * 1000, now, "zh") === "in 12 min", "Future minutes");
  assert(mod.formatRelativeTime(now + 3 * 60 * 60 * 1000, now, "en") === "in 3 h", "Future hours (en)");
  assert(mod.formatRelativeTime(now + 2 * 24 * 60 * 60 * 1000, now, "zh") === "in 2 days", "Future days");

  // ── 5. 词条与接线 ─────────────────────────────────────────
  for (const key of ["knowledge.title", "knowledge.readOnly", "knowledge.documents", "knowledge.unsupported", "tasks.title", "tasks.readOnly", "tasks.nextRun", "tasks.unsupported", "common.loadMore", "common.noMore"]) {
    assert(typeof mod.ZH[key] === "string" && typeof mod.EN[key] === "string" && mod.ZH[key].length > 0 && mod.EN[key].length > 0, `Entry ${key} exists in both ZH and EN`);
  }
  assert(HOST_SETTINGS.includes('openPage.entry.id === "rag"') && HOST_SETTINGS.includes("<KnowledgePage"), "The 'Knowledge' entry opens the knowledge page");
  assert(HOST_SETTINGS.includes('openPage.entry.id === "scheduled_tasks"') && HOST_SETTINGS.includes("<TasksPage"), "The 'Scheduled tasks' entry opens the tasks page");
  assert(mod.supportsFlag(undefined, mod.KNOWLEDGE_FLAGS) === true && mod.supportsFlag([], mod.KNOWLEDGE_FLAGS) === false, "Knowledge capability flag check");
  assert(mod.supportsFlag(["tasks"], mod.TASK_FLAGS) === true && mod.supportsFlag(["knowledge"], mod.TASK_FLAGS) === false, "Scheduled tasks capability flag check");
  assert(CONNECTION.includes('type: "knowledge.list"') && CONNECTION.includes('type: "tasks.list"'), "Both read commands can be sent");
  assert(/host-task-cron|host-task-meta/.test(CSS), "Task card styles exist");

  if (failures > 0) throw new Error(`Knowledge and scheduled tasks assertions failed (${failures} failures)`);
  console.log("\nAll knowledge and scheduled tasks assertions passed (both pages render / pagination / task formatting / empty states / capability flags).");
} finally {
  await rm(TEMP, { force: true });
}
