# Connect your Android phone from anywhere

1. Build the updated companion with `build_friday_apk.bat` (requires JDK 17+ and Android SDK 35), then install `friday-connect-android/app/build/outputs/apk/debug/app-debug.apk` on your Android phone. Android may ask you to allow installation from that source.
2. Start Friday on your PC. Open its device page, choose **Add Device**, then **Android**. This enables the gateway for future launches too.
3. Wait until the pairing screen shows **Global: https://…trycloudflare.com**. Friday starts Cloudflare Tunnel automatically; initial setup downloads cloudflared. A local IP alone works only on the same network.
4. Open Friday Connect on the phone and scan the desktop QR code. Alternatively, enter the Global URL and the six-digit code in manual pairing. Codes expire after five minutes; refresh if needed.
5. Grant the phone permissions you want to use. Enable Friday's Accessibility Service only if you want phone tap, swipe, and typing commands. Keep the companion's connection service running; allow its notifications and background operation.
6. Switch the phone to mobile data, keep Friday running on the PC, and try a chat message or battery command. The saved pairing credentials authenticate remote commands, files, and screenshot downloads.

The PC must stay awake and both devices must have internet. USB attachment alone does not pair the app. This companion is Android-only. A phone that is offline cannot be controlled.

The automatic tunnel uses a temporary address. After restarting Friday or the tunnel, pair again using the new QR code before leaving home. This implementation does not provide a permanent relay address or unattended recovery after a PC restart. If Global never appears, check internet/firewall access for cloudflared; local pairing still works on Wi-Fi. No router port forwarding is needed.

To remove access, revoke or forget the device in Friday's desktop device page. Install the updated APK with this gateway version: older APKs do not send the required authentication headers. Keep pairing codes and QR codes private.
