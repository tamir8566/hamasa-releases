# המסע הגדול (HamasaHagadol) — Android fitness RPG for one user (Tamir)

## Hard rules
- UI language: Hebrew only. Layout is RTL (MainActivity forces LayoutDirection.Rtl). No English words in UI strings.
- THE IRON RULE: Shambiuluk's body (BodyStage) depends ONLY on body weight (weights table). Never on level/xp/sessions.
- The coach decides weights/reps/rest; game mechanics never add reps to heavy compound lifts (only to accessory work).
- Nothing is ever taken away from the player: levels, badges, gear, Shambiuloq's body stage never regress because of inactivity. Only the streak can break.
- Network: only data/cloud (Supabase backup). Gemini food is v2. No new Gradle dependencies without asking the integrator.
- There is NO local compiler in this environment. Code must be written carefully: check imports, nullability, and Compose API names. CI (GitHub Actions) is the only build. Keep code simple and explicit.
- Do not run git commands as an agent; the integrator commits.

## Architecture (package com.tamir.hamasa)
- `data/db` Room entities + DAOs (`Entities.kt`, `Daos.kt`, `AppDatabase.kt`). Add columns only via new entity fields + bump version (fallbackToDestructiveMigration is on).
- `data/prefs/Prefs.kt` DataStore profile: xp, gold, streak, shields, focus, morning roll, planned tomorrow, quiet hours, lifetimeKg.
- `data/repo/WorkoutRepository.kt` sessions/sets/weights/plan creation/finishSession → rewards.
- `data/repo/GameRepository.kt` grant xp/gold, levels, badges, modes, inventory, chests, streak, boss progress, badge evaluation, morning roll.
- `data/AppGraph.kt` service locator: `AppGraph.workouts`, `AppGraph.game`, `AppGraph.prefs`, `AppGraph.db`.
- `data/food` the food base ("מאגר המאכלים"): `FoodSeed` (built-in foods per 100 g, generated from `tools/foodbase`, mostly USDA SR Legacy under Hebrew names) + `FoodBaseStore` (his learned / hand-written rows, SharedPreferences + cloud profile). Pure matching and text parsing in `domain/food/FoodBase.kt`. A meal text or a renamed item is answered from the base first; the AI is asked only about unknown foods, which are then learned when the meal is saved.
- `domain/coach` pure logic: `ExerciseCatalog`, `CoachEngine`, types in `CoachTypes.kt`.
- `domain/game` pure logic + catalogs: `ModeCatalog`, `BadgeCatalog` (+ `GameFacts` evaluator), `GearCatalog`, `BossCatalog`, `ChestRoller`, `LevelTable`, `BodyStage`, `Voice`.
- `domain/tree/TreeModel.kt` branch levels + derived stats.
- `ui/<feature>/` one package per screen; `ui/theme` palette (`HColors`); `ui/common` shared composables (owned by integrator; agents may ADD files there with unique names, never edit existing ones).
- `ui/AppRoot.kt` navigation. Screen signatures are fixed (see the file). Do not change them.
- `work/ReminderScheduler.kt` WorkManager hourly reminder.
- Assets: `res/drawable-nodpi/` (PNG, names in `ui/common/Assets.kt`).

## Style
- Compose Material3, dark theme from `HColors`. Big readable text, comic-ink feel: thick rounded cards, gold accents, parchment text.
- Shambiuluk's voice: short sentences, periods, a twist at the end. Deadpan, self-aware, mock-formal but never silly ("מדוע", "היכן", "הידד", "אכן"), dramatic exaggeration ("רצח", "האיש והאגדה"), stretched letters for big moments ("הידדדדד"), fatigue is always "המפלצת". No emojis. The coach (plan text) is serious and factual; the humor lives in Shambiuluk, badges and bosses.
- Overused words, use sparingly. Hard caps across the whole app (app/src/main/java): "הבינותי" at most 6 occurrences, "לפרוטוקול" at most 3, "אינני" at most 8. The caps are currently full; a new line must not add one. Vary openers instead: "רשמתי.", "נרשם.", "קיבלתי.", "בסדר.", "טוב.", "יפה.", "ראיתי.", "נו.", "מקובל.", "סגור.", or no opener at all. Dialog confirm buttons use short varied labels ("סגור", "רשמתי", "טוב", "קיבלתי", "נו טוב", "בסדר"), not "הבינותי".
- Stories (StoryChapters, the AI story chapter in V2Game, anything narrative) must be CLEAR first: plain everyday Hebrew a 12-year-old understands on the first read, one concrete event per chapter with a beginning, a middle and an end, chapters that continue each other, light humor that is easy to get. No riddles, aphorisms or stacked metaphors (owner feedback 2026-11-01: the old cryptic lines were "not understandable and not good"). The deadpan voice below is for short hero lines, not a license to be obscure.
- Voice reference (the tone to match):
  - "הפעם לא אגיד. בפעם הבאה גם."
  - "אני מחזיק את הרמז. שתי ידיים. לא משחרר."
  - "הדרך לכאן עוברת דרך המכון. כנראה."
  - "לא שאלת, אבל: לא."
  - "תנאי אחד. פשוט. מסובך. תלוי ביום."
  - "חמש פעמים. זה כבר לא מקרה, זו מדיניות."

## Verification (owner's standing rule, from 2026-11-01)
Every change is checked two ways before it is reported as ready:
1. Screenshots: the CI Roborazzi screenshots (screenshots.zip on the release) of the affected screens are looked at.
2. A "clicking agent": Robolectric Compose interaction tests (app/src/test/.../flows/) that tap the real buttons of the affected flow and assert the result (DB/prefs/UI). New features get a flow test. CI reports failed flows; a failing flow is fixed before the change is called done.
3. CI behavior: failed tests never block a release (they are reported: "flow-check-N" issue, _flows.txt in screenshots.zip). A HUNG test run is stopped after 25 minutes and the whole test run is retried once; if it hangs again, NO release is published and an issue "tests-stuck-N" names the last running test — fix that test before anything else. Every test logs STARTED/PASSED/FAILED in the CI log.
4. Releases: a push to main only builds + tests (results in a pre-release "check-N" with screenshots.zip, no APK). A version for the phones is published only with "[release]" in the commit message or a manual run of "Build APK" — batch fixes, check, then release once. "Flows only" (workflow_dispatch) runs just the flow tests for quick iteration.
