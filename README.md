# Friday Phone — hands-free call control ("the ears")

Say **"Friday answer the call"** → your phone answers **on speaker**.
**Shake the phone** → it ends the call.

This app is deliberately tiny: it's an always-listening **Vosk** (offline, no account) wake-word service
that, on a hit, fires a local broadcast to **MacroDroid**, which runs the proven **answer+speaker /
end-call** macros. The telephony stays in MacroDroid (already working on the Galaxy A15); this app only
listens and pokes it.

> Built from the **North Star** scaffold (same guardian-service + cloud-build pattern). We moved the
> wake-word engine from Picovoice Porcupine to **Vosk** because Porcupine's free tier now requires manual
> approval of a *commercial* use case — a dead end for a personal project.

## How the pieces fit
```
"Friday answer the call"  ─▶ Vosk (offline) ─▶ broadcast com.macvision.fridayphone.ANSWER ─▶ MacroDroid ▶ Answer+Speaker macro
shake the phone           ─▶ MacroDroid Shake trigger ──────────────────────────────────────────────── ▶ End-Call macro
```

**Why shake to hang up?** Android does not let *any* third-party app read the mic during an **active**
call (telephony owns it), so voice hang-up mid-call is impossible on every engine. Shake is a sensor (no
mic) and works mid-call. Voice "Friday hang up" still works when you are *not* already on a call.

## Get the APK (no Android Studio)
Built in the cloud via **GitHub Actions** (the workflow also fetches the ~40 MB Vosk model for you):
1. **Actions** tab → latest **"Build Friday Phone APK"** → **Artifacts** → `fridayphone-debug-apk` → unzip → `app-debug.apk`.
2. On the phone: allow install from this source, open the APK.

## First run
1. Grant **Microphone** and **Notifications**.
2. Tap **Keep Friday alive (battery)** → set unrestricted (Samsung kills background mic apps otherwise).
3. Tap **Start listening**.
4. In **MacroDroid**, make sure each macro is wired:
   - Answer+Speaker macro → *Intent Received* `com.macvision.fridayphone.ANSWER`
   - End-Call macro → *Intent Received* `com.macvision.fridayphone.HANGUP` **and** *Shake Device*

## Wake-word engine
Offline/low-power via **Vosk** — see `VOSK_SETUP.md`. The English model is fetched by CI into
`app/src/main/assets/model-en-us/` (gitignored). If it's absent, the app falls back to Android's on-device
recognizer so the intent wiring is still testable (that fallback can't hear during a ring).

## Known limits (honest)
- On a 4 GB phone, keep it **battery-unrestricted** or Samsung kills the listener.
- A mic foreground service started from **boot** may not get mic access until the app is opened once
  (Android background-mic restriction). v1: reopen after reboot. Hardening tracked for later.
- Voice hang-up only works when not already connected — shake is the mid-call hang-up (see above).

## Layout
- `ListenerService.kt` — mic foreground service + Vosk keyword grammar + fallback recognizer → broadcasts.
- `MainActivity.kt` — start/stop, mic permission, battery-exemption, diagnostic Test buttons.
- `BootReceiver.kt` / `Prefs.kt` — re-arm after reboot if it was on.
- `.github/workflows/android-build.yml` — fetch Vosk model → cloud build → APK artifact.
