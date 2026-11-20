Friday Phone — assets/

The offline wake-word engine is Vosk. The English model lives at:

    app/src/main/assets/model-en-us/

You normally DO NOT put it here by hand — the GitHub Actions build fetches and unpacks
vosk-model-small-en-us-0.15 (~40 MB) into that folder automatically before compiling
(see .github/workflows/android-build.yml). The folder is in .gitignore so the binary
blob never bloats the repo.

To build LOCALLY (Android Studio / gradle on your machine), fetch it once yourself:

    curl -L -o model.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
    unzip model.zip
    mv vosk-model-small-en-us-0.15 app/src/main/assets/model-en-us

If the model folder is absent, the app still builds and runs — it falls back to Android's
on-device SpeechRecognizer (which, unlike Vosk, cannot hear during an incoming ring).
