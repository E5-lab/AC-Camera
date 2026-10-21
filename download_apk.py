# -*- coding: utf-8 -*-
"""Robust download of app-debug.apk: verifies size against Content-Length, resumes, retries."""
import os
import sys
import time
import urllib.request

URL = "https://github.com/E5-lab/AC-Camera/releases/download/latest-debug/app-debug.apk"
OUT = "app-debug.apk"

def fetch_length():
    req = urllib.request.Request(URL, headers={"User-Agent": "poll", "Range": "bytes=0-0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        cr = r.headers.get("Content-Range")
        if cr and "/" in cr:
            return int(cr.split("/")[-1])
    return 0

def main():
    total = fetch_length()
    print(f"[dl] expected total {total / 1048576:.1f} MB", flush=True)
    if total <= 0:
        print("[dl] cannot determine total size")
        return 2

    for attempt in range(1, 9):
        have = os.path.getsize(OUT) if os.path.exists(OUT) else 0
        if have >= total:
            break
        print(f"[dl] attempt {attempt}: resume from {have / 1048576:.1f} MB", flush=True)
        try:
            req = urllib.request.Request(
                URL,
                headers={"User-Agent": "poll", "Range": f"bytes={have}-"},
            )
            with urllib.request.urlopen(req, timeout=180) as r, open(OUT, "ab") as f:
                while True:
                    chunk = r.read(256 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
        except Exception as e:
            print(f"[dl] attempt {attempt} error: {e}", flush=True)
            time.sleep(2)
        size = os.path.getsize(OUT)
        print(f"[dl] now at {size / 1048576:.1f} / {total / 1048576:.1f} MB", flush=True)
        if size >= total:
            print(f"[dl] COMPLETE {size} bytes ({size / 1048576:.1f} MB)", flush=True)
            return 0
    print("[dl] FAILED to reach full size after retries")
    return 3

if __name__ == "__main__":
    sys.exit(main())
