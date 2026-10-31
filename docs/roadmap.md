# Roadmap

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

Ideas and known gaps, roughly in priority order. Shipped work moves to [CHANGELOG.md](../CHANGELOG.md)
(the `dsm-docs` skill checks this list against the code at the end of every task).

- Text areas per app (a game's dialogue box and a video's subtitle band need different boxes;
  areas are per orientation only today).
- Neural VAD (Silero via ONNX Runtime, as GSM uses) for finding the voice in a clip. The energy
  trimmer behind *Trim silence* and the card editor's suggested range works on Nightshade
  microphone clips, but needs a stricter threshold and a gap rule to step over background music
  and later sounds, and clipped speech under rain/music in the YouTube test.
- RNNoise (as OBS uses) for the microphone fallback instead of the device's voice suppressor
  (a simple STFT Wiener filter on top of the platform suppressor measured no better).
- Vertical text for screen OCR (meikiocr ships a vertical recognizer; not wired up yet; Book mode
  reads vertical print and manga with ML Kit).
- Skip the detector on visual novels: a simulation (static background, typewriter text) showed
  reusing the last line boxes, grown rightwards for typewriter text and verified by the
  recognizer, cuts detector runs by ~65 % with identical lines; on video it saves nothing (the
  band changes everywhere every frame).
- Hotkey toggle for the live/lookup overlay (gamepad button or volume key via an accessibility
  service), as meikidroid does — handy on Android handhelds.
- Quick Settings tile to start/stop the overlay.
- Per-dictionary frequency display modes.
- Emulator capture E2E: MediaProjection frames go black once video plays on the emulator, so
  auto OCR is only validated on real devices. Find a working GPU/rendering setup or a
  frame-injection test path.
- Optional ASR engines (e.g. whisper.cpp or Moonshine via JNI) as an alternative/complement to OCR
  for games with hard-to-read fonts.
