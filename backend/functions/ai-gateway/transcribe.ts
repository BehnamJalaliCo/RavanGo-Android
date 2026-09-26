// POST /v1/audio/transcriptions — forwards Whisper-compatible multipart uploads to WHISPER_API_URL.
import { config, CREDIT_COST } from "./config.ts";
import { type Caller, consumeCredits, refundCredits } from "./credits.ts";
import { apiError, errors } from "./http.ts";
import { log } from "./messages.ts";

const PASS_FIELDS = ["language", "response_format", "timestamp_granularities[]", "prompt"];

export async function handleTranscription(req: Request, caller: Caller): Promise<Response> {
  if (!config.whisperApiUrl || !config.whisperApiKey) return errors.notConfigured("Speech-to-text (WHISPER_API_URL / WHISPER_API_KEY)");

  const length = Number(req.headers.get("content-length") ?? "0");
  if (length > config.maxAudioBytes + 64 * 1024) return apiError(413, "invalid_request_error", "Audio chunk too large");

  let form: FormData;
  try {
    form = await req.formData();
  } catch {
    return errors.badRequest("Expected multipart/form-data");
  }
  const file = form.get("file");
  if (!(file instanceof File)) return errors.badRequest("file is required");
  if (file.size > config.maxAudioBytes) return apiError(413, "invalid_request_error", "Audio chunk too large");

  const seconds = await durationSeconds(file);
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  const cost = CREDIT_COST.transcribe_per_minute * minutes;
  const requestId = crypto.randomUUID();
  const consumed = await consumeCredits(caller, "transcribe_per_minute", cost, requestId);
  if (consumed.status === "insufficient") return errors.noCredits(consumed.remaining);
  if (consumed.status === "rate_limited") return errors.rateLimited();

  const upstreamForm = new FormData();
  upstreamForm.append("file", file, file.name || "audio.wav");
  upstreamForm.append("model", config.whisperModel);
  for (const key of PASS_FIELDS) for (const v of form.getAll(key)) if (typeof v === "string") upstreamForm.append(key, v);

  const started = Date.now();
  try {
    const upstream = await fetch(config.whisperApiUrl, {
      method: "POST",
      headers: { authorization: `Bearer ${config.whisperApiKey}` },
      body: upstreamForm,
    });
    const text = await upstream.text();
    log("transcriptions", caller, { status: upstream.status, ms: Date.now() - started, seconds: Math.round(seconds), cost });
    if (!upstream.ok) {
      await refundCredits(caller, requestId);
      const status = upstream.status === 401 || upstream.status === 403 ? 502 : upstream.status;
      return apiError(status, status === 429 ? "rate_limit_error" : "api_error", "Transcription upstream error");
    }
    return new Response(text, {
      status: 200,
      headers: {
        "content-type": upstream.headers.get("content-type") ?? "application/json",
        "x-ravango-credits-remaining": String(consumed.remaining),
        "x-ravango-credits-charged": String(cost),
      },
    });
  } catch (e) {
    await refundCredits(caller, requestId);
    console.error(`transcriptions: upstream failure (${(e as Error).name})`);
    return apiError(502, "api_error", "Transcription upstream unavailable");
  }
}

/**
 * Duration for metering. WAV (what the app sends: 16 kHz mono PCM) is exact from the header; other formats are
 * estimated conservatively at 1 MB per minute (≈128 kbps).
 */
async function durationSeconds(file: File): Promise<number> {
  const head = new Uint8Array(await file.slice(0, 64).arrayBuffer());
  const ascii = (o: number, n: number) => String.fromCharCode(...head.slice(o, o + n));
  if (head.length >= 44 && ascii(0, 4) === "RIFF" && ascii(8, 4) === "WAVE") {
    const view = new DataView(head.buffer);
    const byteRate = view.getUint32(28, true);
    if (byteRate > 0) return Math.max(0, file.size - 44) / byteRate;
  }
  return file.size / (1024 * 1024) * 60;
}
