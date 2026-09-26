// /v1/video/eye-contact — generic job proxy to a gaze-redirection provider (EYE_CONTACT_API_URL).
// Provider contract (implemented by the service that wraps e.g. NVIDIA Maxine Eye Contact):
//   POST {url}/jobs                 {content_type,size_bytes,duration_ms} -> {job_id, upload_url, upload_headers?}
//   POST {url}/jobs/{id}/start      -> 2xx
//   GET  {url}/jobs/{id}            -> {status: queued|running|succeeded|failed, progress?, result_url?, error?}
import { config, CREDIT_COST } from "./config.ts";
import { type Caller, consumeCredits, db, refundCredits } from "./credits.ts";
import { apiError, errors, json } from "./http.ts";
import { log } from "./messages.ts";

function provider(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${config.eyeContactApiUrl.replace(/\/+$/, "")}${path}`, {
    ...init,
    headers: { authorization: `Bearer ${config.eyeContactApiKey}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

async function ownedJob(caller: Caller, jobId: string) {
  const { data } = await db().from("ai_video_jobs").select("job_id,request_id,refunded,status").eq("job_id", jobId).eq("user_id", caller.userId).maybeSingle();
  return data as { job_id: string; request_id: string; refunded: boolean; status: string } | null;
}

export async function handleEyeContact(req: Request, caller: Caller, rest: string[]): Promise<Response> {
  if (!config.eyeContactApiUrl) return errors.notConfigured("Eye contact (EYE_CONTACT_API_URL)");

  // POST /v1/video/eye-contact
  if (rest.length === 0 && req.method === "POST") {
    const body = await req.json().catch(() => null) as { content_type?: string; size_bytes?: number; duration_ms?: number } | null;
    if (!body || !body.duration_ms || body.duration_ms <= 0) return errors.badRequest("duration_ms is required");
    const minutes = Math.max(1, Math.ceil(body.duration_ms / 60000));
    const cost = CREDIT_COST.video_analysis * minutes;
    const requestId = crypto.randomUUID();
    const consumed = await consumeCredits(caller, "video_analysis", cost, requestId);
    if (consumed.status === "insufficient") return errors.noCredits(consumed.remaining);
    if (consumed.status === "rate_limited") return errors.rateLimited();
    try {
      const upstream = await provider("/jobs", {
        method: "POST",
        body: JSON.stringify({ content_type: body.content_type, size_bytes: body.size_bytes, duration_ms: body.duration_ms }),
      });
      if (!upstream.ok) throw new Error(`provider ${upstream.status}`);
      const job = await upstream.json() as { job_id: string; upload_url: string; upload_headers?: Record<string, string> };
      const { error } = await db().from("ai_video_jobs").insert({ job_id: job.job_id, user_id: caller.userId, request_id: requestId });
      if (error) throw new Error("job insert failed");
      log("eye-contact.create", caller, { minutes, cost });
      return json({ job_id: job.job_id, upload_url: job.upload_url, upload_headers: job.upload_headers ?? {} });
    } catch (e) {
      await refundCredits(caller, requestId);
      console.error(`eye-contact: create failed (${(e as Error).message})`);
      return apiError(502, "api_error", "Eye-contact provider unavailable");
    }
  }

  const jobId = rest[0];
  const job = jobId ? await ownedJob(caller, jobId) : null;
  if (!job) return errors.notFound();

  // POST /v1/video/eye-contact/{id}/start
  if (rest.length === 2 && rest[1] === "start" && req.method === "POST") {
    const upstream = await provider(`/jobs/${encodeURIComponent(jobId)}/start`, { method: "POST", body: "{}" });
    if (!upstream.ok) return apiError(502, "api_error", "Eye-contact provider error");
    await db().from("ai_video_jobs").update({ status: "running" }).eq("job_id", jobId);
    return json({ ok: true });
  }

  // GET /v1/video/eye-contact/{id}
  if (rest.length === 1 && req.method === "GET") {
    const upstream = await provider(`/jobs/${encodeURIComponent(jobId)}`, { method: "GET" });
    if (!upstream.ok) return apiError(502, "api_error", "Eye-contact provider error");
    const status = await upstream.json() as { status: string; progress?: number; result_url?: string; error?: string };
    if (status.status === "failed" && !job.refunded) {
      await refundCredits(caller, job.request_id);
      await db().from("ai_video_jobs").update({ refunded: true, status: "failed" }).eq("job_id", jobId);
    } else if (status.status !== job.status) {
      await db().from("ai_video_jobs").update({ status: status.status }).eq("job_id", jobId);
    }
    return json({ status: status.status, progress: status.progress ?? null, result_url: status.result_url ?? null, error: status.error ?? null });
  }

  return errors.notFound();
}
