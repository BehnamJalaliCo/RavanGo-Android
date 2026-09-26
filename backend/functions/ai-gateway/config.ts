// Environment configuration for the RavanGo AI gateway. See backend/README-ai.md.

function env(name: string, fallback = ""): string {
  return (Deno.env.get(name) ?? fallback).trim();
}

function intEnv(name: string, fallback: number): number {
  const v = Number.parseInt(env(name), 10);
  return Number.isFinite(v) && v > 0 ? v : fallback;
}

export const config = {
  // Provided automatically by Supabase Edge Functions.
  supabaseUrl: env("SUPABASE_URL"),
  serviceRoleKey: env("SUPABASE_SERVICE_ROLE_KEY"),

  // Claude.
  anthropicApiKey: env("ANTHROPIC_API_KEY"),
  allowedModels: env("AI_ALLOWED_MODELS", "claude-opus-5,claude-sonnet-5,claude-haiku-4-5")
    .split(",").map((m) => m.trim()).filter(Boolean),
  maxTokensCap: intEnv("AI_MAX_TOKENS_CAP", 16000),
  /** Requests whose max_tokens exceed this are metered as TEXT_LARGE. Must match the app's PromptLibrary. */
  smallMaxTokens: intEnv("AI_SMALL_MAX_TOKENS", 8000),
  maxInputChars: intEnv("AI_MAX_INPUT_CHARS", 200000),
  /** "default" enables Anthropic server-side refusal fallbacks; "off" disables them. */
  fallbacks: env("AI_FALLBACKS", "default"),

  // Credits & limits.
  freeMonthlyCredits: intEnv("AI_FREE_MONTHLY_CREDITS", 20),
  ratePerMinute: intEnv("AI_RATE_LIMIT_PER_MINUTE", 20),

  // Speech-to-text (OpenAI-compatible /v1/audio/transcriptions).
  whisperApiUrl: env("WHISPER_API_URL", "https://api.openai.com/v1/audio/transcriptions"),
  whisperApiKey: env("WHISPER_API_KEY"),
  whisperModel: env("WHISPER_MODEL", "whisper-1"),
  maxAudioBytes: intEnv("AI_MAX_AUDIO_BYTES", 25 * 1024 * 1024),

  // Eye contact (optional provider implementing the job protocol in README-ai.md).
  eyeContactApiUrl: env("EYE_CONTACT_API_URL"),
  eyeContactApiKey: env("EYE_CONTACT_API_KEY"),
};

/** Credit cost per unit; mirrors core/model AiOperation. */
export const CREDIT_COST: Record<string, number> = {
  text_small: 1,
  text_large: 3,
  transcribe_per_minute: 2,
  video_analysis: 5,
};
