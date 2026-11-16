"""Python port of Manatan's crates/ocr-server/src/merge.rs (filter_bad_boxes + auto_merge),
used to group host-engine text lines into bubbles exactly like Manatan does.

Usage: python -I manatan_merge.py LINES.json OUT.json
LINES.json: {page: {"w","h","ms","lines":[{"box":[x1,y1,x2,y2],"text","vertical"}]}}
OUT.json:   {page: {"ms","blocks":[{"text","box","vertical"}]}}
"""
import json
import re
import sys

JP = re.compile(r"[ぁ-ゖァ-ヺ一-鿿]")
NOISE = re.compile(r"^[|—_ノヘく/\\:;]$")
KANJI = re.compile(r"[一-鿿]")
KATA = re.compile(r"[ァ-ヺ]")


def wh(b):
    return b[2] - b[0], b[3] - b[1]


def filter_bad(lines, pw, ph):
    n = len(lines)
    keep = [True] * n
    area = pw * ph
    for i, l in enumerate(lines):
        t = l["text"].strip()
        w, h = wh(l["box"])
        if len(t) == 1 and ((t.isascii() and not t.isalnum()) or t.isdigit() or NOISE.match(t)):
            keep[i] = False
        elif w * h < area * 0.0005 and not JP.search(t):
            keep[i] = False
        elif w * h > area * 0.30 and len(t) < 6:
            keep[i] = False
    for i in range(n):
        if not keep[i]:
            continue
        for j in range(n):
            if i == j or not keep[j]:
                continue
            a, b = lines[i]["box"], lines[j]["box"]
            xo = min(a[2], b[2]) - max(a[0], b[0])
            yo = min(a[3], b[3]) - max(a[1], b[1])
            if xo > 0 and yo > 0:
                inter = xo * yo
                ba = wh(b)[0] * wh(b)[1]
                aa = wh(a)[0] * wh(a)[1]
                if inter > ba * 0.3 and aa > ba * 3 and inter > ba * 0.8:
                    if JP.search(lines[j]["text"]) and lines[j]["text"] not in lines[i]["text"]:
                        continue
                    keep[j] = False
    for i in range(n):
        if not keep[i]:
            continue
        for j in range(n):
            if i == j or not keep[j]:
                continue
            m, s = lines[i], lines[j]
            if not KANJI.search(m["text"]) or KANJI.search(s["text"]) or KATA.search(s["text"]):
                continue
            mt = min(wh(m["box"]))
            st = min(wh(s["box"]))
            if st > mt * 0.8:
                continue
            mb, sb = m["box"], s["box"]
            xg = sb[0] - mb[2]
            yov = min(mb[3], sb[3]) - max(mb[1], sb[1])
            v = -mt * 0.5 < xg < mt * 0.5 and yov > 0
            yg = mb[1] - sb[3]
            xov = min(mb[2], sb[2]) - max(mb[0], sb[0])
            hz = -mt * 0.5 < yg < mt * 0.5 and xov > 0
            if v or hz:
                keep[j] = False
    return [l for i, l in enumerate(lines) if keep[i]]


def mergeable(a, b, ratio=3.0):
    if a["v"] != b["v"]:
        return False
    mf, nf = max(a["f"], b["f"]), min(a["f"], b["f"])
    fr = mf / nf
    if fr > ratio:
        return False
    raw = min(a["max"], b["max"]) - max(a["min"], b["min"])
    if raw < -nf * 0.5:
        return False
    ov = max(0.0, raw)
    gap = max(0.0, b["cmin"] - a["cmax"], a["cmin"] - b["cmax"])
    glob = ov / max(a["len"], b["len"])
    if gap < nf * 0.2:
        return True
    allowed = 0.0
    if fr < 1.25:
        allowed = 2.0 if glob > 0.8 else 0.9 if glob > 0.4 else 1.3
    elif glob > 0.5:
        allowed = 0.8
    if max(a["len"], b["len"]) / min(a["len"], b["len"]) > 2.5:
        allowed = min(allowed, 0.8)
    if gap > nf * 1.2 and fr > 1.15:
        return False
    if gap > nf * allowed:
        return False
    if ov <= 0:
        gm = max(0.0, b["min"] - a["max"], a["min"] - b["max"])
        if gm > nf * 0.6:
            return False
    return True


def auto_merge(lines, pw, ph):
    lines = filter_bad(lines, pw, ph)
    proc = []
    for l in lines:
        x1, y1, x2, y2 = l["box"]
        w, h = x2 - x1, y2 - y1
        t = l["text"]
        if len(t) == 1:
            v = h > w * 0.8
        else:
            v = l.get("vertical", False) or h > w
        if v:
            proc.append({"v": True, "f": max(w, 1), "len": max(h, 1), "min": y1, "max": y2, "cmin": x1, "cmax": x2})
        else:
            proc.append({"v": False, "f": max(h, 1), "len": max(w, 1), "min": x1, "max": x2, "cmin": y1, "cmax": y2})
    parent = list(range(len(proc)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(proc)):
        for j in range(i + 1, len(proc)):
            if mergeable(proc[i], proc[j]):
                parent[find(i)] = find(j)
    groups = {}
    for i in range(len(proc)):
        groups.setdefault(find(i), []).append(i)
    blocks = []
    for idx in groups.values():
        v = proc[idx[0]]["v"]
        gl = [lines[i] for i in idx]
        if v:
            gl.sort(key=lambda l: (-round(l["box"][2] / 6), l["box"][1]))
        else:
            gl.sort(key=lambda l: (round(l["box"][1] / 6), l["box"][0]))
        box = [min(l["box"][0] for l in gl), min(l["box"][1] for l in gl), max(l["box"][2] for l in gl), max(l["box"][3] for l in gl)]
        blocks.append({"text": "".join(l["text"] for l in gl), "box": box, "vertical": v, "lines": [l["text"] for l in gl]})
    return blocks


def main():
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    out = {}
    for page, d in data.items():
        out[page] = {"ms": d.get("ms"), "blocks": auto_merge(d["lines"], d["w"], d["h"])}
    json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
