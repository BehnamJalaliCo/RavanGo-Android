# RavanGo monetization

**Principles**: no ads, ever (they would break the calm, focused experience and conflict with privacy); a Free
plan that is genuinely useful for daily creators; Pro for people who publish professionally; honest paywall
copy with the price, renewal and trial terms next to the button. All rules are data
(`platform/billing/.../config/MonetizationConfig.kt`) and can be changed remotely without an app update.
Features never hard-code plan logic — they ask `EntitlementProvider.has(ProFeature.X)` / read `Entitlements`.

## Plans

| | Free | Pro (monthly / yearly) | Lifetime (one-time) |
|---|---|---|---|
| Teleprompter | Full (except floating) | + Floating prompter over other apps | same as Pro |
| Recording | 1080p · 30 fps · H.264 | 4K · 60 fps · HEVC, manual pro camera | same as Pro |
| Beauty | Smooth, brighten, sharpen, whitening, tone | + Advanced beauty, face reshape, makeup | same as Pro |
| Editor | Trim, split, merge, crop, rotate, speed, filters, text, music, basic adjust | + Multi-layer, PiP, advanced color, reverse, noise reduction | same as Pro |
| Export | ≤ 1080p30 with a small tasteful watermark | 4K / 60 fps, no watermark | same as Pro |
| AI | 20 credits / month | Auto captions, AI video tools, **1000 credits / month** | Auto captions, AI video tools, **200 credits / month** |
| Cloud | Sync of scripts, folders, presets & settings | + Projects & drafts sync, **50 GB** video backup | + Projects & drafts sync, **20 GB** video backup |
| Custom presets | 3 | Unlimited | Unlimited |

* The yearly plan carries a **7-day free trial** (Play offer `yearly-free-trial`, shown only to eligible users —
  Play filters offers by eligibility, so the trial badge disappears automatically after use).
* Lifetime gets every Pro feature but a smaller monthly AI allowance and backup quota, because AI inference and
  storage have ongoing costs that a one-time price cannot fund indefinitely.
* **AI credit packs** (consumable, any plan): 200 and 1000 credits. Packs never expire and are spent after the
  monthly allowance. 1 credit ≈ one short text generation (`AiOperation`: small text 1, large text 3,
  transcription 2/minute, video analysis 5).

## Store products (Google Play Console)

| Product | Type | Id |
|---|---|---|
| RavanGo Pro | Subscription with base plans `monthly` (P1M) and `yearly` (P1Y); offer `yearly-free-trial` (P7D free) on `yearly` | `ravango_pro` |
| RavanGo Lifetime | One-time (non-consumable) | `ravango_lifetime` |
| 200 AI credits | One-time (consumable) | `ai_credits_200` |
| 1000 AI credits | One-time (consumable) | `ai_credits_1000` |

Suggested price ladder (set per country in Play): Monthly ≈ $4.99, Yearly ≈ $29.99 (≈ 50% saving, shown
automatically from live prices), Lifetime ≈ $79.99, packs ≈ $1.99 / $6.99.

## Entitlement computation

```
ownership = max(store purchases, server entitlement, cached ownership ≤ 7 days old when both are unreachable)
            # Pro subscription outranks Lifetime (bigger AI allowance); expired grants are ignored
limits    = config.limitsFor(ownership.plan)
credits   = max(0, limits.aiCreditsPerMonth − usedThisMonth) + packBalance
usedThisMonth = max(local ledger since the 1st of the month, server metering from the AI gateway)
```

`tryConsumeAiCredits` runs under a mutex: it reserves from the monthly allowance first (ledger row in
`ai_usage`), then from the pack balance; `refundAiCredits` reverses the exact split of the matching reservation.

## Remote override

`app_config.key = 'monetization'` holds a *partial* JSON deep-merged onto the defaults:

```json
{
  "version": 2,
  "free":     { "aiCreditsPerMonth": 30, "features": ["FLOATING_PROMPTER"] },
  "pro":      { "cloudQuotaBytes": 107374182400 },
  "lifetime": { "aiCreditsPerMonth": 300 },
  "products": { "creditPacks": [ { "productId": "ai_credits_500", "credits": 500 } ] },
  "trialDays": 7
}
```

`features` accepts `ProFeature` names or `"*"`; unknown names are ignored (forward compatible);
`maxSavedPresets: -1` means unlimited. Invalid configs are rejected and the last good one (cached in DataStore)
stays active.

## Distribution channels

`AppConfig.distribution` selects the `BillingProvider`:

* `play` → Google Play Billing 8 (`PlayBillingProvider`: pending purchases, auto service reconnection,
  subscription offers with free-trial phases, acknowledge/consume, restore).
* `bazaar` → Cafe Bazaar through Poolakey 2.2.0 (`BazaarBillingProvider`), enabled when `RAVANGO_BAZAAR_RSA_PUBLIC_KEY`
  is set (purchases are signature-checked locally with it). Bazaar has no base plans, so the SKUs
  `ravango_pro_monthly` / `ravango_pro_yearly` are mapped onto the monthly/yearly plans of `ravango_pro`; lifetime
  and AI credit packs use the same product ids as Play. No free-trial phase; no acknowledgement step; packs are
  consumed after crediting. Prices come as formatted strings (Toman/Rial) and are parsed for the yearly-savings badge.
* `myket`, `direct` (or `bazaar` without the RSA key) → `NoopBillingProvider`: the paywall explains that purchases
  aren't supported through that store yet. Server-granted entitlements (e.g. bought on another device) still apply.
* Each release build carries only its own store's billing (manifest overlays `app/src/{playStore,bazaarStore,otherStore}`).

## Trust model

* **Supabase + `verify-purchase` deployed** (recommended): every purchase token is validated server-side with the
  Google Play Developer API; `entitlements` is the source of truth; credit packs are credited exactly once per
  token on the server.
* **Otherwise**: an acknowledged Play purchase is trusted locally and credit packs are credited locally
  (idempotent by purchase token). Risk: a tampered client could unlock Pro features *on that device only*; it
  cannot obtain server resources (AI gateway credits, cloud storage), which the server meters independently.
  Accepted trade-off for builds without a backend.

## Paywall rules

* Headline adapts to the feature that triggered it (`PaywallRoute(feature = ProFeature.X.name)`) and that
  benefit is highlighted and listed first.
* Live localized store prices only; yearly shows savings % vs 12× monthly and the per-month equivalent.
* CTA wording states the trial ("Start 7-day free trial") and the fine print states price, renewal and how to
  cancel. "Continue with Free" is always visible. Restore purchases is always available.
* Pending (e.g. cash/carrier) purchases, cancellations and failures show clear, non-alarming messages
  ("You were not charged").
