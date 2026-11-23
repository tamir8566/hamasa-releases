-- Multi-user: every user's cloud rows are separated by a personal recovery code (no email / password).
-- Run ONCE in the Supabase SQL editor (project oacijyezkwmysertkmvl), and only AFTER:
--   1. the app build with Account.kt / x-profile-code is installed on Tamir's phone, and
--   2. Tamir copied his code from Settings -> "החשבון שלי" (format XXXX-XXXX-XXXX).
-- Then replace TAMIR_CODE below (it appears exactly once, inside the DO block) with that code and run the whole file.
--
-- What it does, for every app table (a missing optional table is skipped with a notice):
--   * adds profile_code text, defaulting to the request's x-profile-code header;
--   * claims every existing row (profile_code is null) for Tamir;
--   * makes profile_code NOT NULL and the first column of the primary key, so two users can never collide;
--     PostgREST upserts (Prefer: resolution=merge-duplicates) merge on the primary key, so the app needs no
--     on_conflict parameter and keeps working with the new keys;
--   * drops every old policy (the permissive anon_all ones) and creates policies that only show / accept rows
--     whose profile_code equals the x-profile-code header.
-- Also creates public.profiles (one row per code) and public.ai_log (written only by the AI Edge Function).
--
-- Old app builds (before Account.kt) send no header: after this runs their backup is rejected (nothing is lost,
-- the local data stays on the phone). That is why the new build must be installed first.

begin;

-- ---------------------------------------------------------------- helper: the code of the current request
-- PostgREST puts all request headers (lower-case names) in the setting request.headers as JSON.
-- nullif: outside a request the setting is null or '' (casting '' to json would fail).
create or replace function public.request_profile_code() returns text
language sql stable
as $$
  select nullif(current_setting('request.headers', true), '')::json ->> 'x-profile-code'
$$;
grant execute on function public.request_profile_code() to anon, authenticated;

-- ---------------------------------------------------------------- new tables
-- One row per user. The app upserts {profile_code, last_seen, app_build} on every backup (CloudSync.pushAll).
create table if not exists public.profiles (
  profile_code text        primary key default public.request_profile_code(),
  created_at   timestamptz not null default now(),
  last_seen    timestamptz not null default now(),
  app_build    integer     not null default 0
);
grant select, insert, update, delete on public.profiles to anon, authenticated;

-- AI usage log, written by supabase/functions/ai with the service role key (RLS on, no policy = anon sees nothing).
create table if not exists public.ai_log (
  id           bigint generated always as identity primary key,
  at           timestamptz not null default now(),
  profile_code text        not null,
  provider     text        not null default '',
  tier         text        not null default '',
  ok           boolean     not null default false,
  ms           integer     not null default 0,
  in_bytes     integer     not null default 0,
  error        text        not null default ''
);
create index if not exists ai_log_code_at_idx on public.ai_log (profile_code, at);
alter table public.ai_log enable row level security;
revoke all on public.ai_log from anon, authenticated;

-- ---------------------------------------------------------------- every app table
do $$
declare
  tamir    text := 'TAMIR_CODE';   -- <== Tamir's recovery code from Settings, e.g. 'ABCD-EFGH-JK23'
  tables   text[] := array[
    'sessions', 'set_logs', 'weights', 'meals', 'badges', 'unlocked_modes', 'inventory', 'boss_progress',
    'rewards', 'ledger', 'templates', 'template_items', 'measurements', 'custom_exercises', 'profile', 'profiles'
  ];
  t        text;
  pk_name  text;
  pk_cols  text;
  has_code boolean;
  pol      record;
begin
  if tamir !~ '^[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$' then
    raise exception 'Replace TAMIR_CODE with Tamir''s recovery code (XXXX-XXXX-XXXX) before running this file';
  end if;

  foreach t in array tables loop
    if to_regclass('public.' || t) is null then
      raise notice 'table public.% does not exist, skipped', t;
      continue;
    end if;

    -- 1. the column, defaulting to the request header (so even a row sent without the field gets the right owner)
    execute format('alter table public.%I add column if not exists profile_code text', t);
    execute format('alter table public.%I alter column profile_code set default public.request_profile_code()', t);

    -- 2. one-time claim: everything that exists today is Tamir's
    execute format('update public.%I set profile_code = %L where profile_code is null', t, tamir);
    execute format('alter table public.%I alter column profile_code set not null', t);

    -- 3. primary key = (profile_code, <old key columns>)
    select c.conname,
           string_agg(quote_ident(a.attname), ', ' order by k.ord),
           bool_or(a.attname = 'profile_code')
      into pk_name, pk_cols, has_code
      from pg_constraint c
      cross join lateral unnest(c.conkey) with ordinality as k(attnum, ord)
      join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum
     where c.conrelid = ('public.' || t)::regclass and c.contype = 'p'
     group by c.conname;

    if pk_name is null then
      raise notice 'public.% has no primary key: left as is (rows are still separated by the policies)', t;
    elsif not has_code then
      execute format('alter table public.%I drop constraint %I', t, pk_name);
      execute format('alter table public.%I add primary key (profile_code, %s)', t, pk_cols);
      raise notice 'public.%: primary key is now (profile_code, %)', t, pk_cols;
    end if;
    pk_name := null; pk_cols := null; has_code := null;

    execute format('create index if not exists %I on public.%I (profile_code)', t || '_profile_code_idx', t);

    -- 4. policies: drop every old one (anon_all and friends), then one per command, all keyed on the header
    execute format('alter table public.%I enable row level security', t);
    for pol in select policyname from pg_policies where schemaname = 'public' and tablename = t loop
      execute format('drop policy %I on public.%I', pol.policyname, t);
    end loop;
    execute format('create policy mu_select on public.%I for select to anon, authenticated using (profile_code = (select public.request_profile_code()))', t);
    execute format('create policy mu_insert on public.%I for insert to anon, authenticated with check (profile_code = (select public.request_profile_code()))', t);
    execute format('create policy mu_update on public.%I for update to anon, authenticated using (profile_code = (select public.request_profile_code())) with check (profile_code = (select public.request_profile_code()))', t);
    execute format('create policy mu_delete on public.%I for delete to anon, authenticated using (profile_code = (select public.request_profile_code()))', t);
  end loop;

  -- Tamir's row in profiles
  insert into public.profiles (profile_code) values (tamir) on conflict (profile_code) do nothing;
end
$$;

commit;

-- PostgREST must see the new column at once (Supabase usually reloads by itself after DDL).
notify pgrst, 'reload schema';

-- ---------------------------------------------------------------- checks (optional, run one by one)
-- Every row has an owner (expect only Tamir's code):
--   select profile_code, count(*) from public.sessions group by 1;
-- The policies (expect mu_select / mu_insert / mu_update / mu_delete on each table, no anon_all):
--   select tablename, policyname from pg_policies where schemaname = 'public' order by 1, 2;
-- The new keys:
--   select conrelid::regclass, pg_get_constraintdef(oid) from pg_constraint where contype = 'p' and connamespace = 'public'::regnamespace;
-- Who uses the app, and AI calls per user in the last 7 days:
--   select * from public.profiles order by last_seen desc;
--   select profile_code, provider, count(*), sum(case when ok then 1 else 0 end) as ok from public.ai_log where at > now() - interval '7 days' group by 1, 2;
--
-- A user lost the code? It is in their phone's Settings. If the phone is gone too, find their row by last_seen
-- in public.profiles and give them that code (anyone holding a code sees that code's data, so hand it over privately).
