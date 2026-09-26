// Response helpers. Error bodies use the Anthropic error envelope so the Anthropic SDKs in the app map them to their
// typed exceptions (401 → UnauthorizedException, 402 billing_error, 429 → RateLimitException, …).

export type ErrorType =
  | "invalid_request_error"
  | "authentication_error"
  | "permission_error"
  | "not_found_error"
  | "rate_limit_error"
  | "billing_error"
  | "api_error"
  | "overloaded_error";

export function json(body: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

export function apiError(status: number, type: ErrorType, message: string, headers: HeadersInit = {}): Response {
  return json({ type: "error", error: { type, message } }, status, headers);
}

export const errors = {
  unauthorized: () => apiError(401, "authentication_error", "Sign in required"),
  noCredits: (remaining: number) =>
    apiError(402, "billing_error", `Not enough AI credits (remaining: ${remaining})`),
  rateLimited: () => apiError(429, "rate_limit_error", "Too many AI requests; slow down", { "retry-after": "30" }),
  notConfigured: (what: string) => apiError(501, "not_found_error", `${what} is not configured on this gateway`),
  badRequest: (message: string) => apiError(400, "invalid_request_error", message),
  notFound: () => apiError(404, "not_found_error", "Not found"),
  internal: () => apiError(500, "api_error", "Internal gateway error"),
};
