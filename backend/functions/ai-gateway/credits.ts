// Auth + per-user credit ledger (SQL in backend/sql/ai.sql). Never logs request content.
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { config } from "./config.ts";

let admin: SupabaseClient | null = null;

export function db(): SupabaseClient {
  admin ??= createClient(config.supabaseUrl, config.serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return admin;
}

export interface Caller {
  userId: string;
}

/** Verifies the Supabase access token from `Authorization: Bearer <jwt>`. */
export async function authenticate(req: Request): Promise<Caller | null> {
  const header = req.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match) return null;
  const { data, error } = await db().auth.getUser(match[1]);
  if (error || !data?.user) return null;
  return { userId: data.user.id };
}

export type ConsumeStatus = "ok" | "insufficient" | "rate_limited" | "duplicate";

export async function consumeCredits(
  caller: Caller,
  operation: string,
  amount: number,
  requestId: string,
): Promise<{ status: ConsumeStatus; remaining: number }> {
  const { data, error } = await db().rpc("ai_consume_credits", {
    p_user: caller.userId,
    p_operation: operation,
    p_amount: amount,
    p_request_id: requestId,
    p_default_allowance: config.freeMonthlyCredits,
    p_rate_per_minute: config.ratePerMinute,
  });
  if (error) throw new Error(`ledger error: ${error.code ?? "unknown"}`);
  const row = Array.isArray(data) ? data[0] : data;
  return { status: row.status as ConsumeStatus, remaining: row.remaining as number };
}

export async function refundCredits(caller: Caller, requestId: string): Promise<void> {
  const { error } = await db().rpc("ai_refund_credits", { p_user: caller.userId, p_request_id: requestId });
  if (error) console.error(`refund failed for request ${requestId}: ${error.code ?? "unknown"}`);
}

export async function remainingCredits(caller: Caller): Promise<{ remaining: number; monthly_allowance: number }> {
  const { data, error } = await db().rpc("ai_credits_remaining", {
    p_user: caller.userId,
    p_default_allowance: config.freeMonthlyCredits,
  });
  if (error) throw new Error(`ledger error: ${error.code ?? "unknown"}`);
  const row = Array.isArray(data) ? data[0] : data;
  return { remaining: row.remaining, monthly_allowance: row.monthly_allowance };
}
