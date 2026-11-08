"""Build a language pack zip (OCR models etc.) for GitHub Releases.

usage: python3 build_pack.py LANG OUT_DIR
Downloads the pinned upstream files listed in PACKS[LANG], writes pack.json / paddle.json /
NOTICE.txt and OUT_DIR/dsm-lang-LANG-VERSION.zip, and prints the SHA-256 and size to paste into
the language module (`lang/<Language>.kt`, `pack = LanguagePack(...)`). Publish with:

  gh release create lang-LANG-VERSION OUT_DIR/dsm-lang-LANG-VERSION.zip --latest=false \
      --title "Language pack: ..." --notes "..."

`--latest=false` matters: the in-app updater reads releases/latest and must keep seeing the app.
Needs PyYAML (the recognizer's character list is read from its inference.yml).
"""
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

import yaml

HF = "https://huggingface.co/PaddlePaddle/{repo}/resolve/{rev}/{file}"

PACKS = {
    "en": {
        "version": 1,
        "name": "English OCR (PaddleOCR PP-OCRv5 det + PP-OCRv6 small rec)",
        "paddle": {
            "det": ("PP-OCRv5_mobile_det_onnx", "e6f4fa85f00e168c862bc462aebca69eef9b3d3d"),
            "rec": ("PP-OCRv6_small_rec_onnx", "b8f84f0b80c529de40b4fbb3544b84fa7233a513"),
            "thresh": 0.3, "boxThresh": 0.6, "unclipRatio": 1.5, "detLimit": 960, "minScore": 0.5,
        },
    },
}

NOTICE = """This language pack contains PaddleOCR models by PaddlePaddle, licensed under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0), downloaded unmodified from Hugging Face:
{sources}
"""


def fetch(repo, rev, file):
    url = HF.format(repo=repo, rev=rev, file=file)
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def main():
    lang, out = sys.argv[1], sys.argv[2]
    spec = PACKS[lang]
    os.makedirs(out, exist_ok=True)
    name = f"dsm-lang-{lang}-{spec['version']}.zip"
    path = os.path.join(out, name)
    p = spec["paddle"]
    det_repo, det_rev = p["det"]
    rec_repo, rec_rev = p["rec"]
    rec_yml = yaml.safe_load(fetch(rec_repo, rec_rev, "inference.yml"))
    chars = rec_yml["PostProcess"]["character_dict"]
    assert all("\n" not in c for c in chars)
    paddle = {k: v for k, v in p.items() if k not in ("det", "rec")}
    paddle.update({"det": "det.onnx", "rec": "rec.onnx", "dict": "rec_dict.txt", "name": f"PaddleOCR ({rec_repo.removesuffix('_onnx')})"})
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.json", json.dumps({"language": lang, "version": spec["version"], "name": spec["name"]}, indent=1))
        z.writestr("paddle.json", json.dumps(paddle, indent=1))
        z.writestr("rec_dict.txt", "\n".join(chars) + "\n")
        z.writestr("det.onnx", fetch(det_repo, det_rev, "inference.onnx"))
        z.writestr("rec.onnx", fetch(rec_repo, rec_rev, "inference.onnx"))
        sources = "\n".join(f"  https://huggingface.co/PaddlePaddle/{r} @ {v}" for r, v in (p["det"], p["rec"]))
        z.writestr("NOTICE.txt", NOTICE.format(sources=sources))
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    print(f"{path}\nsha256 {digest}\nsize {os.path.getsize(path) / 1e6:.1f} MB\nchars {len(chars)}")


if __name__ == "__main__":
    main()
