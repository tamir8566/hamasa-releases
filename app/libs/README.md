# app/libs

## עברית
כאן צריך לשבת הקובץ **`samsung-health-data-api-1.1.0.aar`** (Samsung Health Data SDK, גרסה 1.1.0).
מורידים אותו ידנית מאתר המפתחים של סמסונג: developer.samsung.com ← Health ← Samsung Health Data SDK ← Download
(צריך חשבון סמסונג). מתוך הזיפ שמורד לוקחים את קובץ ה־AAR ושמים אותו כאן בדיוק בשם הזה.
`app/build.gradle.kts` טוען אותו כך: `implementation(files("libs/samsung-health-data-api-1.1.0.aar"))`.
בלי הקובץ הבנייה נכשלת. הקוד שמשתמש בו: `data/health/SamsungHealth.kt`, הסבר מלא ב־`docs/watch.md`.

## English
This folder must contain **`samsung-health-data-api-1.1.0.aar`** (Samsung Health Data SDK 1.1.0).
Download it by hand from the Samsung Developers site (developer.samsung.com → Health → Samsung Health Data SDK →
Download, Samsung account required), take the AAR out of the zip and put it here under exactly this name.
It is referenced by `app/build.gradle.kts` (`implementation(files("libs/samsung-health-data-api-1.1.0.aar"))`);
the build fails without it. Its runtime needs (Gson, the Kotlin parcelize runtime) are declared in the build file.
Code: `data/health/SamsungHealth.kt`; details: `docs/watch.md`.
