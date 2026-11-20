# Friday Phone — wake-word engine (Vosk)

Friday Phone listens offline for a wake phrase and fires an intent that MacroDroid turns into
"answer on speaker" / "end call". The listener uses **[Vosk](https://alphacephei.com/vosk/)** —
Apache-2.0, fully offline, **no account and no approval** (this is why we moved off Picovoice
Porcupine, whose free tier now requires manual approval of a *commercial* use case).

## How it fits together

```
you speak  ->  Friday Phone app (Vosk, offline)  ->  broadcast intent  ->  MacroDroid  ->  telephony
 "Friday answer the call"      the "ears"            ANSWER / HANGUP      the "hands"    answer+speaker
```

- **Answer:** say **"Friday answer the call"** while the phone is ringing. The mic is free during a
  ring, so Vosk hears it and broadcasts `com.macvision.fridayphone.ANSWER`.
- **Hang up:** **shake the phone.** Android does **not** let any third-party app read the mic during an
  *active* call (telephony owns it), so voice hang-up mid-call is impossible on any engine. Shake is a
  sensor (no mic) and works mid-call — it's a MacroDroid **Shake** trigger on the End-Call macro.
  (Voice "Friday hang up" still works when you are *not* already on a call.)

When a call connects the mic goes busy and Vosk reports an error; the service just re-arms every ~2.5 s
so it's listening again the instant the call ends.

## The model

The small English model (`vosk-model-small-en-us-0.15`, ~40 MB) is **fetched by CI**, not committed:

- `.github/workflows/android-build.yml` downloads + unzips it into `app/src/main/assets/model-en-us/`
  before `gradle assembleDebug`.
- `.gitignore` excludes that folder so the binary never bloats the repo.
- For a **local** build, fetch it once yourself (see `app/src/main/assets/README_DROP_FILES_HERE.txt`).

If the model is absent the app still builds/runs, falling back to Android's on-device SpeechRecognizer
(which cannot hear during a ring — so it's only for bench-testing the intent wiring).

## Tuning

- Recognized phrases live in `ListenerService.GRAMMAR`. The tight grammar (only our phrases + `[unk]`)
  is what keeps spotting fast and false-positives rare. Add variants there if you want more ways to say it.
- `COOLDOWN_MS` (3 s) stops one spoken phrase from firing repeatedly across partial frames.
- Want higher accuracy and don't mind a bigger APK? Swap the model URL in the workflow for a larger
  Vosk EN model and keep the asset folder name `model-en-us`.

## MacroDroid side (unchanged, already proven)

- **Friday - Answer + Speaker:** trigger *Intent Received* `com.macvision.fridayphone.ANSWER` ->
  Answer Call -> Wait 2 s -> UI Interaction: Click (Text) "Speaker".
- **Friday - End Call:** triggers *Intent Received* `com.macvision.fridayphone.HANGUP` **and** *Shake
  Device* -> Call Reject.
- MacroDroid needs both accessibility services on ("MacroDroid" + "MacroDroid UI Interaction"), the Phone
  permission, and battery optimisation disabled.
