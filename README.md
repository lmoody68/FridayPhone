# Friday Phone — hands-free call control ("the ears")

Say **"Friday answer the call"** → your phone answers **on speaker**.
Say **"Friday hang up now"** → it ends the call.

This app is deliberately tiny: it's an always-listening **Porcupine** wake-word service that, on a hit, fires a
local broadcast to **MacroDroid**, which runs the proven **answer+speaker / end-call** macros. The telephony
stays in MacroDroid (already working on the Galaxy A15); this app only listens and pokes it.

> Built from the **North Star** scaffold (same Porcupine + guardian-service + cloud-build pattern).

## How the pieces fit
```
"Friday answer the call"  ─▶ Porcupine (index 0) ─▶ broadcast com.macvision.fridayphone.ANSWER ─▶ MacroDroid ▶ Answer+Speaker macro
"Friday hang up now"      ─▶ Porcupine (index 1) ─▶ broadcast com.macvision.fridayphone.HANGUP ─▶ MacroDroid ▶ End-Call macro
```

## Get the APK (no Android Studio)
Built in the cloud via **GitHub Actions**:
1. **Actions** tab → latest **"Build Friday Phone APK"** → **Artifacts** → `fridayphone-debug-apk` → unzip → `app-debug.apk`.
2. On the phone: allow install from this source, open the APK.

## First run
1. Grant **Microphone** and **Notifications**.
2. Tap **Keep Friday alive (battery)** → set unrestricted (Samsung kills background mic apps otherwise).
3. Tap **Start listening**.
4. In **MacroDroid**, change each macro's trigger from *Shake Device* → **Intent Received**:
   - Answer+Speaker macro → action `com.macvision.fridayphone.ANSWER`
   - End-Call macro → action `com.macvision.fridayphone.HANGUP`

## Wake-words
Offline/low-power via **Picovoice Porcupine** — see `PORCUPINE_SETUP.md`. Until the `.ppn` files are added, it
falls back to Android's on-device recognizer so the pipeline is still testable.

## Known limits (honest)
- On a 4 GB phone, keep it **battery-unrestricted** or Samsung kills the listener.
- A mic foreground service started from **boot** may not get mic access until the app is opened once
  (Android background-mic restriction). v1: reopen after reboot. Hardening tracked for v2.

## Layout
- `ListenerService.kt` — mic foreground service + Porcupine (2 keywords) + fallback recognizer → broadcasts.
- `MainActivity.kt` — start/stop, mic permission, battery-exemption.
- `BootReceiver.kt` / `Prefs.kt` — re-arm after reboot if it was on.
- `.github/workflows/android-build.yml` — cloud build → APK artifact.
