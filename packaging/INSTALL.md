# Friday AI 1.0.1 for Windows x64

Close Friday and run `Friday-Setup-1.0.1-x64.exe`. Installation is per-user and requires no Python or Node.js installation. Use the FRIDAY desktop shortcut to launch.

Say “Friday”, “wake up Friday”, or “utho Friday”, then give your command. Push-to-talk is optional in Audio settings. Confirm the selected microphone in those settings. Voice recognition requires an internet connection and a Gemini key; the installer contains no API keys or personal data.

Application files live in `%LOCALAPPDATA%\Programs\FridayAI`. Your settings, memory, logs and credentials live separately in `%LOCALAPPDATA%\FridayAI` and survive upgrades and uninstall. Local dashboard access uses port 8000 on loopback; network remote access is disabled by default.

This personal build is unsigned. Source self-patching is disabled for installed releases. Upgrade by closing Friday and installing a newer setup executable. Do not distribute your user-data folder.

## Rebuild

Use Python 3.12 x64 in a clean `.build-venv`; install `packaging/requirements-win.lock`. Use Node 22.18.0 x64 and `npm ci --prefix packaging` with the committed package lock. Copy node.exe into `packaging/runtime/`; set PLAYWRIGHT_BROWSERS_PATH to `packaging/runtime/browsers` and run `node packaging/node_modules/playwright/cli.js install chromium`. Run `packaging/build_release.ps1` using Inno Setup 6. Keep runtime downloads and generated artifacts out of Git.
