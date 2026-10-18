"""OCR every image of a folder already pushed to the app's files dir, through the debug API.

usage: run_device.py REMOTE_SUBDIR LOCAL_IMAGES_DIR OUT.json [meiki|mlkit]
  adb push LOCAL_IMAGES_DIR/. /sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/REMOTE_SUBDIR/
  adb forward tcp:9099 tcp:9099   (keep DSM in the foreground and auto OCR stopped: they share the engine)
"""
import json
import os
import statistics
import sys
import urllib.parse
import urllib.request

remote, local, out = sys.argv[1:4]
engine = sys.argv[4] if len(sys.argv) > 4 else "meiki"
base = "/sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/" + remote
result, times = {}, []
for name in sorted(os.listdir(local)):
    query = urllib.parse.urlencode({"file": f"{base}/{name}", "engine": engine, "twoPass": "1"})
    data = json.load(urllib.request.urlopen("http://localhost:9099/ocr?" + query, timeout=60))
    result[name] = data.get("text", "")
    times.append(data.get("elapsedMs", 0))
print(f"{engine}: {len(result)} images, median {statistics.median(times)} ms", file=sys.stderr)
json.dump(result, open(out, "w"), ensure_ascii=False, indent=0)
