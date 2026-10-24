"""A/B meikiocr settings on the phone through the debug API's /ocr_bench, alternating configs.

usage: bench_device.py REMOTE_DIR ROUNDS OUT_PREFIX name=query [name=query ...]
  e.g. bench_device.py /sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/bench/h40 \
         3 out/ab orig=variants=0 cuts=variants=1

Phones throttle within minutes of back-to-back inference (135 -> 186 ms on the S24+), so configs
run in alternating order each round with a cool-down (COOL seconds, default 20) and are compared
on the mean over rounds. The first config's texts are the reference for the per-image text diff;
each config's texts are saved to OUT_PREFIX_<name>.json for score.py. Stop auto OCR first, and
end on the app's default config (/ocr_bench options stay loaded).
"""
import json
import os
import statistics
import sys
import time
import urllib.parse
import urllib.request

remote, rounds, prefix = sys.argv[1], int(sys.argv[2]), sys.argv[3]
configs = [arg.split("=", 1) for arg in sys.argv[4:]]
cool = float(os.environ.get("COOL", "20"))
stats = {name: {} for name, _ in configs}
texts = {}
for r in range(rounds):
    for name, query in configs if r % 2 == 0 else configs[::-1]:
        url = f"http://localhost:9099/ocr_bench?dir={urllib.parse.quote(remote)}&n=2&{query}"
        data = json.load(urllib.request.urlopen(url, timeout=3600))
        if not data.get("ok"):
            sys.exit(f"{name}: {data}")
        for key, value in data["stats"].items():
            stats[name].setdefault(key, []).append(value["mean"])
        texts[name] = data["texts"]
        time.sleep(cool)
reference = texts[configs[0][0]]
for name, _ in configs:
    diff = sum(texts[name].get(k) != v for k, v in reference.items())
    timing = " ".join(f"{k}={statistics.mean(stats[name][k]):.1f}" for k in ("total", "detPrep", "detRun", "recRun"))
    print(f"{name:12s} {timing}  text differs on {diff}/{len(reference)}")
    json.dump(texts[name], open(f"{prefix}_{name}.json", "w"), ensure_ascii=False, indent=0)
