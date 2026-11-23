-- Custom exercises backup table (Supabase / PostgREST).
-- Run once in the Supabase SQL editor. The app pushes this table best-effort: if it does not exist,
-- the rest of the backup still succeeds and a warning is logged (CloudSync: "custom_exercises push failed").
--
-- Column names match CloudSync.customExerciseJson / customExerciseFrom.
-- Primary key is (id, device_id) like the other per-device tables; upserts merge on it.

create table if not exists public.custom_exercises (
  id          text        not null,            -- "custom_<millis>", the Room primary key
  device_id   text        not null,
  name_he     text        not null default '',
  branch      text        not null default 'CORE',   -- CHEST | BACK | LEGS | SHOULDERS_ARMS | CORE | ENDURANCE
  equipment   text        not null default 'machine', -- barbell | dumbbell | machine | cable | body | band
  is_compound boolean     not null default false,
  unit        text        not null default 'kg',      -- kg | reps | sec
  description text        not null default '',
  target      text        not null default '',        -- see domain/coach/ExerciseTargets.kt keys
  preferred   boolean     not null default false,
  updated_at  timestamptz not null default now(),
  primary key (id, device_id)
);

-- Same access policy as the other tables. If the project uses RLS with the anon key, mirror the
-- policies you already have on e.g. public.sessions, for example:
--
-- alter table public.custom_exercises enable row level security;
-- create policy "anon all custom_exercises" on public.custom_exercises
--   for all to anon using (true) with check (true);

-- Note: deleting a custom exercise in the app does not delete the cloud row (the client has no DELETE).
-- A later "restore from cloud" (fill missing) would bring it back; delete the row here if that matters:
-- delete from public.custom_exercises where id = 'custom_...';
