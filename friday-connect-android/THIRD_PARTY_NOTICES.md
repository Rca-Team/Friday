# Friday Android third-party notices

Offline speech recognition: Vosk API, Apache License 2.0.
Project: https://github.com/alphacep/vosk-api
License: https://github.com/alphacep/vosk-api/blob/master/COPYING
Pinned Maven dependency: com.alphacephei:vosk-android:0.3.75

Bundled speech models (Apache License 2.0):
- vosk-model-small-en-us-0.15, https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
- vosk-model-small-hi-0.22, https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip

Model SHA-256 values are in app/src/main/assets/voice-models.json and verified by packaging/prepare_android_voice.py. Model files are unmodified; their README files are retained in the APK assets. Build assets include an Apache-2.0 license copy in third-party/apache-2.0.txt.

AndroidX, Material Components, Kotlin and OkHttp are included through their respective declared Gradle dependencies and retain their upstream licensing.
