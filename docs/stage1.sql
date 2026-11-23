-- Stage 1 backup tables (Supabase / PostgREST). Run once in the Supabase SQL editor.
-- Every table here is pushed best-effort: if it does not exist the rest of the backup still succeeds
-- and CloudSync logs a warning.

-- ---------------------------------------------------------------- ledger (stage 1 E)
-- Gold / XP history ("ledger" in Room). Column names match CloudSync.ledgerJson.
-- Backup only: the app pushes it but never restores it (the totals live in the profile row).
create table if not exists public.ledger (
  id          bigint      not null,            -- Room auto-generated id
  device_id   text        not null,
  at_millis   bigint      not null default 0,  -- epoch millis
  xp          integer     not null default 0,
  gold        integer     not null default 0,  -- negative = spent
  reason      text        not null default '', -- "session:<id>", "chest", "badge:<id>", "reward:<id>:<name>", ...
  updated_at  timestamptz not null default now(),
  primary key (id, device_id)
);

-- Same access policy as the other tables, for example:
-- alter table public.ledger enable row level security;
-- create policy "anon all ledger" on public.ledger for all to anon using (true) with check (true);

-- ---------------------------------------------------------------- templates, template_items, measurements (stage 1 D)
-- "האימונים שלי" and body measurements. Column names match CloudSync.templateJson / templateItemJson /
-- measurementJson and the matching *From readers. Restored only on a fresh install (Room auto-generated ids).
create table if not exists public.templates (
  id            bigint      not null,            -- Room auto-generated id (workout_templates.id)
  device_id     text        not null,
  name_he       text        not null default '',
  created_at    bigint      not null default 0,  -- epoch millis
  last_used_at  bigint      not null default 0,  -- epoch millis, 0 = never started
  notes         text        not null default '',
  updated_at    timestamptz not null default now(),
  primary key (id, device_id)
);

create table if not exists public.template_items (
  id            bigint      not null,            -- Room auto-generated id
  device_id     text        not null,
  template_id   bigint      not null,
  order_index   integer     not null default 0,
  exercise_id   text        not null default '',
  sets          integer     not null default 0,
  reps          integer     not null default 0,
  weight_kg     double precision not null default 0,
  rest_sec      integer     not null default 0,
  note          text        not null default '',
  sets_csv      text        not null default '', -- per-set targets "kg:reps;kg:reps"
  updated_at    timestamptz not null default now(),
  primary key (id, device_id)
);

create table if not exists public.measurements (
  id              bigint      not null,          -- Room auto-generated id
  device_id       text        not null,
  date_epoch_day  bigint      not null default 0,
  waist_cm        double precision not null default 0,  -- 0 = not measured
  chest_cm        double precision not null default 0,
  arm_cm          double precision not null default 0,
  thigh_cm        double precision not null default 0,
  hips_cm         double precision not null default 0,
  updated_at      timestamptz not null default now(),
  primary key (id, device_id)
);

-- Same access policy as the other tables, for example:
-- alter table public.templates enable row level security;
-- create policy "anon all templates" on public.templates for all to anon using (true) with check (true);
-- alter table public.template_items enable row level security;
-- create policy "anon all template_items" on public.template_items for all to anon using (true) with check (true);
-- alter table public.measurements enable row level security;
-- create policy "anon all measurements" on public.measurements for all to anon using (true) with check (true);
-- Note: deleting a template / measurement in the app does not delete the cloud row (the client has no DELETE).
-- Progress photos are not uploaded (local files only).
