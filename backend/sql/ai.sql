-- RavanGo AI gateway: per-user monthly AI credits, rate limiting and video job ownership.
-- Self-contained; safe to run more than once. Apply after the main schema (it only references auth.users).
--
-- Model:
--   ai_credit_accounts  one row per user: monthly allowance (set by billing when the plan changes).
--   ai_credit_ledger    append-only: negative delta = consumption, positive delta = refund / top-up.
--   remaining(month)    = monthly_allowance + sum(delta) over the current UTC calendar month.
-- All writes go through SECURITY DEFINER functions called by the gateway with the service role.

create table if not exists public.ai_credit_accounts (
    user_id           uuid primary key references auth.users (id) on delete cascade,
    monthly_allowance integer not null default 20 check (monthly_allowance >= 0),
    updated_at        timestamptz not null default now()
);

create table if not exists public.ai_credit_ledger (
    id          bigint generated always as identity primary key,
    user_id     uuid not null references auth.users (id) on delete cascade,
    delta       integer not null,
    operation   text not null,          -- text_small | text_large | transcribe_per_minute | video_analysis | refund | topup
    request_id  uuid not null,           -- one consumption (and at most one refund) per request
    kind        text not null check (kind in ('consume', 'refund', 'topup')),
    created_at  timestamptz not null default now(),
    unique (request_id, kind)
);

create index if not exists ai_credit_ledger_user_time on public.ai_credit_ledger (user_id, created_at desc);

create table if not exists public.ai_video_jobs (
    job_id      text primary key,        -- provider job id
    user_id     uuid not null references auth.users (id) on delete cascade,
    request_id  uuid not null,           -- ledger request that paid for the job
    status      text not null default 'created',
    refunded    boolean not null default false,
    created_at  timestamptz not null default now()
);

alter table public.ai_credit_accounts enable row level security;
alter table public.ai_credit_ledger   enable row level security;
alter table public.ai_video_jobs      enable row level security;

-- Users may read their own balance and history; only the gateway (service role) writes.
drop policy if exists ai_accounts_read_own on public.ai_credit_accounts;
create policy ai_accounts_read_own on public.ai_credit_accounts for select using (auth.uid() = user_id);
drop policy if exists ai_ledger_read_own on public.ai_credit_ledger;
create policy ai_ledger_read_own on public.ai_credit_ledger for select using (auth.uid() = user_id);
drop policy if exists ai_jobs_read_own on public.ai_video_jobs;
create policy ai_jobs_read_own on public.ai_video_jobs for select using (auth.uid() = user_id);

-- Remaining credits for the current UTC month.
create or replace function public.ai_credits_remaining(p_user uuid, p_default_allowance integer default 20)
returns table (remaining integer, monthly_allowance integer)
language sql
stable
security definer
set search_path = public
as $$
    select
        coalesce(a.monthly_allowance, p_default_allowance)
            + coalesce((select sum(l.delta)::integer from public.ai_credit_ledger l
                        where l.user_id = p_user
                          and l.created_at >= date_trunc('month', now() at time zone 'utc') at time zone 'utc'), 0),
        coalesce(a.monthly_allowance, p_default_allowance)
    from (select 1) one
    left join public.ai_credit_accounts a on a.user_id = p_user;
$$;

-- Atomically checks the rate limit and balance, then records the consumption.
-- status: 'ok' | 'insufficient' | 'rate_limited' | 'duplicate'
create or replace function public.ai_consume_credits(
    p_user uuid,
    p_operation text,
    p_amount integer,
    p_request_id uuid,
    p_default_allowance integer default 20,
    p_rate_per_minute integer default 20
)
returns table (status text, remaining integer)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_remaining integer;
    v_recent integer;
begin
    if p_amount <= 0 then
        raise exception 'amount must be positive';
    end if;

    -- Serialize per user: create the account row on first use and lock it.
    insert into public.ai_credit_accounts (user_id, monthly_allowance)
    values (p_user, p_default_allowance)
    on conflict (user_id) do nothing;
    perform 1 from public.ai_credit_accounts where user_id = p_user for update;

    if exists (select 1 from public.ai_credit_ledger where request_id = p_request_id and kind = 'consume') then
        select r.remaining into v_remaining from public.ai_credits_remaining(p_user, p_default_allowance) r;
        return query select 'duplicate'::text, v_remaining;
        return;
    end if;

    select count(*) into v_recent from public.ai_credit_ledger
    where user_id = p_user and kind = 'consume' and created_at > now() - interval '1 minute';
    if v_recent >= p_rate_per_minute then
        select r.remaining into v_remaining from public.ai_credits_remaining(p_user, p_default_allowance) r;
        return query select 'rate_limited'::text, v_remaining;
        return;
    end if;

    select r.remaining into v_remaining from public.ai_credits_remaining(p_user, p_default_allowance) r;
    if v_remaining < p_amount then
        return query select 'insufficient'::text, v_remaining;
        return;
    end if;

    insert into public.ai_credit_ledger (user_id, delta, operation, request_id, kind)
    values (p_user, -p_amount, p_operation, p_request_id, 'consume');
    return query select 'ok'::text, v_remaining - p_amount;
end;
$$;

-- Refunds a consumption exactly once (no-op if already refunded or never consumed).
create or replace function public.ai_refund_credits(p_user uuid, p_request_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_amount integer;
begin
    select -delta into v_amount from public.ai_credit_ledger
    where request_id = p_request_id and user_id = p_user and kind = 'consume';
    if v_amount is null then
        return false;
    end if;
    insert into public.ai_credit_ledger (user_id, delta, operation, request_id, kind)
    values (p_user, v_amount, 'refund', p_request_id, 'refund')
    on conflict (request_id, kind) do nothing;
    return found;
end;
$$;

-- Called by billing (service role) when a user's plan changes.
create or replace function public.ai_set_monthly_allowance(p_user uuid, p_allowance integer)
returns void
language sql
security definer
set search_path = public
as $$
    insert into public.ai_credit_accounts (user_id, monthly_allowance, updated_at)
    values (p_user, p_allowance, now())
    on conflict (user_id) do update set monthly_allowance = excluded.monthly_allowance, updated_at = now();
$$;

revoke all on function public.ai_consume_credits(uuid, text, integer, uuid, integer, integer) from public, anon, authenticated;
revoke all on function public.ai_refund_credits(uuid, uuid) from public, anon, authenticated;
revoke all on function public.ai_set_monthly_allowance(uuid, integer) from public, anon, authenticated;
-- Balances are served to users through the gateway's GET /v1/credits (it checks the caller's JWT).
revoke all on function public.ai_credits_remaining(uuid, integer) from public, anon, authenticated;
