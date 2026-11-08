"""Score Latin-script OCR outputs against ground truth: CER, WER and exact lines.

usage: score_latin.py gt.json name=outputs.json [name=outputs.json ...]
Text is NFKC-normalized, whitespace collapsed, curly quotes folded; case and punctuation count
(a dictionary lookup needs "it's" and "its" told apart). WER shows word segmentation errors
(a missing space breaks a lookup even when every letter is right).
"""
import json
import sys
import unicodedata


def norm(s):
    s = unicodedata.normalize("NFKC", s or "").replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return " ".join(s.split())


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[-1]


gt = json.load(open(sys.argv[1]))
print(f"{'engine':28} {'CER':>7} {'WER':>7} {'exact':>7}")
for arg in sys.argv[2:]:
    name, path = arg.split("=", 1)
    out = json.load(open(path))
    chars = errs = words = werrs = exact = 0
    for key, truth in gt.items():
        t, o = norm(truth), norm(out.get(key, ""))
        chars += len(t)
        errs += lev(t, o)
        words += len(t.split())
        werrs += lev(t.split(), o.split())
        exact += t == o
    print(f"{name:28} {errs / chars:7.2%} {werrs / words:7.2%} {exact / len(gt):7.0%}")
