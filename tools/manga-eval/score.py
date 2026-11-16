"""Score manga OCR outputs against hand-checked bubble ground truth.

Usage: python -I score.py data/gt.json engineA.json [engineB.json ...]
Engine JSON: {page: {"ms": int, "blocks": [{"text": str, ...}]}} (a .mokuro file also works).

Per GT bubble the best single predicted block is taken (a bubble split in two or merged with its
neighbour scores as the errors a tap would show). Text is compared punctuation-insensitive.
  bubbleCER  mean of min(1, edit(gt, best)/len(gt))
  exact      bubbles read exactly; good = CER <= 0.1
  charRecall page multiset overlap of characters (recognition only, ignores grouping)
  noise      predicted blocks with Japanese text that match no GT bubble (SFX, garbage)
"""
import json
import os
import re
import sys
import unicodedata
from collections import Counter

KEEP = re.compile(r"[ぁ-ゖァ-ヺー一-鿿々A-Za-z0-9]")
FOLD = str.maketrans({"ヘ": "へ", "ベ": "べ", "ペ": "ぺ"})


def norm(text):
    t = unicodedata.normalize("NFKC", text).translate(FOLD)
    t = "".join(ch for ch in t if KEEP.match(ch))
    return t.lstrip("ー一")


def edit(a, b):
    if not a:
        return len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def load_engine(path):
    data = json.load(open(path, encoding="utf-8"))
    if "pages" in data:  # .mokuro
        out = {}
        for page in data["pages"]:
            name = os.path.splitext(os.path.basename(page["img_path"]))[0]
            out[name] = {"ms": None, "blocks": [{"text": "".join(b["lines"])} for b in page["blocks"]]}
        return out
    return data


def score(gt, engine):
    cers, exact, good, gt_chars, hit_chars, noise, times = [], 0, 0, 0, 0, 0, []
    misses = []
    for page, bubbles in gt.items():
        if page.startswith("_"):
            continue
        pred = engine.get(page, {"blocks": []})
        if pred.get("ms") is not None:
            times.append(pred["ms"])
        texts = [norm(b["text"]) for b in pred["blocks"]]
        texts = [t for t in texts if t]
        used = set()
        for raw in bubbles:
            g = norm(raw)
            if not g:
                continue
            best, best_i = 1.0, None
            for i, t in enumerate(texts):
                c = min(1.0, edit(g, t) / len(g))
                if c < best:
                    best, best_i = c, i
            if best_i is not None and best < 0.5:
                used.add(best_i)
            cers.append(best)
            exact += best == 0
            good += best <= 0.1
            if best > 0.1:
                misses.append((page, raw, texts[best_i] if best_i is not None else "-"))
        gc = Counter("".join(norm(b) for b in bubbles))
        pc = Counter("".join(texts))
        gt_chars += sum(gc.values())
        hit_chars += sum((gc & pc).values())
        for i, t in enumerate(texts):
            if i in used or len(t) < 2:
                continue
            if all(min(1.0, edit(norm(b), t) / max(1, len(t))) > 0.5 for b in bubbles):
                noise += 1
    n = len(cers)
    return {
        "bubbleCER": round(sum(cers) / n, 3),
        "exact": f"{exact}/{n}",
        "good": f"{good}/{n}",
        "charRecall": round(hit_chars / gt_chars, 3),
        "noise": noise,
        "ms_median": sorted(times)[len(times) // 2] if times else None,
    }, misses


def main():
    gt = json.load(open(sys.argv[1], encoding="utf-8"))
    verbose = os.environ.get("MISSES")
    for path in sys.argv[2:]:
        result, misses = score(gt, load_engine(path))
        print(f"{os.path.basename(path):28s} {result}")
        if verbose:
            for page, raw, got in misses:
                print(f"    {page}: {raw}  ->  {got}")


if __name__ == "__main__":
    main()
