# מחשבון האנרגיה: הנוסחאות, המקורות, ומה האפליקציה עושה בפועל

קוד: `domain/energy/EnergyModel.kt` (פונקציות טהורות), `data/repo/EnergyData.kt` (קריאת נתונים), `ui/energy/EnergyScreen.kt` (המסך).
כל המספרים הם הערכה. הטעות הטיפוסית של נוסחאות שריפה היא כ-10% לכל כיוון. לכן החלק החשוב ביותר הוא החישוב לפי הנתונים בפועל (סעיף 6): הוא מתקן את הנוסחה לפי הגוף של המשתמש.

## 0. התמונה הכללית

שריפה יומית (TDEE) = שריפה טבעית (BMR) + פעילות יומיומית (NEAT) + אימונים (EAT, נטו) + עיכול (TEF)

| רכיב | איך מחושב | מתי |
|---|---|---|
| BMR | מיפלין-סנט ז'אור; קאץ'-מקארדל אם אחוז השומן ידוע | תמיד |
| NEAT | אחוז מה-BMR לפי רמת פעילות; בשלב 3: לפי צעדים מהשעון | תמיד |
| EAT | MET מטבלת Compendium 2024, נטו; בשלב 3: דופק (Keytel) להליכה/ריצה | לכל אימון שהסתיים |
| TEF | 10% מהאכילה | תמיד |
| TDEE אמיתי | ממוצע אכילה פחות שינוי משקל × 7,700 | אחרי שבועיים של נתונים |

## 1. שריפה טבעית (BMR / RMR)

### מיפלין-סנט ז'אור (ברירת המחדל)
- גבר: `BMR = 10×משקל(ק"ג) + 6.25×גובה(ס"מ) − 5×גיל + 5`
- אישה: `BMR = 10×משקל + 6.25×גובה − 5×גיל − 161`

מקור: Mifflin et al., Am J Clin Nutr 1990;51:241–247.
דיוק: הסקירה השיטתית של Frankenfield ושות' (J Am Diet Assoc 2005) מצאה שזו הנוסחה האמינה ביותר, וחוזה את השריפה בטווח של ±10% מהמדידה אצל יותר אנשים מכל נוסחה אחרת, גם אצל אנשים עם השמנה. אצל אנשים עם השמנה הדיוק נמוך יותר, והטעות נוטה להיות בשני הכיוונים. זו הסיבה שהאפליקציה לא סומכת על הנוסחה לבד, ומציעה מעבר ל-TDEE לפי נתונים בפועל.

### קאץ'-מקארדל (כשאחוז השומן ידוע)
- `BMR = 370 + 21.6 × מסת גוף רזה(ק"ג)`, כאשר `מסה רזה = משקל × (1 − אחוז שומן/100)`
- נוסחת קנינגהם הדומה: `RMR = 500 + 22 × מסה רזה` (נותנת מעט יותר; לא בשימוש, מתועדת להשוואה).

האפליקציה משתמשת בקאץ'-מקארדל רק כשהוזן אחוז שומן בין 3 ל-60. מסת הגוף הרזה מסבירה את רוב השונות ב-BMR, ולכן כשהיא ידועה (סריקה, מדידה) היא עדיפה, במיוחד אצל אנשים עם הרבה שריר.

גיל לא ידוע (0): החישוב משתמש בגיל 30 עד שהמשתמש עונה. המסך שואל בפתיחה הראשונה. מין לא ידוע: גבר.

## 2. פעילות יומיומית שאינה אימון (NEAT)

### לפי רמת פעילות (היום)
ארגון המזון והחקלאות של האו"ם (FAO/WHO/UNU 2004) מגדיר רמת פעילות כוללת (PAL = TDEE/BMR):
יושבני או פעילות קלה 1.40–1.69, פעיל 1.70–1.99, פעיל מאוד 2.00–2.40.

האימונים נספרים באפליקציה בנפרד, ולכן הרמה כאן מתארת רק את החיים שמחוץ לאימונים. NEAT מחושב כאחוז מה-BMR:

| רמה | NEAT כחלק מ-BMR | PAL שמתקבל בלי אימונים (כולל עיכול) |
|---|---|---|
| יושבני | 0.25 | ≈ 1.39 |
| קל | 0.40 | ≈ 1.56 |
| בינוני | 0.55 | ≈ 1.72 |
| פעיל | 0.70 | ≈ 1.89 |

החישוב: `PAL ≈ (1 + NEAT) / 0.9` (כי עיכול הוא כ-10% מההוצאה). הערכים בחרו כך שייפלו בתוך טווחי FAO. ברירת המחדל: יושבני (שמרני לירידה במשקל; הנתונים בפועל יתקנו).

### לפי צעדים (שלב 3, שעון גלקסי). פונקציה מוכנה: `neatWithSteps`, `neatFromSteps`
- עלות הליכה נטו לפי משוואת ההליכה של ACSM: `VO2 = 0.1×מהירות(מ'/דקה) + 1.8×מהירות×שיפוע + 3.5`. החלק האופקי, 0.1 מ"ל חמצן לק"ג למטר, כפול כ-5 קלוריות לליטר חמצן = **0.0005 קלוריות לק"ג למטר**.
- אורך צעד ≈ 0.414 × גובה (הערכה גסה, מקובלת במדי צעדים; 0.75 מ' אם הגובה לא ידוע).
- `קלוריות מצעדים = צעדים × אורך צעד(מ') × 0.0005 × משקל`. לדוגמה: 105 ק"ג, 185 ס"מ, 10,000 צעדים ≈ 400 קלוריות נטו.
- `NEAT בשלב 3 = 0.10 × BMR (עמידה, תנועות קטנות; הנחה) + קלוריות מצעדים`. צעדים שנעשו בזמן אימון הליכה/ריצה רשום צריך להוריד, כדי לא לספור פעמיים.

## 3. עיכול (TEF)
`TEF = 0.10 × האכילה`. בתזונה מעורבת, האנרגיה שהגוף מוציא על עיכול היא כ-10% מהאכילה (Westerterp, Nutr Metab 2004). חלבון גבוה יותר (20–30% מהקלוריות שלו), שומן נמוך (0–3%).
- במאזן היומי: 10% ממה שנאכל באותו יום בפועל.
- בתכנון (TDEE לשמירה): `TDEE = (BMR + NEAT + EAT) / 0.9`, כלומר 10% מהאכילה ששומרת על המשקל.

## 4. אימונים (EAT)

### שיטת MET (ברירת המחדל)
הגדרה: MET אחד = 3.5 מ"ל חמצן לק"ג לדקה ≈ 1 קלוריה לק"ג לשעה (2024 Adult Compendium of Physical Activities).
- ברוטו: `קלוריות לדקה = MET × 3.5 × משקל / 200` (נוסחת ההמרה של אתר ה-Compendium).
- **נטו (מה שהאפליקציה סופרת)**: `(MET − 1) × 3.5 × משקל / 200 × דקות`. מחסירים MET אחד כי ה-BMR כבר נספר לכל היממה. בלי זה, אימון של שעה היה סופר שוב כ-100 קלוריות של מנוחה.
- הסתייגות ידועה: 3.5 מ"ל/ק"ג/דקה הוא ערך ממוצע. אצל רוב האנשים, ובמיוחד במשקל גבוה, המנוחה האמיתית לק"ג נמוכה יותר (Byrne et al., J Appl Physiol 2005, "Metabolic equivalent: one size does not fit all"). השימוש בנטו מקטין את הטעות הזו, והחישוב לפי נתונים בפועל מתקן את השאר.

### ערכי MET (Compendium 2024)
| סוג באפליקציה | עצימות | MET | קוד |
|---|---|---|---|
| מכון / אימון זעיר | רגיל (סטים עם מנוחה של 2–3 דקות) | 3.5 | 02054 אימון התנגדות, כמה תרגילים, 8–15 חזרות |
| | בינוני (צפוף) | 5.0 | 02052 סקוואט/דדליפט; 02035 מעגלי בינוני |
| | גבוה (כמעט בלי מנוחה) | 6.0 | 02050 משקולות חופשיות/מכונות, מאמץ גבוה |
| יוגה | האטה | 2.3 | 02150 |
| | ויניאסה (ברירת המחדל) | 2.7 | 02185 |
| | יוגה פאוור | 4.0 | 02160 |
| הליכה | איטית 3.2–3.9 קמ"ש | 2.8 | 17152 |
| | בינונית 4.5–5.5 קמ"ש (ברירת המחדל) | 3.8 | 17190 |
| | מהירה 5.6–6.3 קמ"ש | 4.8 | 17200 |
| ריצה | 6.4–6.8 קמ"ש | 6.5 | 12028 |
| | ג'וגינג בקצב חופשי (ברירת המחדל) | 7.5 | 12020 |
| | 9.7–10.1 קמ"ש | 9.3 | 12050 |

ערכים נוספים מהטבלה לעתיד: מעגלי קל 3.5 (02034), מעגלי עם קטלבל ומנוחה מינימלית 7.5 (02040), HIIT בינוני 7.0 (02210), HIIT גבוה 11.0 (02214), קליסתניקס בינוני 3.8 (02022), אליפטיקל בינוני 5.0 (02048), חתירה כללית 7.3 (02070), הליכה בעלייה 1–5% 5.3 (17034).

עצימות אימון כוח נקבעת לפי **צפיפות האימון**: סטים שהושלמו לדקה (כולל מנוחה).
`< 0.40 סטים לדקה → 3.5`, `0.40–0.55 → 5.0`, `≥ 0.55 → 6.0`. אימון טיפוסי: 20 סטים בשעה = 0.33 → 3.5.
משך: `durationSec` של האימון; אם חסר, לפי שעות ההתחלה והסיום. אימון בלי משך לא מקבל מספר.
דוגמה: 105 ק"ג, שעה, 3.5 MET → `2.5 × 3.5 × 105 / 200 × 60 ≈ 276` קלוריות נטו.

שום דבר לא נשמר: השריפה של כל אימון מחושבת מחדש בכל הצגה (מסך סיום אימון, פרטי אימון, מחשבון).

### לפי דופק (שלב 3): Keytel et al., J Sports Sci 2005;23(3):289–297
תוצאה בקילוג'אול לדקה, מחולקת ב-4.184 לקלוריות. HR = דופק ממוצע, משקל בק"ג, גיל בשנים.
- גבר, בלי VO2max: `−55.0969 + 0.6309×HR + 0.1988×משקל + 0.2017×גיל`
- אישה, בלי VO2max: `−20.4022 + 0.4472×HR − 0.1263×משקל + 0.074×גיל`
- עם VO2max (מ"ל/ק"ג/דקה), המודל המשולב: `−59.3954 + מין×(−36.3781 + 0.271×גיל + 0.394×משקל + 0.404×VO2max + 0.634×HR) + (1−מין)×(0.274×גיל + 0.103×משקל + 0.380×VO2max + 0.450×HR)`, מין = 1 לגבר, 0 לאישה.
  כלומר גבר: `−95.7735 + 0.634×HR + 0.404×VO2max + 0.394×משקל + 0.271×גיל`; אישה: `−59.3954 + 0.45×HR + 0.380×VO2max + 0.103×משקל + 0.274×גיל`.

התוצאה היא ברוטו; האפליקציה מחסירה את ה-BMR של אותן דקות (`BMR/1440 × דקות`) כדי לקבל נטו.
מגבלות: המשוואה נבנתה על פעילות אירובית תת-מרבית (דופק בערך 90–150). לכן היא בשימוש **רק** להליכה ולריצה, ורק כשהדופק הממוצע בין 90 ל-200. באימוני כוח הדופק עולה בלי עלייה מקבילה בצריכת חמצן (מאמץ סטטי, לחץ), והנוסחה מנפחת את השריפה. לכן אימון כוח תמיד לפי MET.

## 5. יעד לירידה במשקל

### אנרגיה של קילו
הכלל הישן: ליברה = 3,500 קלוריות, כלומר **כ-7,700 קלוריות לק"ג**. Hall ושות' (Lancet 2011, "Quantification of the effect of energy imbalance on bodyweight", הבסיס ל-NIH Body Weight Planner) הראו שהכלל טוב לטווח קצר, אבל מגזים לטווח ארוך: כשהמשקל יורד, השריפה יורדת איתו, והגירעון מתכווץ. זמן מחצית של התגובה הוא כשנה. בנוסף, בשבועות הראשונים הירידה מהירה מהצפוי (מים וגליקוגן, צפיפות אנרגיה נמוכה).
מה האפליקציה עושה עם זה: הגירעון מחושב כאחוז מהמשקל **הנוכחי**, וה-BMR מתעדכן עם כל שקילה, כך שהיעד יורד יחד עם המשקל. והחישוב לפי נתונים בפועל (סעיף 6) מודד את השריפה האמיתית במקום להניח אותה. המסך כותב במפורש שבשבועיים הראשונים הירידה מהירה יותר.

### קצב
`גירעון יומי = קצב(% בשבוע)/100 × משקל × 7,700 / 7`. אפשרויות: 0.25%, 0.5% (ברירת מחדל), 0.75%, 1%.
קצב של 0.5–1% ממשקל הגוף בשבוע הוא ההמלצה המקובלת לשמירה על מסת שריר באימוני כוח (Helms et al., JISSN 2014; Garthe et al., IJSNEM 2011: ירידה של 0.7% בשבוע שמרה טוב יותר על מסה רזה וכוח מ-1.4%).
דוגמה: 105 ק"ג, 0.5% → 0.525 ק"ג בשבוע → גירעון 577 ביום.

### רצפות
- גירעון מקסימלי: 1,000 קלוריות ביום.
- אכילה מינימלית: `max(1,500 לגבר / 1,200 לאישה, BMR)`. הקווים התחתונים לגבר ולאישה הם הקצוות התחתונים של הנחיית AHA/ACC/TOS 2013 לטיפול בהשמנה (1,200–1,500 לנשים, 1,500–1,800 לגברים, או גירעון של 500–750 ביום). "לא מתחת ל-BMR" הוא כלל שמרני של האפליקציה, לא ממצא מחקרי.
- היעד מעוגל לעשרות. אם רצפה שינתה את המספר, המסך אומר זאת.

`יעד = TDEE − גירעון` (TDEE מהנוסחה, או מהנתונים בפועל אם המשתמש בחר ויש מספיק נתונים).
`ירידה צפויה בשבוע = (TDEE − יעד) × 7 / 7,700`.

### מקרו (לפי הכיוון, ראו סעיף 5א)
- **חלבון**:
  - אחוז שומן ידוע: `2.4 / 2.2 / 2.0 גרם לק"ג מסה רזה` (ירידה / שמירה / עלייה).
  - לא ידוע: `2.0 / 1.8 / 1.7 גרם לק"ג של משקל ייחוס` (ירידה / שמירה / עלייה), כאשר `משקל ייחוס = min(משקל, 27 × גובה(מ')²)` (המשקל שבו מדד מסת הגוף הוא 27). כך אצל אדם כבד החלבון לא מנופח בגלל רקמת שומן.
  - בגירעון בצד הגבוה של הטווח (שמירה על מסת שריר), בעודף בצד הנמוך (העודף האנרגטי עצמו חוסך חלבון; Iraki 2019: 1.6–2.2 גרם/ק"ג בעונת המסה).
  - לא יותר מ-40% מהקלוריות.
  - מקורות: ISSN Position Stand (Jäger et al., JISSN 2017): 1.4–2.0 גרם/ק"ג ביום למתאמנים, ויותר (2.3–3.1 גרם לק"ג מסה רזה) בגירעון אצל מתאמני כוח רזים. Morton et al. (Br J Sports Med 2018): התועלת לשריר מגיעה לרוויה סביב 1.6 גרם/ק"ג (גבול עליון של רווח סמך ≈ 2.2). Helms et al. (2014): 2.3–3.1 גרם לק"ג מסה רזה בגירעון.
- **שומן**: 25% מהקלוריות בירידה ובעלייה, 30% בשמירה; תמיד לפחות 0.5 גרם לק"ג משקל ייחוס. (Helms 2014: 15–30% בגירעון; Iraki 2019: 0.5–1.5 גרם/ק"ג בעודף, כדי להשאיר מקום לפחמימות לאימון.)
- **פחמימות**: מה שנשאר, חלקי 4.
- כל ערך שהמשתמש הזין ידנית (> 0) תמיד גובר.

## 5א. הכיוון: ירידה / שמירה / עלייה

שדה `goalDirection` ב-Prefs: `-1` אוטומטי (ברירת מחדל), `0` ירידה, `1` שמירה, `2` עלייה. נבחר במסך המחשבון ("הכיוון").
אוטומטי (`EnergyData.autoDirection`):
- משקל נוכחי מעל משקל היעד ביותר מחצי קילו → **ירידה**.
- מתחת למשקל היעד ביותר מחצי קילו → **עלייה** (או **שמירה** אם המטרה במאמן היא ירידה במשקל).
- ביעד → **עלייה** אם המטרה במאמן היא בניית שריר, אחרת **שמירה**.

יעד לפי הכיוון (`EnergyModel.intakeFor`):
- **ירידה**: כמו בסעיף 5 (`targetIntake`).
- **שמירה**: `יעד = TDEE` (`maintenanceIntake`), עם אותן רצפות מינימום.
- **עלייה (מסה נקייה)**: `יעד = TDEE + עודף` (`gainIntake`), כאשר `עודף = קצב(% בשבוע)/100 × משקל × 7,700 / 7`, מוגבל ל-150–500 ביום. קצבים: 0.25% (ברירת מחדל, `gainRateX100 = 25`) או 0.5%.
  דוגמה: 90 ק"ג, 0.25% → 0.225 ק"ג בשבוע → עודף ≈ 250 ביום; 0.5% → ≈ 500 ביום.
  מקורות: Iraki et al. 2019 (Sports): עלייה של כ-0.25–0.5% ממשקל הגוף בשבוע למתאמנים מתחילים/בינוניים, עודף של כ-10–20%, ועודף שמרני יותר למתקדמים. Slater et al. 2019 (Front Nutr): גודל העודף הדרוש לבניית שריר לא ידוע במדויק, ועודף גדול מוסיף בעיקר שומן, ולכן עדיף עודף מתון עם מעקב משקל.
  7,700 לק"ג הוא מספר תכנון: רקמה רזה צפופה פחות באנרגיה, אבל לבנייתה יש מחיר אנרגטי. המשקל בפועל הוא השופט; אם הוא עולה מהר מהצפי, מורידים.
- הכיוון קובע גם את המקרו האוטומטי (סעיף 5). כל ערך ידני עדיין גובר.
- כל קוראי היעדים משתמשים באותו כיוון: מסך האוכל, הווידג'טים (`Widgets.refreshAll`), ציד הקלוריות (דרך `kcalGoal`), וטיפ החלבון של המאמן.

### רענון ומשקל עדכני
- `EnergyData.snapshot()` תמיד לוקח את השקילה **האחרונה** בטבלת המשקלים (`weights`) ל-BMR, לפעילות היומיומית (צעדים), ליעד ולמקרו. משקל ההתחלה משמש רק כשאין אף שקילה.
- שריפה של אימון ישן מחושבת לפי השקילה שהייתה בתוקף ביום האימון (מדויק יותר להיסטוריה); אימון של היום משתמש בשקילה האחרונה.
- רענון: בכל פתיחה/חזרה לאפליקציה (`MainActivity.onResume` → `EnergyData.syncAutoGoalSoon()`, לכל היותר פעם בדקה), בכל כניסה למסך האוכל, ובכל חזרה למסך המחשבון. המסך כותב בראשו לפי איזו שקילה חושב ("לפי משקל 103.5 ק"ג (שקילה מ-31.10)").

## 6. TDEE לפי הנתונים בפועל (מסתגל)

הרעיון (כמו ב-MacroFactor): אם אכלת בממוצע X ביום, והמשקל ירד בקצב Y ק"ג ליום, אז שרפת `X + Y × 7,700`.

- חלון: 28 הימים שמסתיימים **אתמול** (היום עוד פתוח), החל מהיום הרשום הראשון בחלון.
- יום רשום = יום עם לפחות 800 קלוריות ביומן (ימים חלקיים לא נספרים; ממוצע האכילה רק על ימים רשומים).
- מגמת משקל: **שיפוע קו רגרסיה ליניארית** דרך כל השקילות בחלון (ק"ג ליום). זה מסנן תנודות יומיות של מים ומלח, ולא סובל מהפיגור של ממוצע נע מעריכי בחלון קצר.
- `TDEE = ממוצע אכילה − שיפוע × 7,700`.
- ביטחון:
  - **טוב**: לפחות 21 ימים, לפחות 80% מהם רשומים, לפחות 8 שקילות הפרוסות על 14 ימים לפחות.
  - **ראשוני**: לפחות 14 ימים, לפחות 10 ימים רשומים, לפחות 4 שקילות הפרוסות על 10 ימים לפחות.
  - אחרת: אין מספר. גם תוצאה מחוץ לטווח 1,200–5,500 נחשבת לנתונים לא עקביים ולא מוצגת.
- MacroFactor מדווחים שההערכה שלהם מתייצבת אחרי 14–30 ימים, תלוי בעקביות הרישום.
- המסך מציג את המספר, את הפער מהנוסחה, ומציע "לבנות את היעד על הנתון הזה" (`useAdaptiveTdee`). אם הנתונים נעלמים (פער ברישום), היעד חוזר לנוסחה אוטומטית.

## 7. מה מאוחסן ואיפה

שדות חדשים ב-`Prefs` (מגובים גם בענן דרך `CloudSync.profileJson/profileFrom`):
`age` (0 = לא ידוע), `sex` ("M"/"F"/""), `bodyFatPct` (0 = לא ידוע), `activityLevel` (0–3), `goalRateX100` (25/50/75/100), `kcalAuto`, `useAdaptiveTdee`, `goalDirection` (-1 אוטומטי, 0–2), `gainRateX100` (25/50).
חלבון/פחמימות/שומן: השדות הקיימים `proteinGoal/carbsGoal/fatGoal` (0 = אוטומטי). קלוריות: `kcalGoal` הקיים.
כש-`kcalAuto` פעיל, `EnergyData.syncAutoGoal()` כותב את היעד המחושב לתוך `kcalGoal` (בפתיחת האפליקציה, מסך האוכל ומסך המחשבון), כך שכל מי שקורא את `kcalGoal` (ציד קלוריות, סגירת יום) רואה אותו מספר. שמירת יעד ידני בהגדרות, במחשבון, או קבלת הצעת המאמן, מכבה את האוטומטי.
שריפת אימונים לא נשמרת בכלל: מחושבת מהטבלאות הקיימות.

## 8. נקודות חיבור לשלב 3 (שעון)

- `EnergyModel.neatWithSteps(bmr, steps, weightKg, heightCm)` במקום `neatFromLevel` ליום שיש בו ספירת צעדים; מעבירים ל-`tdeeEstimate(..., neatOverride = ...)`.
- `EnergyModel.workoutKcal(..., avgHr = ...)` / `EnergyData.burnFor(..., avgHr = ...)`: דופק ממוצע לאימון הליכה/ריצה מפעיל את Keytel אוטומטית.
- `keytelKcalPerMin(..., vo2max = ...)`: אם השעון נותן VO2max, המשוואה המדויקת יותר נבחרת.
- חובר בשלב 3 (`EnergyData.snapshot` / `sessionBurn`, נתונים מ־`data/health/WatchStore`). VO2max עוד לא נקרא. הפרטים ב־`docs/watch.md`.

## מקורות

1. Mifflin MD, St Jeor ST, et al. A new predictive equation for resting energy expenditure in healthy individuals. Am J Clin Nutr. 1990;51(2):241–247. PMID 2305711. https://pubmed.ncbi.nlm.nih.gov/2305711/
2. Frankenfield D, Roth-Yousey L, Compher C. Comparison of predictive equations for resting metabolic rate in healthy nonobese and obese adults: a systematic review. J Am Diet Assoc. 2005;105(5):775–789. PMID 15883556. https://pure.psu.edu/en/publications/comparison-of-predictive-equations-for-resting-metabolic-rate-in-/
3. ACE: Resting metabolic rate, equations (Mifflin-St Jeor, Harris-Benedict, Katch-McArdle 370 + 21.6×LBM, Cunningham 500 + 22×LBM). https://www.acefitness.org/certifiednewsarticle/2882/resting-metabolic-rate-best-ways-to-measure-it-and-raise-it-too/
4. Herrmann SD, Willis EA, Ainsworth BE, et al. 2024 Adult Compendium of Physical Activities: A third update of the energy costs of human activities. J Sport Health Sci. 2024;13(1):6–12. https://pubmed.ncbi.nlm.nih.gov/38242596/ ; https://pmc.ncbi.nlm.nih.gov/articles/PMC10818145/
5. Compendium tables: conditioning exercise https://pacompendium.com/conditioning-exercise/ ; walking https://pacompendium.com/walking/ ; running https://pacompendium.com/running/ ; unit conversions (MET × 3.5 × kg / 200, ACSM equations) https://pacompendium.com/unite-conversions/
6. Byrne NM, Hills AP, Hunter GR, et al. Metabolic equivalent: one size does not fit all. J Appl Physiol. 2005;99(3):1112–1119. https://pubmed.ncbi.nlm.nih.gov/15831804/
7. Keytel LR, Goedecke JH, Noakes TD, et al. Prediction of energy expenditure from heart rate monitoring during submaximal exercise. J Sports Sci. 2005;23(3):289–297. https://pubmed.ncbi.nlm.nih.gov/15966347/ (the coefficients as reproduced by Myzone: https://www.myzone.digital/en/knowledge/how-does-myzone-calculate-calorie-burn and Hexoskin: https://support.hexoskin.com/calculation-of-the-energy-expenditure)
8. ACSM metabolic equations (walking: VO2 = 0.1×speed + 1.8×speed×grade + 3.5; 5 kcal per L O2). https://www.ideafit.com/how-to-calculate-calories-expended
9. FAO/WHO/UNU. Human energy requirements (2004), section 5.3, Table 5.3 (PAL categories). https://www.fao.org/4/Y5686E/y5686e07.htm
10. Westerterp KR. Diet induced thermogenesis. Nutr Metab (Lond). 2004;1:5. https://nutritionandmetabolism.biomedcentral.com/articles/10.1186/1743-7075-1-5
11. Hall KD, Sacks G, Chandramohan D, et al. Quantification of the effect of energy imbalance on bodyweight. Lancet. 2011;378(9793):826–837. https://www.niddk.nih.gov/research-funding/at-niddk/labs-branches/laboratory-biological-modeling/integrative-physiology-section/research/body-weight-planner ; https://www.globalfamilydoctor.com/Resources/A-Ztopiclisting/JournalWatch/Modelstoquantificationweightgainandweightloss.aspx
12. Jensen MD, Ryan DH, et al. 2013 AHA/ACC/TOS Guideline for the Management of Overweight and Obesity in Adults (1,200–1,500 kcal women, 1,500–1,800 kcal men; 500–750 kcal/day deficit). https://acc.org/Latest-in-Cardiology/Journal-Scans/2014/07/10/16/17/2013-AHA-ACC-TOS-Guideline-for-the-Management-of-Overweight
13. Jäger R, Kerksick CM, et al. International Society of Sports Nutrition Position Stand: protein and exercise. J Int Soc Sports Nutr. 2017;14:20. https://pubmed.ncbi.nlm.nih.gov/28642676/
14. Helms ER, Zinn C, Rowlands DS, Brown SR. A systematic review of dietary protein during caloric restriction in resistance trained lean athletes: a case for higher intakes. Int J Sport Nutr Exerc Metab. 2014;24(2):127–138. https://pubmed.ncbi.nlm.nih.gov/24092765/
15. Morton RW, Murphy KT, et al. A systematic review, meta-analysis and meta-regression of the effect of protein supplementation on resistance training-induced gains in muscle mass and strength in healthy adults. Br J Sports Med. 2018;52(6):376–384. https://pubmed.ncbi.nlm.nih.gov/28698222/
16. Garthe I, Raastad T, Refsnes PE, Koivisto A, Sundgot-Borgen J. Effect of two different weight-loss rates on body composition and strength and power-related performance in elite athletes. Int J Sport Nutr Exerc Metab. 2011;21(2):97–104. https://lida.sport-iat.de/ta/Record/4020442?lng=en
17. MacroFactor: algorithms and core philosophy (expenditure from intake + weight trend; stabilises after ~14–30 days). https://macrofactor.com/macrofactors-algorithms-and-core-philosophy/
18. Iraki J, Fitschen P, Espinar S, Helms E. Nutrition Recommendations for Bodybuilders in the Off-Season: A Narrative Review. Sports (Basel). 2019;7(7):154. (weight gain ~0.25-0.5% of bodyweight/week, ~10-20% surplus; protein 1.6-2.2 g/kg; fat 0.5-1.5 g/kg; carbs the rest). https://pubmed.ncbi.nlm.nih.gov/31247944/ ; https://pmc.ncbi.nlm.nih.gov/articles/PMC6680710/
19. Slater GJ, Dieter BP, Marsh DJ, Helms ER, Shaw G, Iraki J. Is an Energy Surplus Required to Maximize Skeletal Muscle Hypertrophy Associated With Resistance Training. Front Nutr. 2019;6:131. https://doi.org/10.3389/fnut.2019.00131
20. Helms ER, Aragon AA, Fitschen PJ. Evidence-based recommendations for natural bodybuilding contest preparation: nutrition and supplementation. J Int Soc Sports Nutr. 2014;11:20. (deficit: protein 2.3-3.1 g/kg lean mass, fat 15-30% of kcal). https://pmc.ncbi.nlm.nih.gov/articles/PMC4033492/
