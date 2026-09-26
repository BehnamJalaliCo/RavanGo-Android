# RavanGo AI gateway

A Supabase Edge Function (`backend/functions/ai-gateway`, TypeScript/Deno) that sits between the app and the AI
providers. **Provider keys never ship in the APK**: the app authenticates with the user's Supabase session token, and
the gateway verifies it, enforces per-user monthly credits and rate limits, and forwards the request.

```
app (engine:ai) ──Bearer <user JWT>──► ai-gateway ──► api.anthropic.com/v1/messages   (official @anthropic-ai/sdk)
                                              ├─────► WHISPER_API_URL                  (Whisper-compatible STT)
                                              └─────► EYE_CONTACT_API_URL              (optional gaze model)
```

## Endpoints

All paths are relative to the function URL, e.g. `https://<project>.supabase.co/functions/v1/ai-gateway`.
This base URL is what the app build gets as `RAVANGO_AI_GATEWAY_URL` (`AppConfig.aiGatewayUrl`).

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/messages` | Anthropic Messages API (JSON or SSE streaming passthrough). The app's Anthropic Java SDK targets the gateway with `baseUrl`. |
| POST | `/v1/audio/transcriptions` | Whisper-compatible multipart (`file`, `response_format=verbose_json`, `timestamp_granularities[]=word`). |
| POST | `/v1/video/eye-contact` | Create an eye-contact job → `{job_id, upload_url, upload_headers}`. |
| POST | `/v1/video/eye-contact/{id}/start` | Start processing after the upload. |
| GET | `/v1/video/eye-contact/{id}` | `{status, progress, result_url, error}`. |
| GET | `/v1/capabilities` | `{messages, models, transcription, eye_contact}` — the app enables features from this. |
| GET | `/v1/credits` | `{remaining, monthly_allowance}` for the caller. |

Errors use Anthropic's envelope (`{"type":"error","error":{"type","message"}}`) so the SDKs map them:
401 `authentication_error` (sign in again), 402 `billing_error` (out of credits), 429 `rate_limit_error`
(with `retry-after`), 400 `invalid_request_error`, 501 feature not configured, 502 upstream problem.

### `/v1/messages` rules

- **Model allow-list**: `claude-opus-5` (app default), `claude-sonnet-5`, `claude-haiku-4-5` (`AI_ALLOWED_MODELS`).
- **Field allow-list**: `model, max_tokens, system, messages, output_config, thinking, stop_sequences, stream`;
  anything else is dropped. `thinking` may only be `adaptive`/`disabled` (`budget_tokens` is rejected by the model).
  `metadata.user_id` is set to a salted hash of the user id.
- **`max_tokens`** is clamped to `AI_MAX_TOKENS_CAP` (16000).
- **Refusal fallbacks**: for `claude-opus-5` the gateway adds `fallbacks: "default"` with beta
  `server-side-fallback-2026-07-01`, so a request declined by safety classifiers is retried server-side on
  Anthropic's recommended model. Set `AI_FALLBACKS=off` to disable.
- **Metering**: `text_large` (3 credits) when `max_tokens > AI_SMALL_MAX_TOKENS` (8000) or the input exceeds 24k
  characters, otherwise `text_small` (1). The app may declare a *higher* operation via `X-RavanGo-Operation`
  (`video_analysis` = 5 for highlight/shorts passes); lower declarations are ignored.
- **Refunds**: upstream errors, empty responses and refusals that arrive before any text are refunded automatically.

### Transcription metering

`transcribe_per_minute` = 2 credits per started minute. Duration is read exactly from the WAV header (the app uploads
16 kHz mono PCM chunks ≤ 10 min); other formats are estimated at 1 MB/min. Chunks above `AI_MAX_AUDIO_BYTES`
(25 MB) are rejected with 413.

### Eye-contact provider contract

Gaze redirection needs a server-side model (for example an NVIDIA Maxine Eye Contact deployment). Put a small
service in front of it that implements:

```
POST {EYE_CONTACT_API_URL}/jobs            {content_type, size_bytes, duration_ms} → {job_id, upload_url, upload_headers?}
POST {EYE_CONTACT_API_URL}/jobs/{id}/start → 2xx
GET  {EYE_CONTACT_API_URL}/jobs/{id}       → {status: queued|running|succeeded|failed, progress?, result_url?, error?}
```

`upload_url` / `result_url` should be short-lived pre-signed URLs (the app uploads and downloads directly, so large
videos never pass through the edge function). Cost: `video_analysis` (5) per started minute, refunded if the job fails.
When `EYE_CONTACT_API_URL` is unset, `/v1/capabilities` reports `eye_contact: false` and the app shows
"requires service" instead of the feature.

## Database

Run `backend/sql/ai.sql` once (SQL editor or `supabase db push` as a migration). It creates:

- `ai_credit_accounts(user_id, monthly_allowance)` — set by billing when a plan changes via
  `select ai_set_monthly_allowance('<user>', 500);` (service role only). Users without a row get
  `AI_FREE_MONTHLY_CREDITS`.
- `ai_credit_ledger` — append-only; remaining = allowance + sum(delta) in the current UTC month.
- `ai_video_jobs` — eye-contact job ownership (users can only poll their own jobs).
- `ai_consume_credits(...)` — atomic (row lock per user): rate limit (`AI_RATE_LIMIT_PER_MINUTE`) + balance check +
  insert. `ai_refund_credits(user, request_id)` refunds exactly once.

RLS lets users read only their own rows; all writes go through the functions with the service role.

The app keeps its own client-side mirror through `EntitlementProvider.tryConsumeAiCredits/refundAiCredits` for instant
UI feedback; the gateway ledger is authoritative.

## Environment variables

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | auto | — | Injected by Supabase; used to verify JWTs and write the ledger. |
| `ANTHROPIC_API_KEY` | yes | — | Claude key (keep it only in function secrets). |
| `AI_ALLOWED_MODELS` | no | `claude-opus-5,claude-sonnet-5,claude-haiku-4-5` | Comma-separated allow-list. |
| `AI_MAX_TOKENS_CAP` | no | `16000` | Upper bound for `max_tokens`. |
| `AI_SMALL_MAX_TOKENS` | no | `8000` | Threshold between `text_small` and `text_large` (matches the app). |
| `AI_MAX_INPUT_CHARS` | no | `200000` | Reject larger prompts. |
| `AI_FALLBACKS` | no | `default` | `default` or `off` (server-side refusal fallbacks). |
| `AI_FREE_MONTHLY_CREDITS` | no | `20` | Allowance for users without an account row. |
| `AI_RATE_LIMIT_PER_MINUTE` | no | `20` | Metered requests per user per minute. |
| `WHISPER_API_URL` | no | OpenAI transcriptions URL | Any Whisper-compatible endpoint (OpenAI, Groq, self-hosted faster-whisper). |
| `WHISPER_API_KEY` | for STT | — | Key for `WHISPER_API_URL`. Transcription is disabled without it. |
| `WHISPER_MODEL` | no | `whisper-1` | Model name sent upstream. |
| `AI_MAX_AUDIO_BYTES` | no | `26214400` | Max upload per chunk. |
| `EYE_CONTACT_API_URL`, `EYE_CONTACT_API_KEY` | no | — | Enables `/v1/video/eye-contact`. |

## Deploy

```bash
supabase secrets set ANTHROPIC_API_KEY=sk-ant-... WHISPER_API_KEY=sk-...
supabase functions deploy ai-gateway          # JWT verification stays on (the app sends the user's token)
# then build the app with RAVANGO_AI_GATEWAY_URL=https://<project>.supabase.co/functions/v1/ai-gateway
```

Notes:
- Edge Functions have a wall-clock limit per request; long streamed scripts finish well within it, but very long
  generations on low tiers may be cut — the app then shows a network error and credits for failures before the
  first token are refunded.
- Logs contain only route, status, duration, cost and a truncated user id — never prompts, transcripts or outputs.

## App-side providers (engine:ai)

| Provider | Text | Speech | Credits |
|---|---|---|---|
| `RAVANGO_GATEWAY` (default) | Claude via this gateway | `/v1/audio/transcriptions` | RavanGo credits |
| `ANTHROPIC_DIRECT` (BYOK) | Claude with the user's key | — | none |
| `OPENAI_COMPATIBLE` (BYOK/self-hosted) | `/v1/chat/completions` SSE | — | none |
| `OPENAI_WHISPER` (BYOK) | — | OpenAI Whisper with the user's key | none |
| `ANDROID_ON_DEVICE` | — | Platform on-device recognizer (Android 13+) | none, nothing uploaded |

Nothing is sent to any provider until the user grants `cloudProcessingConsent` (the app shows a consent dialog that
names the destination).
