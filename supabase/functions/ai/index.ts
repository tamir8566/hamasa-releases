// AI proxy for HamasaHagadol (Supabase Edge Function, Deno). The app never holds the AI keys: it posts here
// with the anon key + its recovery code, and this function calls Claude or Gemini with the secrets
// ANTHROPIC_API_KEY / GEMINI_API_KEY (Supabase dashboard -> Edge Functions -> Secrets). See docs/ai.md.
//
// Request (POST, JSON), header x-profile-code: XXXX-XXXX-XXXX
//   { provider: "claude" | "gemini", tier: "MAIN" | "LITE", system?: string, json?: boolean,
//     temperature?: number, maxTokens?: number, parts: [{ text } | { imageBase64, mime } | { videoUrl }] }  (videoUrl: YouTube, Gemini only)
// Response (JSON): { text, provider, error? }
//   200 = the provider was reached: text is the answer, or "" with error ("HTTP 429: ...", like the app's own clients)
//   400 = bad request (validation), 403 = this recovery code may not use the AI, 405 = not POST,
//   500 = missing secret / internal error.
//
// Who may call (the anon key and this URL are inside the app, so they are public; the recovery code is the lock):
//   1. a code listed in the secret AI_ALLOWED_CODES ("ABCD-EFGH-JK23,MNPQ-RSTU-VW45"): how a new user is let in;
//   2. a code that already used the AI successfully before OLD_USERS_UNTIL (read from public.ai_log, which only
//      this function can write): the owner and the friends who had the app before the code went public.
// Everyone else gets 403 before any provider is called, so a stranger costs nothing. See docs/ai.md.
//
// Behaviour mirrors app/src/main/java/com/tamir/hamasa/data/ai/ClaudeClient.kt and GeminiClient.kt so the answer
// is the same whether the app calls a provider directly or through here.

const CLAUDE_URL = "https://api.anthropic.com/v1/messages";
const CLAUDE_VERSION = "2023-06-01"; // still accepts temperature
const CLAUDE_MODELS: Record<string, string> = { MAIN: "claude-sonnet-5-5", LITE: "claude-haiku-5-5" };
const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
const GEMINI_MODELS: Record<string, string> = { MAIN: "gemini-3.6-flash", LITE: "gemini-3.5-flash-lite" };

const MAX_IMAGE_BYTES = 5 * 1024 * 1024; // decoded size of one image
const MAX_BODY_BYTES = 16 * 1024 * 1024; // whole request (base64 text included)
const MAX_TOKENS = 16000;
const UPSTREAM_TIMEOUT_MS = 60_000;
const CODE_RE = /^[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$/;
const JSON_RULE =
  "Answer ONLY with one valid JSON object. No markdown, no code fences, no text before or after the JSON.";

type Part = { text?: string; imageBase64?: string; mime?: string; videoUrl?: string };
type Req = {
  provider: "claude" | "gemini";
  tier: "MAIN" | "LITE";
  system?: string;
  json?: boolean;
  temperature?: number;
  maxTokens?: number;
  parts: Part[];
};
type Out = { text: string; error?: string };

function reply(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

/** Decoded byte size of a base64 string (no decoding needed). */
function b64Bytes(s: string): number {
  const pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
  return Math.floor((s.length * 3) / 4) - pad;
}

function validate(raw: unknown): Req | string {
  if (typeof raw !== "object" || raw === null) return "body must be a JSON object";
  const r = raw as Record<string, unknown>;
  if (r.provider !== "claude" && r.provider !== "gemini") return "provider must be claude or gemini";
  const tier = r.tier === undefined ? "MAIN" : r.tier;
  if (tier !== "MAIN" && tier !== "LITE") return "tier must be MAIN or LITE";
  if (r.system !== undefined && r.system !== null && typeof r.system !== "string") return "system must be a string";
  if (r.json !== undefined && typeof r.json !== "boolean") return "json must be a boolean";
  if (r.temperature !== undefined && r.temperature !== null && typeof r.temperature !== "number") return "temperature must be a number";
  if (r.maxTokens !== undefined && r.maxTokens !== null) {
    if (typeof r.maxTokens !== "number" || !Number.isFinite(r.maxTokens) || r.maxTokens < 1) return "maxTokens must be a positive number";
    if (r.maxTokens > MAX_TOKENS) return `maxTokens must be <= ${MAX_TOKENS}`;
  }
  if (!Array.isArray(r.parts) || r.parts.length === 0) return "parts must be a non-empty array";
  if (r.parts.length > 20) return "too many parts";
  for (const p of r.parts as unknown[]) {
    if (typeof p !== "object" || p === null) return "every part must be an object";
    const part = p as Part;
    if (typeof part.text === "string") continue;
    if (typeof part.imageBase64 === "string") {
      if (b64Bytes(part.imageBase64) > MAX_IMAGE_BYTES) return "image larger than 5 MB";
      if (part.mime !== undefined && typeof part.mime !== "string") return "mime must be a string";
      continue;
    }
    if (typeof part.videoUrl === "string") {
      if (!/^https:\/\/(www\.|m\.)?(youtube\.com|youtu\.be)\//.test(part.videoUrl)) return "videoUrl must be a YouTube link";
      if (r.provider !== "gemini") return "videoUrl needs provider gemini";
      continue;
    }
    return "every part needs text, imageBase64 or videoUrl";
  }
  return {
    provider: r.provider as "claude" | "gemini",
    tier: tier as "MAIN" | "LITE",
    system: typeof r.system === "string" ? r.system : undefined,
    json: r.json === true,
    temperature: typeof r.temperature === "number" ? r.temperature : 0.4,
    maxTokens: typeof r.maxTokens === "number" ? Math.floor(r.maxTokens) : 8192,
    parts: r.parts as Part[],
  };
}

async function post(url: string, headers: Record<string, string>, body: unknown): Promise<{ code: number; text: string }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8", ...headers },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
    return { code: res.status, text: await res.text() };
  } catch (e) {
    const name = e instanceof Error ? e.name : "Error";
    const msg = e instanceof Error ? e.message : String(e);
    // "timeout" / "connect" words keep the app's Hebrew error mapping (AiClient.errorHe) meaningful
    return { code: -1, text: name === "AbortError" ? "timeout" : `connectexception: ${msg}` };
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------- Claude (same rules as ClaudeClient.kt)

function claudeApiError(text: string): string {
  try {
    const err = JSON.parse(text)?.error;
    if (err) return `${err.type ?? ""}: ${String(err.message ?? "").slice(0, 240)}`;
  } catch (_) { /* not JSON */ }
  return text.slice(0, 300);
}

async function callClaude(req: Req, key: string): Promise<Out> {
  const model = CLAUDE_MODELS[req.tier];
  const sys = req.json ? (req.system ? `${req.system}\n\n${JSON_RULE}` : JSON_RULE) : req.system;
  const content = req.parts.map((p) =>
    typeof p.text === "string"
      ? { type: "text", text: p.text }
      : { type: "image", source: { type: "base64", media_type: p.mime || "image/jpeg", data: p.imageBase64 } }
  );
  // Floor: Hebrew costs many tokens and a model that thinks first spends the same budget.
  let budget = Math.min(Math.max(req.maxTokens ?? 8192, 2048), MAX_TOKENS);
  let temp: number | null = req.temperature ?? 0.4;
  for (let attempt = 1; attempt <= 3; attempt++) {
    const body: Record<string, unknown> = {
      model,
      max_tokens: budget,
      messages: [{ role: "user", content }],
    };
    if (sys && sys.trim() !== "") body.system = sys;
    if (temp !== null) body.temperature = Math.min(Math.max(temp, 0), 1);
    const resp = await post(CLAUDE_URL, { "x-api-key": key, "anthropic-version": CLAUDE_VERSION }, body);
    if (resp.code === -1) return { text: "", error: resp.text };
    if (resp.code < 200 || resp.code > 299) {
      // Some models accept only the default sampling: ask again once without temperature.
      if (resp.code === 400 && temp !== null && resp.text.includes("temperature")) {
        temp = null;
        continue;
      }
      return { text: "", error: `HTTP ${resp.code}: ${claudeApiError(resp.text)}` };
    }
    let root: any;
    try {
      root = JSON.parse(resp.text);
    } catch (_) {
      return { text: "", error: `bad response: ${resp.text.slice(0, 200)}` };
    }
    const stop = String(root?.stop_reason ?? "");
    const blocks: any[] = Array.isArray(root?.content) ? root.content : [];
    const text = blocks.filter((b) => b?.type === "text").map((b) => String(b.text ?? "")).join("").trim();
    if (text === "" && stop === "max_tokens" && budget < MAX_TOKENS) {
      // the whole budget went to thinking: one more try with more room
      budget = Math.min(budget * 4, MAX_TOKENS);
      continue;
    }
    if (text === "") return { text: "", error: `empty answer, stop_reason=${stop}` };
    if (stop === "refusal") return { text: "", error: "refusal" };
    return { text };
  }
  return { text: "", error: "no answer after retries" };
}

// ---------------------------------------------------------------- Gemini (same rules as GeminiClient.kt)

async function callGemini(req: Req, key: string): Promise<Out> {
  const model = GEMINI_MODELS[req.tier];
  const parts = req.parts.map((p) =>
    typeof p.text === "string"
      ? { text: p.text }
      : typeof p.videoUrl === "string"
      ? { file_data: { file_uri: p.videoUrl } }
      : { inline_data: { mime_type: p.mime || "image/jpeg", data: p.imageBase64 } }
  );
  const gen: Record<string, unknown> = {
    temperature: req.temperature ?? 0.4,
    // floor: thinking tokens share this budget
    maxOutputTokens: Math.max(req.maxTokens ?? 8192, 2048),
    // Gemini 3.x thinks before answering; keep it low or the answer can come back empty (MAX_TOKENS)
    thinkingConfig: { thinkingLevel: "low" },
  };
  if (req.json) gen.responseMimeType = "application/json";
  const body: Record<string, unknown> = {
    contents: [{ role: "user", parts }],
    generationConfig: gen,
  };
  if (req.system !== undefined) body.system_instruction = { parts: [{ text: req.system }] };
  const resp = await post(`${GEMINI_BASE}${model}:generateContent`, { "x-goog-api-key": key }, body);
  if (resp.code === -1) return { text: "", error: resp.text };
  if (resp.code < 200 || resp.code > 299) return { text: "", error: `HTTP ${resp.code}: ${resp.text.slice(0, 300)}` };
  let root: any;
  try {
    root = JSON.parse(resp.text);
  } catch (_) {
    return { text: "", error: `bad response: ${resp.text.slice(0, 200)}` };
  }
  const cand = Array.isArray(root?.candidates) ? root.candidates[0] : undefined;
  const outParts: any[] = Array.isArray(cand?.content?.parts) ? cand.content.parts : [];
  const text = outParts.filter((p) => p?.thought !== true).map((p) => String(p?.text ?? "")).join("").trim();
  if (text === "") return { text: "", error: `empty answer, finishReason=${String(cand?.finishReason ?? "")}` };
  return { text };
}

// ---------------------------------------------------------------- optional usage log (no limits)

async function logCall(code: string, req: Req, ok: boolean, ms: number, inBytes: number, error: string | undefined) {
  const url = Deno.env.get("SUPABASE_URL");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !service) return;
  try {
    await fetch(`${url}/rest/v1/ai_log`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        apikey: service,
        authorization: `Bearer ${service}`,
        prefer: "return=minimal",
      },
      body: JSON.stringify({
        profile_code: code,
        provider: req.provider,
        tier: req.tier,
        ok,
        ms,
        in_bytes: inBytes,
        error: (error ?? "").slice(0, 300),
      }),
    });
  } catch (_) {
    // the log is best effort (table missing = docs/multiuser.sql not run yet)
  }
}

/** Codes that used the AI before this moment are the existing users (the day the code repository went public). */
const OLD_USERS_UNTIL = "2026-11-23T12:30:00Z";
/** The code of the server checks (.github/workflows); never a user. */
const TEST_CODE = "TEST-TEST-TEST";
/** Codes already found allowed by this instance (a refusal is never cached: a code can be added to the secret). */
const knownAllowed = new Set<string>();

function listedCode(code: string): boolean | null {
  const raw = (Deno.env.get("AI_ALLOWED_CODES") ?? "").trim();
  if (raw === "") return null;
  const list = raw.split(/[\s,;]+/).map((s) => s.trim().toUpperCase()).filter((s) => s !== "");
  return list.includes(code.toUpperCase());
}

/**
 * True when [code] may spend the AI keys. The log lookup failing (table missing, network) falls back to the old
 * open behaviour ONLY while the secret is not set, so a broken lookup can never lock the owner out.
 */
async function allowedCode(code: string): Promise<boolean> {
  if (knownAllowed.has(code)) return true;
  const listed = listedCode(code);
  if (listed === true) {
    knownAllowed.add(code);
    return true;
  }
  if (code === TEST_CODE) return false;
  const url = Deno.env.get("SUPABASE_URL");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  let lookupWorked = false;
  if (url && service) {
    try {
      const q = `${url}/rest/v1/ai_log?select=id&profile_code=eq.${encodeURIComponent(code)}&ok=eq.true` +
        `&at=lt.${encodeURIComponent(OLD_USERS_UNTIL)}&limit=1`;
      const r = await fetch(q, { headers: { apikey: service, authorization: `Bearer ${service}` } });
      if (r.ok) {
        lookupWorked = true;
        const rows = await r.json();
        if (Array.isArray(rows) && rows.length > 0) {
          knownAllowed.add(code);
          return true;
        }
      }
    } catch (_) {
      // falls through
    }
  }
  if (lookupWorked) return false;
  return listed === null;
}

// ---------------------------------------------------------------- entry point

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return reply(405, { text: "", provider: "", error: "POST only" });

  const code = (request.headers.get("x-profile-code") ?? "").trim();
  if (!CODE_RE.test(code)) return reply(400, { text: "", provider: "", error: "missing or bad x-profile-code" });
  if (!(await allowedCode(code))) return reply(403, { text: "", provider: "", error: "this code may not use the AI" });

  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) return reply(400, { text: "", provider: "", error: "request too large" });
  let rawText: string;
  try {
    rawText = await request.text();
  } catch (_) {
    return reply(400, { text: "", provider: "", error: "cannot read body" });
  }
  if (rawText.length > MAX_BODY_BYTES) return reply(400, { text: "", provider: "", error: "request too large" });

  let parsed: unknown;
  try {
    parsed = JSON.parse(rawText);
  } catch (_) {
    return reply(400, { text: "", provider: "", error: "body is not JSON" });
  }
  const req = validate(parsed);
  if (typeof req === "string") return reply(400, { text: "", provider: "", error: req });

  const key = req.provider === "claude" ? Deno.env.get("ANTHROPIC_API_KEY") : Deno.env.get("GEMINI_API_KEY");
  if (!key) return reply(500, { text: "", provider: req.provider, error: "no key on server" });

  const started = Date.now();
  let out: Out;
  try {
    out = req.provider === "claude" ? await callClaude(req, key) : await callGemini(req, key);
  } catch (e) {
    out = { text: "", error: `internal: ${e instanceof Error ? e.message : String(e)}` };
  }
  const ms = Date.now() - started;
  const ok = out.text !== "";
  // do not make the user wait for the log
  const logging = logCall(code, req, ok, ms, rawText.length, out.error);
  // deno-lint-ignore no-explicit-any
  const rt = (globalThis as any).EdgeRuntime;
  if (rt && typeof rt.waitUntil === "function") rt.waitUntil(logging);
  else await logging;

  const body: Record<string, unknown> = { text: out.text, provider: req.provider };
  if (out.error) body.error = out.error;
  return reply(200, body);
});
