import json, time, urllib.request, sys

RUN_ID = 39615245406
API = f"https://api.github.com/repos/E5-lab/AC-Camera/actions/runs/{RUN_ID}"
H = {"User-Agent": "poll"}

def get(url):
    for _ in range(5):
        try:
            return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=40))
        except Exception as e:
            print(f"  [net retry] {e}")
            time.sleep(10)
    raise SystemExit("network failed")

print(f"Polling run {RUN_ID} ...")
while True:
    run = get(API)
    status = run["status"]
    concl = run.get("conclusion")
    print(f"  [{time.strftime('%H:%M:%S')}] status={status} conclusion={concl}")
    if status == "completed":
        print("DONE", concl)
        sys.exit(0 if concl == "success" else 1)
    time.sleep(30)
