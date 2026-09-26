// RavanGo AI gateway (Supabase Edge Function).
//
// The app never holds provider keys. It calls this function with the user's Supabase session token; the gateway
// verifies it, meters per-user monthly credits (backend/sql/ai.sql) and forwards to the providers:
//   POST /v1/messages                  → Anthropic Messages API (streaming SSE passthrough)
//   POST /v1/audio/transcriptions      → Whisper-compatible speech-to-text
//   POST|GET /v1/video/eye-contact...  → optional gaze-redirection provider
//   GET  /v1/capabilities              → which of the above are configured
//   GET  /v1/credits                   → the caller's remaining credits
// Deploy: `supabase functions deploy ai-gateway` (see backend/README-ai.md). Content is never logged.
import { config } from "./config.ts";
import { authenticate, remainingCredits } from "./credits.ts";
import { handleEyeContact } from "./eye_contact.ts";
import { errors, json } from "./http.ts";
import { handleMessages } from "./messages.ts";
import { handleTranscription } from "./transcribe.ts";

/** Path segments after ".../ai-gateway", e.g. ["v1", "messages"]. */
function route(url: URL): string[] {
  const parts = url.pathname.split("/").filter(Boolean);
  const at = parts.lastIndexOf("ai-gateway");
  return at >= 0 ? parts.slice(at + 1) : parts;
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-headers": "authorization, content-type, anthropic-version, anthropic-beta, x-ravango-operation, x-api-key",
        "access-control-allow-methods": "GET, POST, OPTIONS",
      },
    });
  }

  const path = route(new URL(req.url));
  if (path[0] !== "v1") return errors.notFound();

  try {
    const caller = await authenticate(req);
    if (!caller) return errors.unauthorized();

    const [, a, b, ...rest] = path;
    if (a === "messages" && b === undefined && req.method === "POST") return await handleMessages(req, caller);
    if (a === "audio" && b === "transcriptions" && req.method === "POST") return await handleTranscription(req, caller);
    if (a === "video" && b === "eye-contact") return await handleEyeContact(req, caller, rest);
    if (a === "capabilities" && req.method === "GET") {
      return json({
        messages: Boolean(config.anthropicApiKey),
        models: config.allowedModels,
        transcription: Boolean(config.whisperApiUrl && config.whisperApiKey),
        eye_contact: Boolean(config.eyeContactApiUrl),
      });
    }
    if (a === "credits" && req.method === "GET") return json(await remainingCredits(caller));
    return errors.notFound();
  } catch (e) {
    console.error(`gateway: unhandled ${(e as Error).name}: ${(e as Error).message}`);
    return errors.internal();
  }
});
