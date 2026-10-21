# -*- coding: utf-8 -*-
"""Poll GitHub Actions build; on success download app-debug.apk from latest-debug release."""
import json
import sys
import time
import urllib.request

REPO = "E5-lab/AC-Camera"
RUN_ID = 39571458401
APK_OUT = "app-debug.apk"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ac-camera-poller"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))

def main():
    deadline = time.time() + 25 * 60  # 25 min max
    while time.time() < deadline:
        run = get(f"https://api.github.com/repos/{REPO}/actions/runs/{RUN_ID}")
        status = run.get("status")
        conclusion = run.get("conclusion")
        print(f"[poll] status={status} conclusion={conclusion}", flush=True)
        if status == "completed":
            if conclusion == "success":
                print("[poll] BUILD SUCCESS", flush=True)
                return 0
            else:
                print(f"[poll] BUILD FAILED: {conclusion}", flush=True)
                return 2
        time.sleep(30)
    print("[poll] TIMEOUT waiting for build", flush=True)
    return 3

if __name__ == "__main__":
    sys.exit(main())
