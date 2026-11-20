# Roadmap

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Dictionaries](dictionaries.md) · [Roadmap](roadmap.md)

Ideas and known gaps, roughly in priority order. Shipped work moves to [CHANGELOG.md](../CHANGELOG.md)
(the `dsm-docs` skill checks this list against the code at the end of every task).

- AI explanations: models that only speak OpenAI's Responses API (some GPT models on gateways)
  aren't supported; the Claude/ChatGPT app hand-off and the direct Anthropic and OpenAI providers
  were only checked through the OpenCode gateway, not with their own keys (Gemini, OpenRouter and
  Ollama Cloud were checked with their own); Groq, Cerebras and Mistral are untested; Gemini (and
  other apps) can't be made to send the prompt by themselves, the user taps send; answers are
  English-only unless the prompt is edited.
- More study languages: a module is `lang/<code>/` + registry entry + recommended dictionaries
  (wty has Wiktionary dictionaries for dozens of languages, PP-OCRv6 reads 50). Spanish,
  Chinese and Korean are the obvious next ones; Chinese/Korean need their own segmentation.
- Make `build_pack.py` zips byte-reproducible (fixed timestamps), so a rebuilt pack keeps its SHA-256.
- German: separable verbs are only joined when the particle ends the clause and the joined verb is
  in the dictionary; compound splitting takes the longest known head (*Arbeitsplatzangebot* →
  Arbeit + Platzangebot); PaddleOCR's character set has no „ (low quote), so it's dropped.
- French: reflexive verbs whose dictionary has no "se …" headword (wty-fr-en lists *se lever*'s
  senses under *lever*) open the bare verb; the pronoun is a separate tap.
- English: book mode is untested on English print; no English frequency dictionary is offered;
  irregular forms rely on Wiktionary's form-of entries; the English OCR pack is only used for
  English (PaddleOCR on Japanese untested).
- Move the meikiocr graph cuts (`assets/meiki`, 3.6 MB, Japanese-only) into a Japanese language
  pack so English-only installs don't carry them (they're tied to the downloaded originals'
  SHA-256 anyway).
- Auto OCR keeps background text inside the area (a slide behind YouTube captions): a caption
  box / contrast filter for spaced languages would help.
- Text areas per app (a game's dialogue box and a video's subtitle band need different boxes;
  areas are per orientation only today).
- Neural VAD (Silero via ONNX Runtime, as GSM uses) for finding the voice in a clip. The energy
  trimmer behind *Trim silence* and the card editor's suggested range works on Nightshade
  microphone clips, but needs a stricter threshold and a gap rule to step over background music
  and later sounds, and clipped speech under rain/music in the YouTube test.
- RNNoise (as OBS uses) for the microphone fallback instead of the device's voice suppressor
  (a simple STFT Wiener filter on top of the platform suppressor measured no better).
- Vertical text for screen OCR and Book mode (both ML Kit). The manga reader's PP-OCRv6_manga +
  bubble detector reads tategaki well (84–88/99 bubbles vs ML Kit's 21) and could serve them;
  meikiocr's vertical recognizer (`meiki.text.rec.v0.vertical.32x480`) scored 53/99 on manga
  line crops (game-trained), so it is not the candidate.
- Manga reader: merge/split text boxes by hand (Manatan's merge anchor), and a per-page noise
  filter for sound effects outside speech bubbles (they are kept and tappable today).
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
