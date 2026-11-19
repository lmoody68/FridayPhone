# Activating the "Hey Friday" wake-words

Friday Phone already supports **Picovoice Porcupine** — true offline, low-power, always-on custom wake-words.
It activates automatically once you add three files to `app/src/main/assets/`. Until then the app falls back to
Android's SpeechRecognizer (gappy + battery), so nothing breaks in the meantime.

## One-time setup (~10 min, free)
1. Create a free account → https://console.picovoice.ai/
2. Copy your **AccessKey** (long string).
3. **Porcupine → Train** a wake word, **Platform = Android**, **twice**:
   - ANSWER phrase (e.g. **"Friday answer the call"**) → download → rename to **`friday_answer.ppn`**
   - HANG-UP phrase (e.g. **"Friday hang up now"**) → download → rename to **`friday_hangup.ppn`**
4. Put three files in `app/src/main/assets/`:
   - `picovoice_key.txt` — the AccessKey (one line, no quotes)
   - `friday_answer.ppn` — loads as **keyword index 0** → fires ANSWER
   - `friday_hangup.ppn` — loads as **keyword index 1** → fires HANG UP
5. Commit + push → CI rebuilds the APK with the wake-words baked in. Install, grant mic, Start listening.

## Notes
- The AccessKey is a **secret** — this repo is private (fine for personal use). For a public/SaaS build, inject it
  as a CI secret instead of committing it.
- Picovoice free tier covers personal use.
- Sensitivity is `0.6` for both keywords in `ListenerService.kt` — raise it if it misses you, lower it if it
  false-triggers.
- **Order matters:** `friday_answer.ppn` must be the ANSWER phrase (index 0) and `friday_hangup.ppn` the HANG-UP
  phrase (index 1), matching `setKeywordPaths(arrayOf(answerPath, hangupPath))`.
