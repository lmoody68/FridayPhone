# Debug builds don't minify. Keep Porcupine's native bindings if you ever enable R8 for release.
-keep class ai.picovoice.porcupine.** { *; }
