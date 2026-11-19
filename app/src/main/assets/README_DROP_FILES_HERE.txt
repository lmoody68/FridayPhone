Drop three files in THIS folder to activate the offline "Hey Friday" wake-words, then push to rebuild:

  1. picovoice_key.txt   - your Picovoice AccessKey (one line, no quotes)
  2. friday_answer.ppn   - trained wake word for ANSWER  (e.g. "Friday answer the call")  -> index 0
  3. friday_hangup.ppn   - trained wake word for HANG UP (e.g. "Friday hang up now")       -> index 1

Until these exist, the app falls back to Android's on-device speech recognizer so the pipeline still works
(just gappier + more battery). See ../../../../PORCUPINE_SETUP.md.
