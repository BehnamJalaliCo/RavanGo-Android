-- =====================================================================================================
-- RavanGo — Supabase schema: sync tables, entitlements/purchases, public app config, media storage,
-- account deletion. Idempotent: safe to run again after edits (SQL editor or `supabase db push`).
--
-- Sync model (see platform/cloud):
--   * Every synced table has the same shape:
--       id text primary key          -- client-generated UUID (user_settings: the user's id)
--       user_id uuid                 -- owner; RLS restricts every operation to auth.uid()
--       updated_at bigint            -- client wall-clock ms of the last edit (last-writer-wins key)
--       deleted_at bigint null       -- tombstone (client ms); rows are never hard-deleted by clients
--       data jsonb                   -- the entity's portable fields (schema-less → no migrations when
--                                       the app model gains a field)
--       server_updated_at timestamptz -- set by trigger with clock_timestamp(); incremental pull cursor
--   * The BEFORE INSERT/UPDATE trigger enforces last-writer-wins on the server too: an update carrying an
--     older updated_at than the stored row is ignored (the stored row is kept and returned), so a device
--     with stale data can never overwrite a newer edit. user_id can never change.
-- =====================================================================================================

create extension if not exists pgcrypto;

-- ----------------------------------------------------------------------------------------- sync tables

create or replace function public.ravango_sync_row_before_write()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'UPDATE' then
    if new.user_id is distinct from old.user_id then
      raise exception 'user_id is immutable' using errcode = '42501';
    end if;
    if new.updated_at < old.updated_at then
      -- Stale write: keep the newer stored version untouched (server_updated_at unchanged).
      return old;
    end if;
  end if;
  new.server_updated_at := clock_timestamp();
  return new;
end;
$$;

do $$
declare
  t text;
begin
  foreach t in array array[
    'scripts', 'script_folders', 'projects', 'drafts', 'media_assets',
    'beauty_presets', 'prompter_presets', 'user_settings'
  ]
  loop
    execute format($f$
      create table if not exists public.%1$I (
        id                text        primary key,
        user_id           uuid        not null default auth.uid() references auth.users (id) on delete cascade,
        updated_at        bigint      not null default 0,
        deleted_at        bigint,
        data              jsonb       not null default '{}'::jsonb,
        server_updated_at timestamptz not null default clock_timestamp(),
        created_at        timestamptz not null default now()
      )$f$, t);

    execute format('create index if not exists %1$I on public.%2$I (user_id, server_updated_at, id)', t || '_pull_idx', t);

    execute format('drop trigger if exists ravango_sync_row on public.%I', t);
    execute format(
      'create trigger ravango_sync_row before insert or update on public.%I
         for each row execute function public.ravango_sync_row_before_write()', t);

    execute format('alter table public.%I enable row level security', t);

    execute format('drop policy if exists "own rows: select" on public.%I', t);
    execute format('create policy "own rows: select" on public.%I for select to authenticated using (auth.uid() = user_id)', t);
    execute format('drop policy if exists "own rows: insert" on public.%I', t);
    execute format('create policy "own rows: insert" on public.%I for insert to authenticated with check (auth.uid() = user_id)', t);
    execute format('drop policy if exists "own rows: update" on public.%I', t);
    execute format('create policy "own rows: update" on public.%I for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id)', t);
    execute format('drop policy if exists "own rows: delete" on public.%I', t);
    execute format('create policy "own rows: delete" on public.%I for delete to authenticated using (auth.uid() = user_id)', t);

    execute format('revoke all on public.%I from anon', t);
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);
  end loop;
end;
$$;

-- The settings row must be keyed by its owner.
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'user_settings_id_is_owner') then
    alter table public.user_settings add constraint user_settings_id_is_owner check (id = user_id::text);
  end if;
end;
$$;

-- --------------------------------------------------------------------------------- public app config

-- Remotely tunable configuration, readable by everyone (the app fetches `monetization` with the anon key).
-- Only the service role / dashboard can write.
create table if not exists public.app_config (
  key        text primary key,
  value      jsonb       not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);
alter table public.app_config enable row level security;
drop policy if exists "app config: public read" on public.app_config;
create policy "app config: public read" on public.app_config for select to anon, authenticated using (true);
grant select on public.app_config to anon, authenticated;

-- Partial override merged onto the app's built-in MonetizationConfig (docs/MONETIZATION.md). `{}` = defaults.
insert into public.app_config (key, value)
values ('monetization', '{"version": 1}'::jsonb)
on conflict (key) do nothing;

-- ------------------------------------------------------------------------- entitlements & purchases

-- Server-side source of truth for a user's plan. Written ONLY by the service role (verify-purchase edge
-- function, Play Real-time Developer Notifications handler, AI gateway metering). Clients can only read.
create table if not exists public.entitlements (
  user_id          uuid primary key references auth.users (id) on delete cascade,
  plan             text        not null default 'FREE' check (plan in ('FREE', 'PRO', 'LIFETIME')),
  is_trial         boolean     not null default false,
  expires_at       timestamptz,
  -- Purchased AI credit packs remaining (never expire).
  ai_pack_balance  integer     not null default 0 check (ai_pack_balance >= 0),
  -- Monthly-allowance credits used in the current period, as metered by the AI gateway.
  ai_credits_used  integer     not null default 0 check (ai_credits_used >= 0),
  period_start     timestamptz not null default date_trunc('month', now()),
  updated_at       timestamptz not null default now()
);
alter table public.entitlements enable row level security;
drop policy if exists "entitlements: read own" on public.entitlements;
create policy "entitlements: read own" on public.entitlements for select to authenticated using (auth.uid() = user_id);
revoke all on public.entitlements from anon;
revoke insert, update, delete on public.entitlements from authenticated;
grant select on public.entitlements to authenticated;

-- Verified store purchases (audit trail + idempotency for consumable credit packs).
create table if not exists public.purchases (
  id              bigint generated always as identity primary key,
  user_id         uuid references auth.users (id) on delete set null,
  store           text        not null default 'play',
  product_id      text        not null,
  product_type    text        not null check (product_type in ('subs', 'inapp')),
  purchase_token  text        not null unique,
  order_id        text,
  state           text        not null default 'PURCHASED',
  acknowledged    boolean     not null default false,
  consumed        boolean     not null default false,
  credits_granted integer     not null default 0,
  expires_at      timestamptz,
  raw             jsonb,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create index if not exists purchases_user_idx on public.purchases (user_id, created_at desc);
alter table public.purchases enable row level security;
drop policy if exists "purchases: read own" on public.purchases;
create policy "purchases: read own" on public.purchases for select to authenticated using (auth.uid() = user_id);
revoke all on public.purchases from anon;
revoke insert, update, delete on public.purchases from authenticated;
grant select on public.purchases to authenticated;

-- ----------------------------------------------------------------------------------- media storage

-- Private bucket; objects live under media/<user_id>/<asset_id>.<ext>.
insert into storage.buckets (id, name, public)
values ('media', 'media', false)
on conflict (id) do nothing;

drop policy if exists "media: read own folder" on storage.objects;
create policy "media: read own folder" on storage.objects for select to authenticated
  using (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "media: upload to own folder" on storage.objects;
create policy "media: upload to own folder" on storage.objects for insert to authenticated
  with check (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "media: update own folder" on storage.objects;
create policy "media: update own folder" on storage.objects for update to authenticated
  using (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "media: delete own folder" on storage.objects;
create policy "media: delete own folder" on storage.objects for delete to authenticated
  using (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text);

-- ---------------------------------------------------------------------------------- account deletion

-- Deletes every row the caller owns and then the auth user itself. Called by the app via
-- POST /rest/v1/rpc/delete_account when the `delete-account` edge function is not deployed.
-- The app deletes the user's storage objects through the Storage API *before* calling this (Supabase
-- forbids deleting storage.objects rows directly from SQL); the edge function does both server-side.
create or replace function public.delete_account()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;

  delete from public.scripts          where user_id = uid;
  delete from public.script_folders   where user_id = uid;
  delete from public.projects         where user_id = uid;
  delete from public.drafts           where user_id = uid;
  delete from public.media_assets     where user_id = uid;
  delete from public.beauty_presets   where user_id = uid;
  delete from public.prompter_presets where user_id = uid;
  delete from public.user_settings    where user_id = uid;
  delete from public.entitlements     where user_id = uid;
  -- Purchase records are kept anonymised for store reconciliation / tax obligations.
  update public.purchases set user_id = null, raw = null where user_id = uid;

  -- Removing the auth user revokes all sessions and cascades any remaining references.
  delete from auth.users where id = uid;
end;
$$;

revoke all on function public.delete_account() from public, anon;
grant execute on function public.delete_account() to authenticated;
