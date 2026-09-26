// POST /v1/messages — Anthropic-compatible proxy with model allow-list, max_tokens cap and credit metering.
import Anthropic from "@anthropic-ai/sdk";
import { config, CREDIT_COST } from "./config.ts";
import { type Caller, consumeCredits, refundCredits } from "./credits.ts";
import { apiError, errors, json } from "./http.ts";

let client: Anthropic | null = null;

function anthropic(): Anthropic {
  client ??= new Anthropic({ apiKey: config.anthropicApiKey, maxRetries: 2 });
  return client;
}

/** Fields the app may send; everything else is dropped before forwarding. */
const ALLOWED_FIELDS = [
  "model",
  "max_tokens",
  "system",
  "messages",
  "output_config",
  "thinking",
  "stop_sequences",
  "stream",
] as const;

/** Operations the client may declare; the gateway charges max(declared, size-based). */
const DECLARABLE = new Set(["text_small", "text_large", "video_analysis"]);

function sizeOf(value: unknown): number {
  if (typeof value === "string") return value.length;
  if (Array.isArray(value)) return value.reduce((n: number, v) => n + sizeOf(v), 0);
  if (value && typeof value === "object") return Object.values(value).reduce((n: number, v) => n + sizeOf(v), 0);
  return 0;
}

export async function handleMessages(req: Request, caller: Caller): Promise<Response> {
  if (!config.anthropicApiKey) return errors.notConfigured("Claude (ANTHROPIC_API_KEY)");

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errors.badRequest("Body must be JSON");
  }

  const params: Record<string, unknown> = {};
  for (const key of ALLOWED_FIELDS) if (key in body) params[key] = body[key];

  const model = String(params.model ?? "");
  if (!config.allowedModels.includes(model)) {
    return errors.badRequest(`Model not allowed. Allowed: ${config.allowedModels.join(", ")}`);
  }
  const requested = Number(params.max_tokens);
  if (!Number.isFinite(requested) || requested < 1) return errors.badRequest("max_tokens is required");
  params.max_tokens = Math.min(Math.floor(requested), config.maxTokensCap);
  if (!Array.isArray(params.messages) || params.messages.length === 0) return errors.badRequest("messages is required");
  const inputChars = sizeOf(params.system) + sizeOf(params.messages);
  if (inputChars > config.maxInputChars) return errors.badRequest("Input too long");
  const thinking = params.thinking as { type?: string; budget_tokens?: unknown } | undefined;
  if (thinking && thinking.type !== "adaptive" && thinking.type !== "disabled") {
    return errors.badRequest("Only adaptive thinking is supported");
  }
  // Tie the request to the user for Anthropic's abuse monitoring without exposing identity.
  params.metadata = { user_id: await hashUser(caller.userId) };

  const sizeOp = (params.max_tokens as number) > config.smallMaxTokens || inputChars > 24_000 ? "text_large" : "text_small";
  const declared = (req.headers.get("x-ravango-operation") ?? "").toLowerCase();
  const operation = DECLARABLE.has(declared) && CREDIT_COST[declared] > CREDIT_COST[sizeOp] ? declared : sizeOp;
  const cost = CREDIT_COST[operation];
  const requestId = crypto.randomUUID();

  const consumed = await consumeCredits(caller, operation, cost, requestId);
  if (consumed.status === "insufficient") return errors.noCredits(consumed.remaining);
  if (consumed.status === "rate_limited") return errors.rateLimited();

  const useFallbacks = config.fallbacks === "default" && (model.startsWith("claude-opus-5") || model.startsWith("claude-fable"));
  const stream = params.stream === true;
  const started = Date.now();
  try {
    // deno-lint-ignore no-explicit-any
    const request: any = {
      ...params,
      ...(useFallbacks ? { fallbacks: "default", betas: ["server-side-fallback-2026-07-01"] } : {}),
    };
    const upstream = await anthropic().beta.messages.create(request).asResponse();
    const headers = {
      "content-type": upstream.headers.get("content-type") ?? (stream ? "text/event-stream" : "application/json"),
      "cache-control": "no-cache",
      "x-ravango-credits-remaining": String(consumed.remaining),
      "x-ravango-credits-charged": String(cost),
    };
    if (!upstream.body) {
      await refundCredits(caller, requestId);
      return errors.internal();
    }
    const body = stream
      ? upstream.body.pipeThrough(refundOnEmptyFailure(() => refundCredits(caller, requestId)))
      : await refundIfRefusedJson(upstream, () => refundCredits(caller, requestId));
    log("messages", caller, { model, status: upstream.status, ms: Date.now() - started, cost, stream });
    return new Response(body, { status: upstream.status, headers });
  } catch (e) {
    await refundCredits(caller, requestId);
    if (e instanceof Anthropic.APIError && typeof e.status === "number") {
      log("messages", caller, { model, status: e.status, ms: Date.now() - started, refunded: true });
      const upstreamType = (e.type ?? "api_error") as Parameters<typeof apiError>[1];
      // An Anthropic 401/403 means the gateway's key is wrong — not the user's session.
      const status = e.status === 401 || e.status === 403 ? 502 : e.status;
      const retry = e.headers?.get("retry-after");
      return apiError(status, status === 502 ? "api_error" : upstreamType, "Upstream error", retry ? { "retry-after": retry } : {});
    }
    console.error(`messages: upstream failure (${(e as Error).name})`);
    return apiError(502, "api_error", "Upstream unavailable");
  }
}

/**
 * Passes SSE bytes through untouched while watching for a refusal or error event that arrives before any text; in
 * that case the user's credits are refunded (the app discards such output).
 */
function refundOnEmptyFailure(refund: () => Promise<void>): TransformStream<Uint8Array, Uint8Array> {
  const decoder = new TextDecoder();
  let sawText = false;
  let failed = false;
  let tail = "";
  return new TransformStream({
    transform(chunk, controller) {
      controller.enqueue(chunk);
      const text = tail + decoder.decode(chunk, { stream: true });
      if (!sawText && text.includes('"text_delta"')) sawText = true;
      if (text.includes('"stop_reason":"refusal"') || text.includes("event: error")) failed = true;
      tail = text.slice(-64);
    },
    async flush() {
      if (failed && !sawText) await refund();
    },
  });
}

async function refundIfRefusedJson(upstream: Response, refund: () => Promise<void>): Promise<string> {
  const text = await upstream.text();
  try {
    const parsed = JSON.parse(text);
    if (upstream.status >= 400 || parsed?.stop_reason === "refusal") await refund();
  } catch {
    await refund();
  }
  return text;
}

async function hashUser(userId: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`ravango:${userId}`));
  return Array.from(new Uint8Array(digest)).slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Metadata-only structured log (no prompts, no outputs, no raw user ids). */
export function log(route: string, caller: Caller, fields: Record<string, unknown>): void {
  console.log(JSON.stringify({ route, user: caller.userId.slice(0, 8), ...fields }));
}

export { json };
