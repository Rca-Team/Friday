# Android verification checklist

Verification date: 1 October 2026 (Asia/Calcutta).

## Automated checks

- 19 Python tests passed for the gateway and Friday Connect: authentication, pairing, revocation, device-scoped task/attachment access, duplicate task suppression, cancellation before dispatch, restart uncertainty, file validation and attachment transfer.
- 12 Android tests passed: 7 JVM/provider tests and 5 Robolectric tests on simulated Android 12. They cover English/Hindi wake matching, secure remote URL rules, streaming function-call/signature handling, key rejection without secret leakage, network cancellation, OpenRouter tool arguments, conversation save/reopen/delete, attachment cleanup, persistent command replay protection, reminder persistence/cancellation and offline command parsing.
- Android debug lint: zero errors; existing dependency/deprecation and compatibility warnings remain. No lint baseline was added to hide errors.
- Release APK assembly and signing/content verification: see `dist/Friday-AI-Android-1.0.0-verification.json` after packaging.

## Pending physical-phone and live-account checks

No phone or emulator is connected/installed in this environment. The simulated tests do not prove microphone or real UI behavior.

- Install the APK and open all five tabs; inspect portrait/landscape, keyboard, navigation, chat rendering and permission dialogs.
- Upgrade over the current Friday Connect installation and verify pairing credentials remain intact. Package/version/signature metadata can be checked here; the installation lifecycle requires a phone.
- With the PC off, enter your provider key and verify a real streamed answer, Hindi/Hinglish response, image/PDF analysis, invalid key, quota failure and OpenRouter fallback.
- Enable hands-free while the app is visible; say Friday, wake up Friday, utho Friday, iutho firday and Hindi-script equivalents with the matching voice model. Test noisy rooms and mixed-language speech.
- Test spoken replies and interruption during speaker output; test pause, stop, screen-off listening, switching audio devices, a phone call/another recorder, revoked microphone permission, service removal and restart.
- Reboot: reminders should reschedule; voice must stay off until you explicitly start it from Friday.
- Inspect battery use over a sustained session and test the phone manufacturer's background restrictions. Android 15 data-sync timeout should stop the PC connection cleanly and require reopening/reconnection.
- Enable accessibility and notification access separately. Read a screen with a password field and confirm it is redacted; deny approval and verify no provider transfer. Restore the intended target app after approving a click/type action. Stop a multi-step workflow.
- Pair with the updated desktop. Send a confirmed PC task and selected attachments, retrieve generated files through the existing PC file browser, and inspect disconnected/uncertain outcomes. Check the desktop for actual completion of dispatched work.

## Known operational boundaries

Cloud AI needs internet and your provider quota. Small offline speech models have imperfect accuracy and use additional memory/storage. Calendar and messaging tools open drafts for your review; they do not silently send or save them. Reminders use approximate Android alarms. Screen understanding uses selected images/screenshots or approved accessibility text; protected screens and phone unlocking are unavailable.

Android and PC actions are bounded and individually approved where needed. Cancelling a task does not reverse an action already performed, and a dispatched Windows task cannot be guaranteed cancelled by the phone. Desktop completion is reported honestly as dispatch status until the desktop callback provides an actual completion result.

The APK uses a release build configuration with the existing development signing certificate for personal sideload/update compatibility. Live device installation and store distribution are not verified.
