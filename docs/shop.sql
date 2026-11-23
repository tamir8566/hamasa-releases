-- Shop rewards backup table (Supabase / PostgREST).
-- Run once in the Supabase SQL editor. The app pushes this table best-effort: if it does not exist,
-- the rest of the backup still succeeds and a warning is logged (CloudSync: "rewards push failed").
--
-- Column names match CloudSync.rewardJson / rewardFrom.
-- Primary key is (id, device_id) like the other per-device tables; upserts merge on it.
-- Rewards are restored only on a fresh install (auto-generated Room ids, like sessions/meals).

create table if not exists public.rewards (
  id             bigint      not null,            -- Room auto-generated id
  device_id      text        not null,
  name_he        text        not null default '',
  price_gold     integer     not null default 0,
  note           text        not null default '',
  created_at     bigint      not null default 0,  -- epoch millis
  redeemed_count integer     not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (id, device_id)
);

-- Same access policy as the other tables. If the project uses RLS with the anon key, mirror the
-- policies you already have on e.g. public.sessions, for example:
--
-- alter table public.rewards enable row level security;
-- create policy "anon all rewards" on public.rewards
--   for all to anon using (true) with check (true);

-- Note: deleting a reward in the app does not delete the cloud row (the client has no DELETE).
-- Gold purchases themselves are not a separate table: they live in the local `ledger`
-- (reason "reward:<id>:<name>", "shop:shield", "shop:gear:<itemId>", "shop:chest"), which is not synced.
