---
name: dsm-audio
description: Measure and tune Droid Sentence Miner's sentence-audio path, especially the microphone fallback FX chain (noise suppression → high-pass → calibrated speaker-correction EQ → compressor → normalize). Use when changing anything in audio/ (MicEq, MicFxChain, MicCalibration, AudioCompressor, AudioPcm.normalize), when a user says mic clips sound tinny/muffled/noisy, or to re-measure a device's speaker → mic response.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Measuring the microphone fallback

The fallback (DroidDeck/Nightshade, Netflix) records the game *through the device's own speaker*.
Don't tune it by ear or by eyeballing waveforms: play a known signal through the speaker, record it
back exactly like the fallback, and score it against the original.

## The chain (`audio/MicFxChain.kt`, run per 2048-sample chunk in `AudioCaptureManager.micLoop`)

1. **Platform `NoiseSuppressor`** on the recorder session (pref `audioNoiseSuppression`). Source is
   `UNPROCESSED` when the device has it, else `VOICE_RECOGNITION` (`AudioCaptureManager.micSource`).
   Pad 7: room noise floor −49.6 → −60.9 dBFS with it. Essential.
2. **High-pass 120 Hz** (Butterworth, `MicEq.HIGH_PASS_HZ`) — whenever the EQ pref is on, even
   uncalibrated. Below ~130 Hz the speaker plays nothing; the mic only has rumble/fan there
   (SNR < 10 dB at 125 Hz).
3. **Speaker correction EQ** — 19 third-octave peaking filters 160 Hz–10 kHz, Q 3, gains from the
   device's calibration (`MicEqProfile`, pref `audioMicEqProfile`, only used when its `model` ==
   `Build.MANUFACTURER + MODEL`). Boost ≤ +6 dB, cut ≤ −20 dB, correction faded out under 170 Hz.
4. **Compressor** (`AudioCompressor`, −30 dBFS, 4:1, 5/150 ms) — after the EQ so the harsh treble
   peak doesn't drive it.
5. At mining: `AudioPcm.normalize` (−14 dBFS voiced RMS, soft limiter) in `TextLog.clip`.

Cost on the Pad 7 (`/proc/<pid>/task/<dsm-audio-mic>/stat`, Nightshade playing): mic thread
1.87 % of one core with the chain, 0.57 % without. The chain only runs while the mic is in use (or
500 ms before switching, `CHAIN_WARMUP_MS`), so it is free while device audio works.

## Calibration (`audio/MicCalibration.kt`)

Settings → Sentence audio → Microphone fallback → Speaker correction EQ → **Calibrate**, or
`curl localhost:9099/mic_calibrate` (`&save=0` to only measure). Plays 6 s of pink noise
(−18 dBFS RMS) as `USAGE_GAME`, records the mic with the fallback's source + suppressor, takes the
Welch PSD ratio (mic noise from the 0.5 s lead-in subtracted), smooths to 1/3 octave, normalizes to
the 500 Hz–2 kHz median, then `MicEq.fit` (ridge least squares, re-linearized 4×). Refuses when the
mid-band SNR is < 15 dB (volume too low / other audio playing). Needs DSM in the foreground (mic).

Why pink noise: on the Pad 7 its response matched the speech-coherent response within ~1 dB in
every band at 3 levels (0.4/0.8/1.6 amp — the path is linear); a log sine sweep overstated 4–5 kHz
by ~4 dB and understated the bass dip.

## Measured: Xiaomi Pad 7 speaker → mic (pink noise, NS on, vs 1 kHz)

| 125 | 160 | 200 | 250 | 400 | 630 | 1.6k | 2k | 2.5k | 4k | 5k | 6.3k | 8k | 10k |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| −22 | −17 | −13 | −11 | −5 | −4 | +2 | −6 | −13 | +6 | +6 | **+15** | +10 | +2 |

That is the "tinny" sound: no bass, a null at 2.5 kHz, a huge 6.3 kHz resonance.
Output+input latency 150–210 ms (cross-correlation lag).

## Scoring recipe (debug build, device unlocked, DSM in the foreground)

```bash
export ANDROID_SERIAL=c2bd6564; adb forward tcp:9099 tcp:9099
adb shell am start -n io.github.wilsonnet.droidsentenceminer.debug/io.github.wilsonnet.droidsentenceminer.MainActivity
# Clean Japanese speech reference: 30 Forvo clips concatenated, mono 24 kHz 16-bit WAV
ffmpeg -f concat -safe 0 -i list.txt -ar 24000 -ac 1 speech.wav
adb push speech.wav /data/local/tmp/measure.wav
adb shell "run-as io.github.wilsonnet.droidsentenceminer.debug sh -c 'cat /data/local/tmp/measure.wav > files/measure.wav'"
curl -s "localhost:9099/mic_measure.wav?signal=pink&sec=8&amp=0.8&ns=1" -o pink.wav
curl -s "localhost:9099/mic_measure.wav?signal=file&amp=0.7&ns=1" -o raw.wav          # mic only
curl -s "localhost:9099/mic_measure.wav?signal=file&amp=0.7&ns=1&fx=1" -o app.wav     # + app chain
python3 tools/mic-eval/mic_eval.py score raw.wav app.wav
python3 tools/mic-eval/mic_eval.py fit pink.wav raw.wav   # Python port of the fit, to try params
```

`/mic_measure.wav` params: `signal=sweep|noise|pink|file`, `sec`, `amp` (peak; pink RMS ≈ amp/4),
`ns=1` (NoiseSuppressor), `file=` (in app `files/`, default `measure.wav`), `fx=1` (right channel
through the *currently configured* `MicFxChain`, i.e. prefs + calibration). Output: stereo WAV,
left = played, right = recorded. `/mic_fx?eq=&compressor=&ns=&reset=1` reads/sets the chain prefs.
PESQ/STOI: `python3 -m venv --system-site-packages v && v/bin/pip install pesq pystoi` (~5 min build).

## Results (Pad 7, Forvo speech, NS on)

| | LTAS dev 200–8k | PESQ-wb | STOI |
|---|---|---|---|
| raw mic | 7.36 dB | 1.85 | 0.820 |
| high-pass only | 7.37 | 1.92 | 0.819 |
| fitted EQ (Python) | 2.13 | 2.21 | 0.821 |

On-device chain, a second run (`fx=1`, quieter room):

| | LTAS dev | PESQ-wb | STOI |
|---|---|---|---|
| raw mic | 7.13 | 2.14 | 0.867 |
| old chain (NS + compressor) | 6.89 | 2.14 | 0.868 |
| EQ only | 1.81 | 2.31 | 0.855 |
| **EQ + compressor (default)** | **1.78** | **2.60** | **0.881** |

Kotlin calibration vs the Python fit of an earlier pink recording: every band within 2 dB
(6.4 kHz −17.7 vs −17.6). Real Nightshade clips: the 6.3 kHz band sits at −1…+3 dB vs clean
speech (raw mic: +16…+20).

Tried and rejected (no gain, measured): extra STFT Wiener noise reduction on top of the platform
suppressor (PESQ same, STOI −0.03); a downward expander (no change: NS already gates pauses);
bigger boost caps (+9/+12 dB: flatter spectrum but PESQ 2.0/1.94 — boosting where the speaker
is weak lifts room noise and reverb); half-octave bank (can't reach the narrow 6.3 kHz peak:
residual +8.6 dB). What's left (PESQ ~2.2) is room reverb + speaker distortion — not EQ-able.
