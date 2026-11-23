"""Sends one real chef request through the AI proxy (same system prompt as data/ai/Chef.kt) and prints the answer."""
import json
import re
import sys
import time
import urllib.request

src = open("app/src/main/java/com/tamir/hamasa/data/ai/Chef.kt", encoding="utf-8").read()
system = re.search(r'private const val SYSTEM = """(.*?)"""', src, re.S).group(1).strip()
system += "\nThe user is a man: address him in male grammar."
cfg = open("app/src/main/java/com/tamir/hamasa/data/cloud/CloudConfig.kt", encoding="utf-8").read()
url = re.search(r'SUPABASE_URL = "([^"]+)"', cfg).group(1) + "/functions/v1/ai"
anon = re.search(r'SUPABASE_ANON_KEY =\s*"([^"]+)"', cfg).group(1)
prompt = (
    "Time now: 19:30.\nGoal: cutting.\nDaily goals: 2400 kcal, protein 180 g.\n"
    "Remaining for today: 650 kcal, protein 55 g, carbs 60 g, fat 20 g.\n"
    "MEAL: DINNER. This is DINNER, the LAST meal of the day: lighter food built around cheeses, bread, eggs, omelette, shakshuka, yogurt, salads. No heavy meat dishes. MOST IMPORTANT: dinner must CLOSE the day.\n"
    "Dinner calorie range (hard rule for EVERY recipe): 553 to 680 kcal, and as much of the remaining 55 g protein as possible.\n"
    "Recently suggested (do NOT suggest these again): עוף עם פסטה ורוטב עגבניות, חביתת ביצים וקוטג' עם טוסט מלא.\n"
    "His taste facts (hard rules):\n- לא אוכל דגים ומאכלי ים\n- לא אוכל הרבה ירקות\n- שונא עגבנייה שלמה, רוטב עגבניות בסדר\n"
    "His message now: " + (sys.argv[1] if len(sys.argv) > 1 else "מה להכין עכשיו?")
)
for tier in ("LITE",):
    body = json.dumps({"provider": "claude", "tier": tier, "maxTokens": 8192, "temperature": 0.8, "json": True,
                       "system": system, "parts": [{"text": prompt}]}).encode()
    req = urllib.request.Request(url, data=body, headers={
        "apikey": anon, "authorization": "Bearer " + anon, "x-profile-code": "TEST-TEST-TEST",
        "content-type": "application/json"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            out = r.read().decode()
    except Exception as e:
        out = "ERROR " + str(e)
    print(f"== {tier} {time.time() - t:.1f}s")
    print(out[:4000])
