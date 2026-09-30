# Friday AI for Android

Friday is a native Android 12+ assistant with independent cloud AI and optional Windows integration. It opens without sign-in. The installed package remains `com.friday.connect` so existing installations can update when signed by the same certificate.

## Install and set up

1. Copy `dist/Friday-AI-Android-1.0.0.apk` to your phone. Open it and permit installation from your chosen file manager if Android asks.
2. Open **Friday AI → Settings**. Enter your own Gemini API key and save. The default model is `gemini-2.5-flash`; change it if your provider account requires another supported model. Optionally enter an OpenRouter key and model for fallback.
3. Start a conversation in **Chat**. The desktop can be off. Keys are encrypted with Android Keystore-backed encrypted preferences. Android backup is disabled. Provider requests consume your account's quota.
4. For hands-free mode, open **Voice → Enable hands-free** and grant microphone and notification permissions. The first start unpacks the bundled offline model. English/Hinglish and Hindi models are selected in Settings while voice is stopped.
5. For Windows tools, open **PC → Pair a PC**, then scan the Friday desktop pairing QR. Update the desktop checkout with this implementation and restart Friday so the task/attachment endpoints are available.

## Included capabilities

- Streaming Gemini chat and optional OpenRouter fallback before any response or action has begun.
- Local conversation history, attachment persistence, deletion and text export/sharing.
- Editable phone memory, separate from desktop memory. Phone memory is included in AI requests; it is not synchronized to the PC.
- Image, PDF, text and JSON attachments, up to three files at 5 MB each. Camera capture uses the phone camera app. OpenRouter supports images/text; PDFs require Gemini.
- Offline English/Hindi recognition; Friday, wake up Friday, utho Friday and Hindi-script wake matching. A 14-second activated/follow-up window, tap-to-talk, spoken replies, interruption, pause and stop.
- Phone battery/device diagnostics, app launching, volume, flashlight, approximate reminders, calendar and messaging drafts, media commands, file selection and sharing.
- Optional accessibility screen text and confirmed click/type/scroll actions; optional notification access. Password fields are redacted. Results are sent to the provider only after approval.
- A maximum of 12 actions and 8 AI rounds per task. PC workflows have a 12-step/60-second bound and a stop control. Nested workflows are rejected.
- Authenticated desktop task dispatch, capability discovery, selected attachment transfer, and existing PC file browsing/download controls.
- Coverage of all 57 desktop tool registrations is visible under **Tools**. Windows/Python-only capabilities, including coding agents and self-patching, run on the connected PC.

## Voice behavior

Wake recognition runs locally. AI replies still require internet and a configured provider. Examples that work without a provider include `battery status`, `flashlight on`, `volume 20 percent`, and `remind me in 10 minutes to stretch` (notification permission required). More flexible phrasing uses the cloud AI tool planner.

A microphone foreground-service notification stays visible. Pause stops audio recording; Stop shuts down the service. Android does not automatically restart voice after reboot. Open Friday and activate it again. Install English/Hindi Android TTS voices if your phone lacks them. Recognition accuracy, interruption during speaker playback, screen-off operation and OEM battery restrictions need testing on your phone. Small offline speech models are not a guarantee of perfect mixed-language recognition.

Notifications are collected in memory only while access is enabled; reading them requires confirmation. Accessibility must be explicitly enabled in Android Settings. Android does not allow Friday to bypass a PIN/biometric lock or silently change protected settings.

## Desktop connection and task outcomes

Remote connections require HTTPS/WSS and normal certificate verification. A private IPv4 LAN address may use HTTP/WS for existing local pairing; use the secure tunnel QR for encrypted access. Public cleartext URLs and credential-bearing URLs are rejected. HTTP clients do not automatically retry action requests or follow redirects. Offline desktop commands are not queued for later replay.

Desktop task IDs are durable and scoped to the paired device. Duplicate IDs return the existing status without re-executing. Queued tasks can be cancelled; dispatched Windows work cannot reliably be recalled from Android. The current desktop callback reports **dispatched**, not actual workflow completion. Check the desktop workspace for completion. The new API preserves this distinction rather than inventing success. Restarted uncertain tasks are marked **unknown** and never replayed.

Selected files are transferred only after a PC-task confirmation showing their names. The gateway stores them in its private mobile attachment directory and enforces device ownership and a 100 MB quota per device. Existing PC file browsing provides downloads of generated desktop artifacts. Phone memory is not automatically included in PC requests.

## Verification

See `ANDROID_VERIFICATION.md` for automated results and pending physical-phone scenarios. Live Gemini/OpenRouter requests require your keys and have not been tested with a user's account here.

## Rebuild

Run `build_friday_apk.bat`, or `powershell -File packaging/build_android.ps1` from the repository. The script verifies pinned voice-model checksums, generates the feature coverage asset, runs Android unit tests/lint and assembles a signed release APK plus SHA-256 file.

Build requirements: JDK 17, Android SDK 35/build-tools 35.0.0, project Python build environment, and internet for initial Gradle/model downloads. Voice model assets are generated and Git-ignored; `packaging/prepare_android_voice.py` reconstructs them. The personal release uses the existing development signing identity to preserve companion update compatibility. It is a sideload build, not a Play Store release. Keep the signing keystore for future updates.

Offline voice uses Vosk and the Apache-2.0 English/Hindi small models. See `app/src/main/assets/voice-models.json` and `THIRD_PARTY_NOTICES.md` for origins and checksums.
