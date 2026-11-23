-- Stage 2 backup columns (Supabase / PostgREST). Run once in the Supabase SQL editor. Not run by the app.

-- ---------------------------------------------------------------- meals: macros and photo estimate (stage 2 F)
-- Column names match CloudSync.mealJson / mealFrom. Room side: MealEntity (db version 7, AppDatabase.MIGRATION_6_7).
-- Until this runs, pushing meals fails on the unknown columns (logged, the rest of the backup still succeeds);
-- rows restored without these columns read as manual meals without macros.
-- Meal photos are never stored or uploaded: the app deletes them right after the estimate.
alter table public.meals add column if not exists protein_g  double precision not null default 0;   -- grams
alter table public.meals add column if not exists carbs_g    double precision not null default 0;   -- grams
alter table public.meals add column if not exists fat_g      double precision not null default 0;   -- grams
alter table public.meals add column if not exists source     text             not null default 'manual'; -- 'manual' | 'photo'
alter table public.meals add column if not exists ai_kcal    integer          not null default 0;   -- Gemini's original total, 0 = manual
alter table public.meals add column if not exists items_json text             not null default '';  -- JSON array of recognized items

-- The new profile fields (proteinGoal, carbsGoal, fatGoal, lastPhotoDayBonus, lastCalorieHuntDay, goodGuessMeals)
-- travel inside the existing profile JSON (CloudSync.profileJson): no column needed.
