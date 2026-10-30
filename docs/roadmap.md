# Roadmap

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

Ideas and known gaps, roughly in priority order. Shipped work moves to [CHANGELOG.md](../CHANGELOG.md).

- Text areas per app (a game's dialogue box and a video's subtitle band need different boxes;
  areas are per orientation only today).
- RNNoise (as OBS uses) for the microphone fallback instead of the device's voice suppressor
  (a simple STFT Wiener filter on top of the platform suppressor measured no better).
- Deinflection with Yomitan's part-of-speech conditions: 象徴ではある currently highlights as one
  "te-form" match of 象徴.
- Faster mining: a card takes ~11 s on the Xiaomi Pad 7 (word audio download + AAC encode).

- Neural VAD (Silero via ONNX Runtime, as GSM uses) for silence trimming that holds up under
  loud BGM; the current energy trimmer is opt-in because it clipped speech under rain/music in
  the YouTube test.
- Emulator capture E2E: MediaProjection frames go black once video plays on the emulator, so
  auto OCR is only validated on real devices. Find a working GPU/rendering setup or a
  frame-injection test path.
- AnkiDroid media naming: the API stores our AAC clip as `…m4a_<n>.mp3`. It plays, but pass the
  proper MIME type/extension so the file name matches the content.
- Vertical text for screen OCR (meikiocr ships a vertical recognizer; not wired up yet; Book mode
  reads vertical print and manga with ML Kit).
- Book mode: share a photo straight from the camera/gallery into DSM.
- Pitch accent in the lookup popup (the card already gets the graph); jidoujisho-style graph option.
- Hotkey toggle for the live/lookup overlay (gamepad button or volume key via an accessibility
  service), as meikidroid does — handy on Android handhelds.
- Skip the detector on visual novels: a simulation (static background, typewriter text) showed
  reusing the last line boxes, grown rightwards for typewriter text and verified by the
  recognizer, cuts detector runs by ~65 % with identical lines; on video it saves nothing (the
  band changes everywhere every frame).
- Optional ASR engines (e.g. whisper.cpp or Moonshine via JNI) as an alternative/complement to OCR
  for games with hard-to-read fonts.
- Per-dictionary frequency display modes.
- Quick Settings tile and share-sheet mining.
