"""Region-guided grouping prototype: lines from any engine + text regions from the bubble detector.

1. furigana filter: kana-only line, thin (<= RUBY_RATIO x neighbour), right of / above a kanji line
2. each line joins the detected text region (cls 1/2) holding most of its area
3. lines outside every region fall back to Manatan's Union-Find merge

Usage: python -I group_regions.py LINES.json REGIONS.json OUT.json [--no-regions] [--manatan-ruby]
LINES.json: run_ppmanga.py output or run_device.py output (its "lines").
"""
import json
import re
import sys

import manatan_merge

KANJI = re.compile(r"[一-鿿々]")
KANA_ONLY = re.compile(r"^[ぁ-ゖァ-ヺー・]+$")
RUBY_RATIO = 0.68


def thick(b, v):
    return (b[2] - b[0]) if v else (b[3] - b[1])


def is_vertical(line):
    b = line["box"]
    w, h = b[2] - b[0], b[3] - b[1]
    if len(line["text"]) == 1:
        return h > w * 0.8
    return h > w


def is_ruby(sub, lines):
    t = sub["text"].strip()
    if not KANA_ONLY.match(t):
        return False
    sv = is_vertical(sub)
    sb = sub["box"]
    for m in lines:
        if m is sub or not KANJI.search(m["text"]) or is_vertical(m) != sv and len(sub["text"]) > 1:
            continue
        mb = m["box"]
        mv = is_vertical(m)
        mt = thick(mb, mv)
        if thick(sb, mv) > mt * RUBY_RATIO:
            continue
        if mv:
            gap = sb[0] - mb[2]
            span = min(mb[3], sb[3]) - max(mb[1], sb[1])
        else:
            gap = mb[1] - sb[3]
            span = min(mb[2], sb[2]) - max(mb[0], sb[0])
        if -mt * 0.6 < gap < mt * 0.5 and span > 0:
            return True
    return False


def area(b):
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def inter(a, b):
    return area([max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])])


def order(lines, v):
    # columns (rows) = lines whose cross-axis ranges overlap by half the thinner one
    lo, hi, mn = (0, 2, 1) if v else (1, 3, 0)
    cols = []
    for l in sorted(lines, key=lambda l: -l["box"][hi] if v else l["box"][lo]):
        for c in cols:
            ov = min(c["hi"], l["box"][hi]) - max(c["lo"], l["box"][lo])
            if ov > 0.5 * min(c["hi"] - c["lo"], l["box"][hi] - l["box"][lo]):
                c["lines"].append(l)
                c["lo"], c["hi"] = min(c["lo"], l["box"][lo]), max(c["hi"], l["box"][hi])
                break
        else:
            cols.append({"lo": l["box"][lo], "hi": l["box"][hi], "lines": [l]})
    cols.sort(key=lambda c: -(c["lo"] + c["hi"]) if v else (c["lo"] + c["hi"]))
    return [l for c in cols for l in sorted(c["lines"], key=lambda l: l["box"][mn])]


def split_by_regions(line, texts):
    # a line whose glyphs fall into different text regions is cut there (detector line spanning two bubbles)
    gl = line.get("glyphs")
    if not gl or len(gl) != len(line["text"]) or len(texts) < 2:
        return [line]
    owners = []
    for g in gl:
        best, best_j = 0.0, None
        for j, r in enumerate(texts):
            f = inter(g, r["box"]) / max(1, area(g))
            if f > best:
                best, best_j = f, j
        owners.append(best_j if best >= 0.5 else None)
    if len(set(o for o in owners if o is not None)) < 2:
        return [line]
    parts, start = [], 0
    for i in range(1, len(owners) + 1):
        if i == len(owners) or (owners[i] != owners[start] and owners[i] is not None and owners[start] is not None):
            g = gl[start:i]
            box = [min(b[0] for b in g), min(b[1] for b in g), max(b[2] for b in g), max(b[3] for b in g)]
            parts.append(dict(line, text=line["text"][start:i], box=box, glyphs=g))
            start = i
    return parts


def group(lines, regions, w, h, use_regions=True):
    lines = [l for l in lines if l["text"].strip()]
    lines = [l for l in lines if not is_ruby(l, lines)]
    texts = [r for r in regions if r["cls"] in (1, 2)] if use_regions else []
    lines = [p for l in lines for p in split_by_regions(l, texts)]
    owner = {}
    for i, l in enumerate(lines):
        best, best_r = 0.0, None
        for j, r in enumerate(texts):
            f = inter(l["box"], r["box"]) / max(1, area(l["box"]))
            if f > best + 0.05 or (f > best - 0.05 and best_r is not None and area(r["box"]) < area(texts[best_r]["box"])):
                best, best_r = max(f, best), j
        if best >= 0.5:
            owner.setdefault(best_r, []).append(l)
        else:
            owner.setdefault(("free", i), []).append(l)
    blocks = []
    free = []
    for key, group_lines in owner.items():
        if isinstance(key, tuple):
            free.extend(group_lines)
            continue
        v = sum(is_vertical(l) for l in group_lines) * 2 >= len(group_lines)
        ordered = order(group_lines, v)
        box = [min(l["box"][0] for l in ordered), min(l["box"][1] for l in ordered), max(l["box"][2] for l in ordered), max(l["box"][3] for l in ordered)]
        blocks.append({"text": "".join(l["text"] for l in ordered), "box": box, "vertical": v, "lines": [l["text"] for l in ordered]})
    for l in free:
        l.setdefault("vertical", is_vertical(l))
    blocks += manatan_merge.auto_merge(free, w, h) if free else []
    return blocks


def main():
    lines_data = json.load(open(sys.argv[1], encoding="utf-8"))
    regions = json.load(open(sys.argv[2]))
    use_regions = "--no-regions" not in sys.argv
    out = {}
    for page, d in lines_data.items():
        w, h = d["w"], d["h"]
        ls = [dict(l) for l in d["lines"]]
        rr = regions.get(page, {}).get("regions", [])
        sx, sy = w / regions[page]["w"], h / regions[page]["h"]
        rr = [dict(r, box=[r["box"][0] * sx, r["box"][1] * sy, r["box"][2] * sx, r["box"][3] * sy]) for r in rr]
        out[page] = {"ms": d.get("ms"), "blocks": group(ls, rr, w, h, use_regions)}
    json.dump(out, open(sys.argv[3], "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
