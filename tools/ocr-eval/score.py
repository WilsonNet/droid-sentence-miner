"""Score OCR outputs per frame against ground truth, and try fusion strategies.

usage: score.py gt.json name=outputs.json [name=outputs.json ...]
"""
import json
import re
import sys
import unicodedata
from collections import Counter


def norm(s):
    s = unicodedata.normalize("NFKC", s or "")
    return re.sub(r"[\s、。,.!?！？「」『』…・()（）\-―—|｜「」:;]", "", s)


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[-1]


JP = re.compile(r"[぀-ヿ一-鿿々ー]")


def subtitle_text(raw, gt=None):
    """Pick the subtitle rows from an engine's multi-row output.

    With gt: the contiguous row span closest to gt (isolates recognition quality).
    """
    rows = [r for r in (raw or "").split("\n") if r.strip()]
    if not rows:
        return ""
    spans = ["".join(rows[i:j]) for i in range(len(rows)) for j in range(i + 1, len(rows) + 1)]
    return min(spans, key=lambda s: lev(norm(s), norm(gt)))


def align(a, b):
    """Edit-distance alignment; returns list of (ca, cb) with None for gaps."""
    n, m = len(a), len(b)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] != b[j - 1]))
    out = []
    i, j = n, m
    while i or j:
        if i and j and d[i][j] == d[i - 1][j - 1] + (a[i - 1] != b[j - 1]):
            out.append((a[i - 1], b[j - 1])); i -= 1; j -= 1
        elif i and d[i][j] == d[i - 1][j] + 1:
            out.append((a[i - 1], None)); i -= 1
        else:
            out.append((None, b[j - 1])); j -= 1
    return out[::-1]


def medoid(reads):
    c = Counter(reads)
    return min(c, key=lambda x: (sum(n * lev(x, r) for r, n in c.items()), -c[x]))


def rover(reads, weights=None):
    """Character voting: align every read to the medoid backbone and vote per slot."""
    weights = weights or [1.0] * len(reads)
    backbone = medoid(reads)
    # slots: for each backbone position, votes for a char (or deletion); plus insertion votes before it
    sub = [Counter() for _ in backbone]
    ins = [Counter() for _ in range(len(backbone) + 1)]
    total = sum(weights)
    for r, w in zip(reads, weights):
        k = 0
        pending = ""
        for ca, cb in align(backbone, r):
            if ca is None:
                pending += cb
                continue
            if pending:
                ins[k][pending] += w
                pending = ""
            sub[k][cb if cb is not None else ""] += w
            k += 1
        if pending:
            ins[k][pending] += w
    out = []
    for k in range(len(backbone) + 1):
        if ins[k]:
            best, v = ins[k].most_common(1)[0]
            if v > total / 2:
                out.append(best)
        if k < len(backbone):
            best, _ = sub[k].most_common(1)[0]
            out.append(best)
    return "".join(out)


def main():
    gt = json.load(open(sys.argv[1]))
    engines = {}
    for arg in sys.argv[2:]:
        name, path = arg.split("=", 1)
        engines[name] = json.load(open(path))
    frames = [f for f in sorted(gt) if gt[f]]

    def report(name, texts):
        errs = chars = exact = 0
        for f in frames:
            g = norm(gt[f]); t = norm(texts.get(f, ""))
            e = lev(t, g)
            errs += e; chars += len(g); exact += e == 0
        print(f"{name:34s} CER {errs / chars * 100:5.2f}%  exact {exact}/{len(frames)} ({exact / len(frames) * 100:.0f}%)")

    picked = {n: {f: subtitle_text(o.get(f, ""), gt[f]) for f in frames} for n, o in engines.items()}
    for n, p in picked.items():
        report(f"single frame: {n}", p)

    # Per-frame fusion of engines (2 reads -> needs a tiebreak: prefer first engine on ties).
    names = list(engines)
    if len(names) >= 2:
        fused = {}
        for f in frames:
            reads = [norm(picked[n][f]) for n in names]
            fused[f] = rover(reads, weights=[1.01] + [1.0] * (len(reads) - 1))
        report("frame ROVER " + "+".join(names), fused)

    # Temporal groups: consecutive frames with the same GT = one on-screen line (like the tracker).
    groups, cur = [], []
    for f in frames:
        if cur and gt[f] == gt[cur[-1]] and int(f[1:4]) == int(cur[-1][1:4]) + 1:
            cur.append(f)
        else:
            if cur: groups.append(cur)
            cur = [f]
    if cur: groups.append(cur)

    def report_groups(name, fn):
        errs = chars = exact = 0
        for g in groups:
            out = fn(g)
            truth = norm(gt[g[0]])
            e = lev(out, truth)
            errs += e; chars += len(truth); exact += e == 0
        print(f"{name:34s} CER {errs / chars * 100:5.2f}%  exact {exact}/{len(groups)} ({exact / len(groups) * 100:.0f}%)  [lines]")

    print(f"\n{len(groups)} on-screen lines, {sum(map(len, groups)) / len(groups):.1f} frames each")
    for n in names:
        report_groups(f"line medoid: {n}", lambda g, n=n: medoid([norm(picked[n][f]) for f in g]))
        report_groups(f"line ROVER: {n}", lambda g, n=n: rover([norm(picked[n][f]) for f in g]))
    if len(names) >= 2:
        report_groups("line medoid: all engines", lambda g: medoid([norm(picked[n][f]) for n in names for f in g]))
        report_groups("line ROVER: all engines", lambda g: rover(
            [norm(picked[n][f]) for n in names for f in g],
            [1.01 if n == names[0] else 1.0 for n in names for f in g]))


if __name__ == "__main__":
    main()
