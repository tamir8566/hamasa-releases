"""Prints the watch fields of a cloud profile row (see .github/workflows/watch_probe.yml)."""
import json
import sys

rows = json.load(open(sys.argv[1]))
if not isinstance(rows, list):
    print("unexpected answer:", str(rows)[:500])
    sys.exit(0)
print("profile rows:", len(rows))
for row in rows:
    d = row.get("data", row)
    if isinstance(d, str):
        d = json.loads(d)
    print("updated:", row.get("updated_at") or row.get("updatedAt") or "?")
    for k in ("watchZones", "watchZonesDiag", "watchSleep"):
        print("==", k)
        print(d.get(k, "<missing>"))
    print("== streak fields")
    for k in ("streak", "bestStreak", "shields", "lastActiveDay", "restWeekUsed"):
        print(k, "=", d.get(k, "<missing>"))
    print("== watchSessions (last 15)")
    print("\n".join(str(d.get("watchSessions", "")).split(";")[-15:]))
