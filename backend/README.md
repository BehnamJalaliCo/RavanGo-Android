# RavanGo backend (Supabase)

RavanGo is local-first: with no backend configured the app runs fully in guest mode and every cloud feature
says which service is missing. Configuring one Supabase project enables:

| Feature | Supabase piece | App module |
|---|---|---|
| Accounts (email code, email + password, SMS code, Google) | GoTrue `/auth/v1` | `platform:auth` |
| Sync of scripts, folders, presets, settings, projects, drafts, media metadata | PostgREST `/rest/v1` + RLS | `platform:cloud` |
| Video backup (Pro) | Storage bucket `media` (TUS resumable uploads) | `platform:cloud` |
| Remote monetization rules | `app_config` table (public read) | `platform:billing` |
| Server-verified purchases & entitlements | `entitlements`, `purchases` + edge function `verify-purchase` | `platform:billing` |
| Account deletion | edge function `delete-account` or SQL `delete_account()` | `platform:auth` |

The AI gateway (`backend/functions/ai-gateway`, `backend/sql/ai.sql`) is documented separately.

## 1. Create the project

1. Create a project at <https://supabase.com> (pick the region closest to your users).
2. **SQL editor → run `backend/sql/schema.sql`** (idempotent; re-run after edits). It creates:
   - the eight sync tables (`scripts`, `script_folders`, `projects`, `drafts`, `media_assets`,
     `beauty_presets`, `prompter_presets`, `user_settings`) with identical columns
     `id, user_id, updated_at, deleted_at, data jsonb, server_updated_at`, per-user RLS for
     select/insert/update/delete, `(user_id, server_updated_at, id)` indexes, and the trigger that stamps
     `server_updated_at = clock_timestamp()` and rejects stale (older `updated_at`) overwrites;
   - `app_config` (public read) with a `monetization` row;
   - `entitlements` and `purchases` (clients read their own rows; only the service role writes);
   - the private `media` bucket with per-user folder policies (`media/<user_id>/…`);
   - `delete_account()` (security definer).
3. **Authentication → Providers**
   - *Email*: enable. For the 6-digit code flow, edit the "Magic Link" and "Confirm signup" email templates
     to include `{{ .Token }}` (the app verifies codes with `POST /auth/v1/verify` `type=email`).
     Keep "Confirm email" on if you want sign-ups to confirm first — the app handles both.
   - *Phone*: enable and configure an SMS provider (Twilio, MessageBird, Vonage, or a custom
     [Send SMS hook](https://supabase.com/docs/guides/auth/auth-hooks/send-sms-hook) for Iranian gateways
     such as Kavenegar). OTP length must stay **6**.
   - *Google*: enable, set the **Web** OAuth client id (and secret) from Google Cloud Console, and add the
     Android client ids under "Authorized Client IDs". Leave "Skip nonce checks" **disabled** — the app sends a nonce.
   - Rate limits: defaults are fine; the app shows a friendly "too many attempts" message on HTTP 429.
4. **Storage**: the bucket is created by the SQL. Uploads above 6 MB use the resumable (TUS) endpoint; raise
   the project's upload size limit (Storage → Settings) to at least your largest expected recording.

## 2. Configure the app

Values are read at build time (`app/build.gradle.kts`) from environment variables `RAVANGO_<NAME>` →
`secrets.properties` (git-ignored) → `secrets.defaults.properties` (committed, empty):

```properties
# secrets.properties
supabaseUrl=https://<project-ref>.supabase.co
supabaseAnonKey=<anon public key>             # Project settings → API (never the service_role key!)
googleWebClientId=<id>.apps.googleusercontent.com   # the WEB client id used by Supabase's Google provider
aiGatewayUrl=https://<project-ref>.functions.supabase.co/ai-gateway
privacyPolicyUrl=https://ravango.app/privacy
termsUrl=https://ravango.app/terms
supportEmail=support@ravango.app
distribution=play                              # play | bazaar | myket | direct (selects the billing provider)
```

CI uses the same names upper-cased with underscores: `RAVANGO_SUPABASE_URL`, `RAVANGO_SUPABASE_ANON_KEY`,
`RAVANGO_GOOGLE_WEB_CLIENT_ID`, `RAVANGO_AI_GATEWAY_URL`, `RAVANGO_DISTRIBUTION`, …

For Google Sign-In also create **Android** OAuth clients (package `com.ravango.app` and `com.ravango.app.debug`,
with the SHA-1 of each signing key) in the same Google Cloud project.

## 3. Sync protocol (what the app does)

* **Push**: `POST /rest/v1/<table>?on_conflict=id&select=…` with
  `Prefer: resolution=merge-duplicates,return=representation`, body = rows
  `{id, user_id, updated_at, deleted_at, data}`. Deletions are tombstones (`deleted_at`). If the returned row
  has a different `updated_at`, another device won the race and the app merges it like a pulled row.
* **Pull**: `GET /rest/v1/<table>?user_id=eq.<uid>&server_updated_at=gte.<cursor>&order=server_updated_at.asc,id.asc&limit=200&offset=<n>`
  (keyset paging with a same-timestamp offset; cursors are stored per table on the device).
* **Merge policy**: last-writer-wins on `updated_at`; for scripts a concurrently edited losing version is kept
  as a "(conflict copy)" script, so text is never lost silently. Details: `platform/cloud/.../MergePolicy.kt`.
* **Settings**: one `user_settings` row per user (`id = user_id`) containing preferences, prompter defaults,
  camera/audio settings and beauty state. Device-local values (consents, sync switches, AI keys/provider) are
  never uploaded.
* Free plans sync scripts, folders, presets and settings; `projects`, `drafts` and `media_assets` sync with
  `CLOUD_PROJECT_SYNC`; video files upload with `CLOUD_MEDIA_BACKUP` (opt-in, quota from the plan).

## 4. Edge function contracts

Deploy with `supabase functions deploy <name>`; set secrets with `supabase secrets set KEY=value`.
All functions receive the user's JWT in `Authorization: Bearer …` and must verify it
(`supabase.auth.getUser(jwt)`); they use the service role key (`SUPABASE_SERVICE_ROLE_KEY`, available by
default inside functions) for privileged writes. Both functions are optional: the app degrades gracefully when
they return 404.

### `verify-purchase` (optional, recommended)

Request:

```json
{ "store": "play", "packageName": "com.ravango.app", "productId": "ravango_pro",
  "purchaseToken": "…", "orderId": "GPA.1234-…", "type": "subs" }
```

Behaviour:
1. Validate the token with the Google Play Developer API (`purchases.subscriptionsv2.get` for `subs`,
   `purchases.products.get` for `inapp`) using a service account
   (secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`; grant it "View financial data" in Play Console).
2. Upsert `purchases` by `purchase_token` (idempotent). Acknowledge the purchase server-side if needed.
3. Subscriptions / lifetime: upsert `entitlements` (`plan`, `is_trial` from the offer phase, `expires_at`).
4. Credit packs (`ai_credits_200` → 200, `ai_credits_1000` → 1000, from `app_config.monetization`): only if
   `credits_granted = 0` for this token, add credits to `entitlements.ai_pack_balance` and record them.

Response (HTTP 200):

```json
{ "valid": true, "reason": null, "credits_added": 200,
  "entitlement": { "plan": "PRO", "is_trial": true, "expires_at": "2026-10-03T10:00:00Z",
                   "ai_pack_balance": 200, "ai_credits_used": 12, "period_start": "2026-09-01T00:00:00Z" } }
```

`valid: false` (with `reason`) makes the app ignore the purchase. On 404 / network errors the app trusts the
acknowledged Play purchase locally (see `docs/MONETIZATION.md` → trust model). Keep subscriptions current with
Play **Real-time developer notifications** (Pub/Sub push → a function that updates `entitlements`).

### `delete-account` (optional)

Request: `POST /functions/v1/delete-account` with `{ "confirm": true }`. Behaviour:
1. List and remove every object under `media/<uid>/` with the service role Storage API.
2. `rpc('delete_account')` is user-scoped, so instead delete the user's rows with the service role (same
   statements as the SQL function) and call `auth.admin.deleteUser(uid)`.
3. Return `200 {}`.

Reference implementation (Deno):

```ts
import { createClient } from "npm:@supabase/supabase-js@2";

Deno.serve(async (req) => {
  const jwt = req.headers.get("Authorization")?.replace("Bearer ", "") ?? "";
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: { user }, error } = await admin.auth.getUser(jwt);
  if (error || !user) return new Response(JSON.stringify({ error: "unauthorized" }), { status: 401 });

  // 1. Backed-up media.
  for (;;) {
    const { data: files } = await admin.storage.from("media").list(user.id, { limit: 1000 });
    if (!files?.length) break;
    await admin.storage.from("media").remove(files.map((f) => `${user.id}/${f.name}`));
  }
  // 2. Rows (sync tables cascade from auth.users; purchases are anonymised).
  await admin.from("purchases").update({ user_id: null, raw: null }).eq("user_id", user.id);
  // 3. The auth user (cascades to every table referencing auth.users).
  await admin.auth.admin.deleteUser(user.id);
  return new Response("{}", { headers: { "Content-Type": "application/json" } });
});
```

Without the function the app deletes the media itself (Storage API, as the user) and then calls
`POST /rest/v1/rpc/delete_account`.

## 5. Changing monetization without an app update

Edit `app_config` row `monetization`; its JSON is deep-merged onto the built-in defaults, e.g.

```sql
update public.app_config
set value = '{"version": 2, "free": {"aiCreditsPerMonth": 30}, "lifetime": {"aiCreditsPerMonth": 300}}',
    updated_at = now()
where key = 'monetization';
```

Invalid values (negative limits, empty product ids, wrong types) are rejected by the app, which then keeps the
last good configuration. Schema: `platform/billing/.../config/MonetizationConfig.kt`.

## 6. Security checklist

* Only the **anon** key ships in the app. The service role key lives only in edge function secrets.
* RLS is enabled on every table; clients can never write `entitlements`/`purchases`/`app_config`.
* `user_id` defaults to `auth.uid()` and is immutable (trigger); `user_settings.id` must equal the owner.
* Storage objects are private and scoped to the owner's folder.
