# -*- coding: utf-8 -*-
"""Fresh download of app-debug.apk for a specific build, with retry + same-file resume + size verify."""
import os
import sys
import time
import urllib.request

TAG = sys.argv[1] if len(sys.argv) > 1 else "latest-debug"
URL = f"https://github.com/E5-lab/AC-Camera/releases/download/{TAG}/app-debug.apk"
OUT = f"app-debug-{TAG}.apk"

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

    for attempt in range(1, 13):
        have = os.path.getsize(OUT) if os.path.exists(OUT) else 0
        if have >= total:
            print(f"[dl] COMPLETE {have} bytes ({have / 1048576:.1f} MB)", flush=True)
            return 0
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
            time.sleep(3)
    have = os.path.getsize(OUT) if os.path.exists(OUT) else 0
    if have >= total:
        print(f"[dl] COMPLETE {have} bytes", flush=True)
        return 0
    print(f"[dl] FAILED at {have} / {total} bytes")
    return 3

if __name__ == "__main__":
    sys.exit(main())
