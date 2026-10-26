#!/usr/bin/env python3
"""Score the microphone fallback against clean speech, and prototype its EQ.

Input WAVs come from the debug API `/mic_measure.wav` (stereo, 24 kHz: left = what DSM played
through the speaker, right = what the microphone recorded, optionally through the app's chain):

    curl -s "localhost:9099/mic_measure.wav?signal=pink&sec=8&amp=0.8&ns=1" -o pink.wav
    curl -s "localhost:9099/mic_measure.wav?signal=file&amp=0.7&ns=1" -o speech_raw.wav
    curl -s "localhost:9099/mic_measure.wav?signal=file&amp=0.7&ns=1&fx=1" -o speech_app.wav

Usage:
    python3 mic_eval.py score speech_raw.wav speech_app.wav ...   # metrics per file
    python3 mic_eval.py fit pink.wav [speech_raw.wav]             # Python port of MicEq.fit (+ score)
    python3 mic_eval.py response pink.wav                         # third-octave response vs 1 kHz

Metrics (recording aligned to the played reference by cross-correlation):
  LTAS dev  - std of the third-octave long-term spectrum difference, 200 Hz - 8 kHz (0 = same tone)
  LSD       - frame log-spectral distance on active frames (level matched)
  PESQ-wb / STOI - if `pesq` and `pystoi` are installed (pip install pesq pystoi in a venv)
"""
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 24000


def load(path):
    sr, d = wavfile.read(path)
    assert sr == SR and d.ndim == 2, f"{path}: need stereo {SR} Hz from /mic_measure.wav"
    return d[:, 0].astype(float) / 32768, d[:, 1].astype(float) / 32768


def align(x, y):
    c = signal.correlate(y, x, mode="full", method="fft")
    lag = int(np.argmax(np.abs(c)) - (len(x) - 1))
    y = y[lag:] if lag > 0 else np.concatenate([np.zeros(-lag), y])
    n = min(len(x), len(y))
    return x[:n], y[:n], lag


# --- RBJ biquads, same formulas as Biquad.kt
def peaking(f0, gain_db, q):
    a = 10 ** (gain_db / 40)
    w = 2 * np.pi * f0 / SR
    al = np.sin(w) / (2 * q)
    c = np.cos(w)
    b = np.array([1 + al * a, -2 * c, 1 - al * a])
    den = np.array([1 + al / a, -2 * c, 1 - al / a])
    return b / den[0], den / den[0]


def highpass(f0, q=0.7071):
    w = 2 * np.pi * f0 / SR
    al = np.sin(w) / (2 * q)
    c = np.cos(w)
    b = np.array([(1 + c) / 2, -(1 + c), (1 + c) / 2])
    den = np.array([1 + al, -2 * c, 1 - al])
    return b / den[0], den / den[0]


def resp_db(ba, freqs):
    _, h = signal.freqz(ba[0], ba[1], worN=freqs, fs=SR)
    return 20 * np.log10(np.abs(h))


# --- MicEq.kt constants
GRID = 60 * 2 ** (np.arange(0, int(np.log2(11000 / 60) * 24) + 1) / 24)
CENTERS = 160 * 2 ** (np.arange(0, 19) / 3)
Q, HPF, MAX_BOOST, MAX_CUT, LOW_LIMIT, RIDGE = 3.0, 120.0, 6.0, 20.0, 170.0, 0.5


def response(path, lead=12000, seconds=8):
    """Pink-noise response on GRID (like MicEq.response: PSD ratio, noise subtracted, 1/3 oct)."""
    x, y = load(path)
    nf = y[1200:lead - 1200]
    ys = y[lead + SR // 2: lead + seconds * SR]
    xs = x[lead: lead + seconds * SR]
    f, pxx = signal.welch(xs, SR, nperseg=4096)
    _, pyy = signal.welch(ys, SR, nperseg=4096)
    _, pnn = signal.welch(nf, SR, nperseg=4096)
    pc = np.maximum(pyy - pnn, pyy * 0.1)
    h = []
    for g in GRID:
        m = (f >= g * 2 ** (-1 / 6)) & (f <= g * 2 ** (1 / 6))
        h.append(10 * np.log10(pc[m].mean() / pxx[m].mean()))
    h = np.array(h)
    return h - np.median(h[(GRID >= 500) & (GRID <= 2000)])


def fit(h):
    target = np.clip(-h, -MAX_CUT, MAX_BOOST) * np.clip(np.log2(GRID / (LOW_LIMIT / 2)), 0, 1)
    basis = np.stack([resp_db(peaking(c, 6.0, Q), GRID) / 6.0 for c in CENTERS], 1)
    d = np.diff(np.eye(len(CENTERS)), axis=0)
    a = basis.T @ basis + RIDGE * np.eye(len(CENTERS)) + RIDGE * d.T @ d
    gains = np.zeros(len(CENTERS))
    for _ in range(4):
        cur = sum(resp_db(peaking(c, g, Q), GRID) for c, g in zip(CENTERS, gains))
        gains = np.clip(gains + np.linalg.solve(a, basis.T @ (target - cur)), -MAX_CUT, MAX_BOOST)
    return gains


def apply_eq(gains, x):
    for b, a in [highpass(HPF)] + [peaking(c, g, Q) for c, g in zip(CENTERS, gains) if abs(g) >= 0.1]:
        x = signal.lfilter(b, a, x)
    return x


def score(ref, y, label):
    ref, y, _ = align(ref, y)
    f, p1 = signal.welch(ref, SR, nperseg=2048)
    _, p2 = signal.welch(y, SR, nperseg=2048)
    bands = 125 * 2 ** (np.arange(0, 20) / 3)
    bands = bands[bands < 10000]
    d = np.array([10 * np.log10(p2[m].mean() / p1[m].mean())
                  for m in [(f > b / 1.12) & (f < b * 1.12) for b in bands]])
    d -= np.median(d)
    sp = (bands > 200) & (bands < 8000)
    _, _, s1 = signal.stft(ref, SR, nperseg=512)
    _, _, s2 = signal.stft(y, SR, nperseg=512)
    e1 = np.abs(s1) ** 2 + 1e-10
    e2 = np.abs(s2) ** 2 + 1e-10
    act = 10 * np.log10(e1.sum(0)) > 10 * np.log10(e1.sum(0)).max() - 40
    e2 *= e1[:, act].sum() / e2[:, act].sum()
    lsd = np.mean(np.sqrt(np.mean((10 * np.log10(e1[:, act]) - 10 * np.log10(e2[:, act])) ** 2, 0)))
    out = f"{label:28s} LTAS dev {np.std(d[sp]):5.2f} dB  LSD {lsd:5.2f}"
    try:
        from pesq import pesq
        from pystoi import stoi
        r16 = signal.resample_poly(ref, 2, 3)
        y16 = signal.resample_poly(y, 2, 3)
        y16 *= np.sqrt(np.mean(r16 ** 2) / np.mean(y16 ** 2))
        out += f"  PESQ-wb {pesq(16000, r16, y16, 'wb'):.2f}  STOI {stoi(r16, y16, 16000):.3f}"
    except ImportError:
        pass
    print(out)
    print("   third-octave deviation: " + " ".join(f"{b:.0f}:{v:+.1f}" for b, v in zip(bands, d)))


def main():
    cmd, files = sys.argv[1], sys.argv[2:]
    if cmd == "score":
        for p in files:
            score(*load(p), p.split("/")[-1])
    elif cmd == "response":
        h = response(files[0])
        for b in [100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500,
                  3150, 4000, 5000, 6300, 8000, 10000]:
            print(f"{b:6d} Hz {h[np.argmin(abs(GRID - b))]:+6.1f} dB")
    elif cmd == "fit":
        gains = fit(response(files[0]))
        print("gains " + " ".join(f"{c:.0f}:{g:+.1f}" for c, g in zip(CENTERS, gains)))
        for p in files[1:]:
            ref, rec = load(p)
            score(ref, rec, "raw")
            score(ref, apply_eq(gains, rec), "python EQ")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
