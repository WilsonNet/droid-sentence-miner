"""Run an on-device manga OCR engine over the eval pages through the debug API.

Pages must be in the app's files/manga-eval/ (see SKILL/validation docs). Needs `adb forward tcp:9099 tcp:9099`.
Usage: python -I run_device.py ENGINE OUT.json [extra=query&params] < pages listed as args
  python -I run_device.py lens out/lens.json "" op042 op064 ...
Writes {page: {"ms","w","h","blocks":[...],"lines":[...]}} (raw lines kept for host regrouping).
"""
import json
import sys
import urllib.parse
import urllib.request

PKG = "io.github.wilsonnet.droidsentenceminer.debug"


def main():
    engine, out, extra = sys.argv[1:4]
    pages = sys.argv[4:]
    result = {}
    for page in pages:
        path = f"/data/data/{PKG}/files/manga-eval/{page}.jpg"
        q = f"file={urllib.parse.quote(path)}&engine={engine}&raw=1" + (f"&{extra}" if extra else "")
        with urllib.request.urlopen(f"http://localhost:9099/manga_ocr?{q}", timeout=120) as r:
            d = json.loads(r.read())
        if not d.get("ok"):
            print(page, d, file=sys.stderr)
            continue
        result[page] = d
        print(page, d["ms"], "ms", len(d["blocks"]), "blocks", file=sys.stderr)
    json.dump(result, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
