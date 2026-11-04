import { StrictMode, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import type { ChatMessage, ProviderConfig } from "@muse/contracts";
import { detectWebCapabilities } from "@muse/contracts";
import {
  addMemoryFact,
  appendMessage,
  completeFile,
  createAssistant,
  createMemorySpace,
  createSession,
  indexRagDocument,
  listAssistants,
  listMessages,
  listProviders,
  listSessions,
  login,
  presignFile,
  register,
  registerPushSubscription,
  saveProvider,
  searchMemory,
  searchRag,
  streamChat,
  streamDirectByok,
  updateAssistant,
  type AssistantRecord,
  type ClientSession,
  type MemoryFact,
  type RagChunk,
  type SessionRecord,
  type StoredProviderConfig,
} from "./api.js";
import { MessageContent } from "./components/MessageContent.js";
import { HostApp } from "./host/HostApp.js";
import { detectMode, initialMode, type ModeState } from "./host/host-mode.js";
import "./styles.css";
// Muse 组件基元的样式（取值来自 --muse-* 令牌）。基元本身目前没有页面级使用点，
// 这里先把样式层挂上，避免后续引入基元时才发现暗色/令牌没有生效。
import "./components/primitives.css";

const defaultProvider: ProviderConfig = { id: "request-provider", type: "openai", displayName: "OpenAI-compatible", baseUrl: "https://api.openai.com/v1", modelIds: [], supportsStreaming: true };
const providerTypes: ProviderConfig["type"][] = ["openai", "anthropic", "gemini", "deepseek", "openai_responses", "openai_compatible", "custom"];

function Login({ onLogin }: { onLogin: (session: ClientSession) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState("");
  async function submit() {
    try { setError(""); if (registering) { await register(email, password); setRegistering(false); setError("Account created. Please sign in."); } else onLogin(await login(email, password)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Request failed"); }
  }
  return <section className="panel auth-panel"><p className="eyebrow">MUSE WEB · PWA</p><h1>{registering ? "Create account" : "Welcome back"}</h1><p className="lede">BYOK first. Your API key is used only during this browser session and is not written to local storage.</p><input aria-label="Email" placeholder="Email" value={email} onChange={(event) => setEmail(event.target.value)} /><input aria-label="Password" type="password" placeholder="Password (at least 12 characters)" value={password} onChange={(event) => setPassword(event.target.value)} /><button type="button" onClick={() => void submit()}>{registering ? "Register" : "Sign in"}</button><button className="quiet" type="button" onClick={() => { setRegistering(!registering); setError(""); }}>{registering ? "Already have an account? Sign in" : "Create a new account"}</button>{error && <p className="error">{error}</p>}</section>;
}

function ProviderEditor({ session, providers, onSaved }: { session: ClientSession; providers: StoredProviderConfig[]; onSaved: (provider: StoredProviderConfig) => void }) {
  const [id, setId] = useState("");
  const [name, setName] = useState("");
  const [type, setType] = useState<ProviderConfig["type"]>("openai");
  const [baseUrl, setBaseUrl] = useState("https://api.openai.com/v1");
  const [models, setModels] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState("");
  async function submit() {
    try {
      setError("");
      const provider: ProviderConfig = { id: id.trim() || crypto.randomUUID(), type, displayName: name.trim() || type, baseUrl: baseUrl.trim(), modelIds: models.split(",").map((item) => item.trim()).filter(Boolean), supportsStreaming: true };
      onSaved(await saveProvider(session, provider, apiKey.trim())); setApiKey("");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Failed to save provider"); }
  }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">PROVIDER VAULT</p><h3>My Providers</h3></div><span className="muted">Keys are written only to the server-side vault and are not shown again in the list</span></div><div className="provider-cards">{providers.map((item) => <button className="saved-provider" type="button" key={item.id} onClick={() => { setId(item.id); setName(item.displayName); setType(item.type); setBaseUrl(item.baseUrl); setModels(item.modelIds.join(", ")); }}><b>{item.displayName}</b><small>{item.type} · {item.hasSecret ? "Key configured" : "Key missing"}</small></button>)}</div><div className="form-grid"><input placeholder="Configuration ID" value={id} onChange={(event) => setId(event.target.value)} /><input placeholder="Display name" value={name} onChange={(event) => setName(event.target.value)} /><select aria-label="Provider type" value={type} onChange={(event) => setType(event.target.value as ProviderConfig["type"])}>{providerTypes.map((item) => <option key={item} value={item}>{item}</option>)}</select><input placeholder="Base URL（HTTPS）" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} /><input placeholder="Model IDs, separated by commas" value={models} onChange={(event) => setModels(event.target.value)} /><input type="password" placeholder="API key (stored when saved)" value={apiKey} onChange={(event) => setApiKey(event.target.value)} /></div><button type="button" disabled={!apiKey.trim()} onClick={() => void submit()}>Save Provider</button>{error && <p className="error">{error}</p>}</div>;
}

function AssistantEditor({ session, assistants, onSaved }: { session: ClientSession; assistants: AssistantRecord[]; onSaved: (assistant: AssistantRecord) => void }) {
  const [selected, setSelected] = useState<AssistantRecord | undefined>(assistants[0]);
  type AssistantDraft = Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">;
  const [draft, setDraft] = useState<AssistantDraft>({ name: "", identityPrompt: "", relationshipPrompt: "", stylePrompt: "" });
  const [error, setError] = useState("");
  useEffect(() => { const item = selected ?? assistants[0]; if (item) { setSelected(item); setDraft({ name: item.name, identityPrompt: item.identityPrompt, relationshipPrompt: item.relationshipPrompt, stylePrompt: item.stylePrompt }); } }, [assistants, selected]);
  async function save() {
    if (!draft) return;
    try { setError(""); const item = selected ? await updateAssistant(session, { ...selected, ...draft }) : await createAssistant(session, draft); onSaved(item); setSelected(item); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Failed to save assistant"); }
  }
  const update = (key: keyof AssistantDraft, value: string) => setDraft((current) => ({ ...current, [key]: value }));
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">ASSISTANT STUDIO</p><h3>Assistant Persona</h3></div><span className="muted">These prompts are bound to subsequent chats</span></div>{assistants.length > 0 && <select aria-label="Select Assistant" value={selected?.id ?? ""} onChange={(event) => setSelected(assistants.find((item) => item.id === event.target.value))}>{assistants.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select>}<div className="form-grid"><input placeholder="Name" value={draft.name} onChange={(event) => update("name", event.target.value)} /><textarea placeholder="Identity prompt" value={draft.identityPrompt} onChange={(event) => update("identityPrompt", event.target.value)} /><textarea placeholder="Relationship prompt" value={draft.relationshipPrompt} onChange={(event) => update("relationshipPrompt", event.target.value)} /><textarea placeholder="Style prompt" value={draft.stylePrompt} onChange={(event) => update("stylePrompt", event.target.value)} /></div><button type="button" disabled={!draft.name.trim()} onClick={() => void save()}>{selected ? "Save Assistant" : "Create Assistant"}</button>{error && <p className="error">{error}</p>}</div>;
}

function KnowledgePanel({ session, assistantId }: { session: ClientSession; assistantId: string }) {
  const [memoryQuery, setMemoryQuery] = useState("");
  const [facts, setFacts] = useState<MemoryFact[]>([]);
  const [factText, setFactText] = useState("");
  const [spaceId, setSpaceId] = useState("");
  const [spaceName, setSpaceName] = useState("");
  const [ragQuery, setRagQuery] = useState("");
  const [chunks, setChunks] = useState<RagChunk[]>([]);
  const [documentText, setDocumentText] = useState("");
  const [error, setError] = useState("");
  async function runMemorySearch() { try { setFacts(await searchMemory(session, memoryQuery, assistantId, spaceId)); } catch (cause) { setError(cause instanceof Error ? cause.message : "Memory search failed"); } }
  async function createSpace() { try { setError(""); const space = await createMemorySpace(session, spaceName); setSpaceId(space.id); setSpaceName(""); } catch (cause) { setError(cause instanceof Error ? cause.message : "Failed to create memory space"); } }
  async function addFact() { try { setError(""); if (!spaceId) throw new Error("Create a memory space or enter a Space ID first"); await addMemoryFact(session, { assistantId, spaceId, content: factText, importance: 1 }); setFactText(""); if (memoryQuery) await runMemorySearch(); } catch (cause) { setError(cause instanceof Error ? cause.message : "Failed to save memory"); } }
  async function runRagSearch() { try { const result = await searchRag(session, ragQuery); setChunks(result.chunks); } catch (cause) { setError(cause instanceof Error ? cause.message : "RAG search failed"); } }
  async function indexText() { try { setError(""); await indexRagDocument(session, `web-${crypto.randomUUID()}`, documentText); setDocumentText(""); } catch (cause) { setError(cause instanceof Error ? cause.message : "Failed to index document"); } }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">MEMORY · RAG</p><h3>Knowledge and Memory</h3></div><span className="muted">Current Assistant:{assistantId === "default" ? "Default" : assistantId.slice(0, 8)}</span></div><div className="knowledge-grid"><div className="knowledge-card"><h4>Memory Facts</h4><div className="inline-form"><input placeholder="New Memory Space" value={spaceName} onChange={(event) => setSpaceName(event.target.value)} /><button type="button" disabled={!spaceName.trim()} onClick={() => void createSpace()}>Create</button></div><input placeholder="Space ID" value={spaceId} onChange={(event) => setSpaceId(event.target.value)} /><div className="inline-form"><input placeholder="Add a fact" value={factText} onChange={(event) => setFactText(event.target.value)} /><button type="button" disabled={!factText.trim()} onClick={() => void addFact()}>Remember</button></div><div className="inline-form"><input placeholder="Search memories" value={memoryQuery} onChange={(event) => setMemoryQuery(event.target.value)} /><button type="button" onClick={() => void runMemorySearch()}>Search</button></div>{facts.map((fact) => <p className="result-line" key={fact.id}>{fact.content}<small>Importance {fact.importance}  · hits {fact.hitCount}</small></p>)}</div><div className="knowledge-card"><h4>RAG Documents</h4><textarea placeholder="Paste text to create an index" value={documentText} onChange={(event) => setDocumentText(event.target.value)} /><button type="button" disabled={!documentText.trim()} onClick={() => void indexText()}>Index Text</button><div className="inline-form"><input placeholder="Search indexed content" value={ragQuery} onChange={(event) => setRagQuery(event.target.value)} /><button type="button" onClick={() => void runRagSearch()}>Search</button></div>{chunks.map((chunk) => <p className="result-line" key={chunk.id}>{chunk.text}<small>{chunk.documentId} · {chunk.tokenEstimate} tokens</small></p>)}</div></div>{error && <p className="error">{error}</p>}</div>;
}

function FilePanel({ session }: { session: ClientSession }) {
  const [message, setMessage] = useState("After selecting a file, request a one-time upload URL from the API");
  async function upload(file: File) {
    try {
      const fileId = crypto.randomUUID();
      const plan = await presignFile(session, { fileId, name: file.name, mimeType: file.type, sizeBytes: file.size });
      const response = await fetch(plan.upload.url, { method: "PUT", headers: { "content-type": file.type }, body: file });
      if (!response.ok) throw new Error(`Object storage upload failed (${response.status})`);
      const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
      const sha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
      await completeFile(session, { fileId, name: file.name, mimeType: file.type, sizeBytes: file.size, objectKey: plan.objectKey, sha256 });
      setMessage(`${file.name} has been uploaded and registered; the URL is valid until ${new Date(plan.upload.expiresAt).toLocaleTimeString()}`);
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : "File upload failed"); }
  }
  return <div className="workspace-section"><div className="section-title"><div><p className="eyebrow">OBJECT STORAGE</p><h3>Files</h3></div><span className="muted">The API does not receive file bytes; the browser uploads directly to S3-compatible storage</span></div><label className="file-drop">Select File<input type="file" onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload(file); }} /></label><p className="muted">{message}</p></div>;
}

function PushPanel({ session }: { session: ClientSession }) {
  const capabilities = useMemo(() => detectWebCapabilities(), []);
  const [message, setMessage] = useState(capabilities.push ? "This browser supports Push" : "This browser does not support Push");
  async function enable() {
    try { if (!capabilities.push || !capabilities.notifications) throw new Error("This browser does not support notifications"); const key = import.meta.env.VITE_VAPID_PUBLIC_KEY; if (!key) throw new Error("VITE_VAPID_PUBLIC_KEY is not configured"); const permission = await Notification.requestPermission(); if (permission !== "granted") throw new Error("Notification permission was not granted"); const registration = await navigator.serviceWorker.ready; const subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), (char) => char.charCodeAt(0)) }); await registerPushSubscription(session, subscription); setMessage("Push enabled"); } catch (cause) { setMessage(cause instanceof Error ? cause.message : "Failed to enable Push"); }
  }
  return <div className="workspace-section compact-section"><div><p className="eyebrow">PWA</p><h3>Notifications and Offline Support</h3></div><p className="muted">{message}</p><button type="button" disabled={!capabilities.push} onClick={() => void enable()}>Enable Notifications</button></div>;
}

function Workspace({ session, onLogout }: { session: ClientSession; onLogout: () => void }) {
  const [providers, setProviders] = useState<StoredProviderConfig[]>([]);
  const [assistants, setAssistants] = useState<AssistantRecord[]>([]);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { const openWorkspace = () => setOpen(true); window.addEventListener("muse:workspace", openWorkspace); return () => window.removeEventListener("muse:workspace", openWorkspace); }, []);
  useEffect(() => { void Promise.all([listProviders(session), listAssistants(session)]).then(([nextProviders, nextAssistants]) => { setProviders(nextProviders); setAssistants(nextAssistants); }).catch((cause) => setError(cause instanceof Error ? cause.message : "Failed to load workspace")); }, [session]);
  const assistantId = assistants[0]?.id ?? "default";
  return <>{open && <button className="workspace-scrim" type="button" aria-label="Close settings" onClick={() => setOpen(false)} />}<aside className={open ? "workspace open" : "workspace"}><div className="workspace-head"><div><p className="eyebrow">MUSE WORKSPACE</p><h3>Capability Settings</h3></div><button className="quiet" type="button" onClick={() => setOpen(false)}>Collapse</button></div><ProviderEditor session={session} providers={providers} onSaved={(provider) => setProviders((items) => [provider, ...items.filter((item) => item.id !== provider.id)])} /><AssistantEditor session={session} assistants={assistants} onSaved={(assistant) => setAssistants((items) => [assistant, ...items.filter((item) => item.id !== assistant.id)])} /><KnowledgePanel session={session} assistantId={assistantId} /><FilePanel session={session} /><PushPanel session={session} />{error && <p className="error">{error}</p>}<button className="quiet logout-workspace" type="button" onClick={onLogout}>Sign out</button></aside></>;
}

function Chat({ session, onLogout }: { session: ClientSession; onLogout: () => void }) {
  const [sessions, setSessions] = useState<SessionRecord[]>([]);
  const [current, setCurrent] = useState<SessionRecord | undefined>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [mode, setMode] = useState<"byok" | "hosted">("byok");
  const [transport, setTransport] = useState<"direct" | "proxy">("direct");
  const [providers, setProviders] = useState<StoredProviderConfig[]>([]);
  const [assistants, setAssistants] = useState<AssistantRecord[]>([]);
  const [assistantId, setAssistantId] = useState("default");
  const [providerId, setProviderId] = useState("");
  const [model, setModel] = useState("gpt-4o-mini");
  const [busy, setBusy] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [error, setError] = useState("");
  const abortRef = useRef<AbortController | null>(null);
  const provider = providers.find((item) => item.id === providerId) ?? defaultProvider;
  async function refresh() {
    const result = await listSessions(session);
    setSessions(result);
    const target = current && result.find((item) => item.id === current.id) ? current : result[0];
    if (target) {
      setCurrent(target);
      setAssistantId(target.assistantId);
      setMessages(await listMessages(session, target.id));
      return;
    }
    const created = await createSession(session, assistantId === "default" ? undefined : assistantId);
    setSessions([created]);
    setCurrent(created);
    setAssistantId(created.assistantId);
    setMessages([]);
  }
  useEffect(() => { void Promise.all([refresh(), listProviders(session), listAssistants(session)]).then(([, nextProviders, nextAssistants]) => { setProviders(nextProviders); setAssistants(nextAssistants); if (nextProviders[0]) { setProviderId(nextProviders[0].id); if (nextProviders[0].modelIds[0]) setModel(nextProviders[0].modelIds[0]); } }).catch((cause) => setError(cause instanceof Error ? cause.message : "Failed to load")); }, []);
  async function newChat() { const created = await createSession(session, assistantId === "default" ? undefined : assistantId); setSessions((items) => [created, ...items]); setCurrent(created); setAssistantId(created.assistantId); setMessages([]); setMobileSidebarOpen(false); }
  function stop() { abortRef.current?.abort(); }
  async function send() {
    const needsKey = mode === "byok" && transport === "direct";
    if (!current || !input.trim() || busy || (needsKey && !apiKey.trim())) return;
    const text = input.trim(); setInput(""); setBusy(true); setError("");
    const userMessage: ChatMessage = { id: crypto.randomUUID(), role: "user", content: text, createdAt: new Date().toISOString() };
    const history = [...messages, userMessage]; setMessages(history);
    const controller = new AbortController(); abortRef.current = controller;
    try {
      let assistant = ""; let reasoning = "";
      const systemMessage = assistantPrompt(assistants.find((item) => item.id === assistantId));
      const directMessages = systemMessage ? [systemMessage, ...history] : history;
      const directByok = transport === "direct" && mode === "byok";
      if (directByok) await appendMessage(session, current.id, userMessage);
      const events = directByok
        ? streamDirectByok({ messages: directMessages, provider, model, apiKey, signal: controller.signal })
        : streamChat({ session, sessionId: current.id, messages: history, provider, ...(providerId ? { providerId } : {}), model, apiKey, mode, transport, signal: controller.signal });
      for await (const event of events) {
        if (event.type === "content_delta") assistant += event.delta;
        if (event.type === "reasoning_delta") reasoning += event.delta;
        if (event.type === "error") throw new Error(event.message);
        setMessages([...history, { id: "streaming", role: "assistant", content: assistant, ...(reasoning ? { reasoning } : {}), createdAt: new Date().toISOString() }]);
      }
      if (!controller.signal.aborted) {
        if (directByok && assistant.trim()) {
          await appendMessage(session, current.id, { id: crypto.randomUUID(), role: "assistant", content: assistant, ...(reasoning ? { reasoning } : {}), createdAt: new Date().toISOString() });
        }
        await refresh();
      }
    } catch (cause) {
      if (!controller.signal.aborted) { setError(cause instanceof Error ? cause.message : "Failed to send"); setMessages(history); }
    } finally { if (abortRef.current === controller) abortRef.current = null; setBusy(false); }
  }
  return <><main className="app-layout">{mobileSidebarOpen && <button className="mobile-sidebar-scrim" type="button" aria-label="Close session list" onClick={() => setMobileSidebarOpen(false)} />}<aside className={mobileSidebarOpen ? "sidebar mobile-open" : "sidebar"}><div className="brand"><b>Muse</b><span>Web</span></div><button type="button" onClick={() => void newChat()}>+ New chat</button><button className="workspace-toggle" type="button" onClick={() => window.dispatchEvent(new Event("muse:workspace"))}>Configure Provider / Assistant</button><div className="session-list">{sessions.map((item) => <button className={current?.id === item.id ? "session active" : "session"} key={item.id} type="button" onClick={async () => { setCurrent(item); setAssistantId(item.assistantId); setMessages(await listMessages(session, item.id)); setMobileSidebarOpen(false); }}>{item.title}</button>)}</div><button className="quiet" type="button" onClick={onLogout}>Sign out</button></aside><section className="chat"><header><button className="mobile-menu-button" type="button" aria-label="Open session list" onClick={() => setMobileSidebarOpen(true)}>☰</button><div><p className="eyebrow">PRIVATE CONVERSATION</p><h2>{current?.title ?? "New chat"}</h2></div><span className="status">{busy ? "Generating…" : "Online"}</span></header><div className="messages" role="log" aria-live="polite" aria-label="Message history">{messages.length === 0 && <div className="empty"><h3>Start with a single sentence</h3><p>Configure provider, assistant, memory, and file capabilities in the settings panel.</p></div>}{messages.map((message) => <article className={`message ${message.role}`} key={message.id}><small>{message.role === "user" ? "You" : "Muse"}</small><MessageContent content={message.content || "…"} />{message.reasoning && <details><summary>Reasoning</summary><p>{message.reasoning}</p></details>}</article>)}</div><div className="composer"><div className="provider-row"><select aria-label="Billing mode" value={mode} onChange={(event) => setMode(event.target.value as "byok" | "hosted")}><option value="byok">BYOK · My Key</option><option value="hosted">Hosted · Platform Credits</option></select>{mode === "byok" && <select aria-label="Transport" value={transport} onChange={(event) => setTransport(event.target.value as "direct" | "proxy")}><option value="direct">Direct Provider Connection</option><option value="proxy">Server Proxy (Saved Provider)</option></select>}<select aria-label="Assistant" value={assistantId} onChange={(event) => setAssistantId(event.target.value)}><option value="default">Default Assistant</option>{assistants.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select><select aria-label="Provider" value={providerId} onChange={(event) => { setProviderId(event.target.value); const selected = providers.find((item) => item.id === event.target.value); if (selected?.modelIds[0]) setModel(selected.modelIds[0]); }}>{providers.length === 0 && <option value="">Temporary Provider</option>}{providers.map((item) => <option value={item.id} key={item.id}>{item.displayName}</option>)}</select><input aria-label="Model" value={model} onChange={(event) => setModel(event.target.value)} />{mode === "byok" && transport === "direct" && <input aria-label="API Key" type="password" placeholder="API Key for This Session" value={apiKey} onChange={(event) => setApiKey(event.target.value)} />}</div><div className="send-row"><textarea aria-label="Message" placeholder="Write something…" value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void send(); } }} /><button type="button" className={busy ? "stop-button" : "send-button"} disabled={busy ? false : (needsKey(mode, transport) && !apiKey.trim())} onClick={() => busy ? stop() : void send()}>{busy ? "Stop" : "Send"}</button></div>{error && <p className="error">{error}</p>}</div></section></main><Workspace session={session} onLogout={onLogout} /></>;
}

function assistantPrompt(assistant: AssistantRecord | undefined): ChatMessage | undefined {
  if (!assistant) return undefined;
  const content = [assistant.identityPrompt, assistant.relationshipPrompt, assistant.stylePrompt].map((item) => item.trim()).filter(Boolean).join("\n\n").slice(0, 60_000);
  return content ? { id: `system:${assistant.id}`, role: "system", content, createdAt: new Date().toISOString() } : undefined;
}

function needsKey(mode: "byok" | "hosted", transport: "direct" | "proxy"): boolean { return mode === "byok" && transport === "direct"; }

function readStoredClientSession(): ClientSession | undefined {
  const raw = sessionStorage.getItem("muse.session");
  if (!raw) return undefined;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") throw new Error("stored session is not an object");
    const stored = parsed as Partial<ClientSession>;
    const storedToken: unknown = Reflect.get(stored, "token");
    const userId = stored.userId;
    const expiresAt = stored.expiresAt;
    if (typeof storedToken !== "string" || typeof userId !== "string" || typeof expiresAt !== "number") {
      throw new Error("stored session has invalid fields");
    }
    if (expiresAt <= Date.now()) {
      sessionStorage.removeItem("muse.session");
      return undefined;
    }
    return { token: storedToken, userId, expiresAt };
  } catch {
    sessionStorage.removeItem("muse.session");
    return undefined;
  }
}

/** SaaS (cloud account) shell; kept as-is next to the Host mobile shell. */
function SaasApp() {
  const [session, setSession] = useState<ClientSession | undefined>(readStoredClientSession);
  function handleLogin(value: ClientSession) { sessionStorage.setItem("muse.session", JSON.stringify(value)); setSession(value); }
  function logout() { sessionStorage.removeItem("muse.session"); setSession(undefined); }
  return session ? <Chat session={session} onLogout={logout} /> : <main className="shell"><Login onLogin={handleLogin} /></main>;
}

/**
 * Entry gate: a page served by the phone (LAN) prefers Host mode with the PIN;
 * everything else (dev server, SaaS deploy) keeps the cloud account flow.
 */
function App() {
  const [mode, setMode] = useState<ModeState>(initialMode);
  useEffect(() => {
    if (mode !== "pending") return;
    let active = true;
    void detectMode().then((next) => { if (active) setMode(next); });
    return () => { active = false; };
  }, [mode]);
  if (mode === "pending") {
    return <main className="shell"><div className="boot-screen"><span className="boot-dot" /><p>Detecting the runtime environment…</p></div></main>;
  }
  return mode === "host" ? <HostApp /> : <SaasApp />;
}

// iOS over plain http://LAN-IP is not a secure context, so the service worker is
// optional by design: no SW registration must never block the app.
if ("serviceWorker" in navigator && window.isSecureContext) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => undefined);
  });
}
createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
