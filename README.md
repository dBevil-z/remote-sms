# Remote SMS

**Language:** English | [简体中文](README.zh-CN.md)

Self-hosted SMS inbox and sender for an Android phone that stays at home. Remote SMS lets one phone receive, store, and send SMS messages, while any trusted browser can read messages, send from a selected SIM card, and monitor the phone's health.

## Why It Exists

Many people keep a spare phone only for SMS: bank verification codes, work numbers, platform accounts, regional numbers, physical SIM cards that cannot move to eSIM, or dual-SIM and multi-SIM setups. Remote SMS lets that phone stay in one safe place instead of traveling with you.

Useful when you want to:

- Stop carrying several phones just to receive verification codes.
- Keep physical SIM cards available when eSIM is unsupported or unavailable.
- Manage dual-SIM or multi-SIM phones and choose which SIM sends a message.
- Keep backup numbers, work numbers, and sign-up numbers in one fixed place.
- Access regional, low-frequency, IoT-related, or data-plan-linked numbers remotely.
- Read and send SMS from another phone, tablet, laptop, or desktop browser.
- Self-host SMS access through your own LAN, router, frp tunnel, reverse proxy, or VPN.

## Highlights

| Feature | Description |
| --- | --- |
| Phone as the server | The Android app includes an embedded web server on port `8787`. |
| Remote SMS inbox and sender | Read incoming messages and submit outgoing messages from the web UI. |
| Multi-SIM support | Select a specific SIM card when sending SMS. |
| Device dashboard | View battery, memory, storage, network, uptime, SMS count, SIM information, and send-bridge status. |
| Mobile-friendly UI | Compact layout works well from another phone. |
| Password-protected access | Protected APIs require a Bearer Token, configurable from the app or web settings. |
| No hardcoded tunnel secrets | frp URL, server address, ports, auth token, and web password are configured at runtime. |
| Outgoing message history | Sent messages are recorded locally with sending, sent, delivered, or failed status. |
| Smartisan compatibility path | Includes a local shell bridge for Smartisan Pro 2S, where app-level `SmsManager` may be silently blocked. |
| Self-hosted by design | SMS data stays on the phone, and you control the access path. |

## Screenshots

Device IDs, phone numbers, and message-list details are masked where needed.

### Android App

![Android app home screen](docs/assets/android-app-home.png)

### Web Console

![Web status dashboard](docs/assets/web-dashboard.png)

![Web SMS list](docs/assets/web-sms-list.png)

![Web send SMS dialog](docs/assets/web-send-sms.png)

![Web access settings dialog](docs/assets/web-access-settings.png)

## Repository Layout

```text
android/                 Android app and embedded web server
tools/sms-bridge/        Shell-side SMS bridge for Smartisan OS edge cases
tools/start-phone-services.sh
server/                  Legacy Node.js web/API server for optional desktop testing
docs/assets/             README screenshots
```

## How It Works

1. Install and open the Android app on the phone.
2. The app starts an embedded web server on port `8787`.
3. Incoming SMS messages are saved locally on the phone.
4. A trusted browser connects to the phone and reads messages after password authentication.
5. To send SMS, choose a SIM card in the web UI, enter the recipient and message body, then submit.
6. The web UI also shows device health and service status.

## Quick Start

For USB testing:

```sh
adb forward tcp:8787 tcp:8787
```

Then open:

```text
http://127.0.0.1:8787
```

For LAN or tunnel access:

```text
http://<phone-ip>:8787
```

You can also publish it through your own frp endpoint, public domain, reverse proxy, or VPN.

## Configuration

No frp address, frp token, or web access password is hardcoded in the app.

Open the Android app and use **访问设置** to configure:

- Web access password
- Public frp URL
- frp server address
- frp server port
- frp remote port
- frp auth token

The values are stored only in Android `SharedPreferences` on the phone.

## Smartisan SMS Sending Bridge

On the tested Smartisan Pro 2S, normal `SmsManager` calls from an app UID are accepted but do not reach the radio layer. The workaround is a local bridge started by `adb shell`, which submits SMS through Android's `isms` service.

Build and push the bridge:

```sh
javac -source 8 -target 8 \
  -bootclasspath .tools/android-sdk/platforms/android-35/android.jar \
  -d /tmp/sms-bridge-build/classes \
  tools/sms-bridge/SmsBridge.java

.tools/android-sdk/build-tools/35.0.0/d8 \
  --min-api 23 \
  --output /tmp/sms-bridge-build/dex \
  /tmp/sms-bridge-build/classes/SmsBridge*.class

cd /tmp/sms-bridge-build/dex
zip -q /tmp/sms-bridge.jar classes.dex

adb shell 'mkdir -p /data/local/tmp/sms-bridge'
adb push /tmp/sms-bridge.jar /data/local/tmp/sms-bridge/sms-bridge.jar
adb push tools/start-phone-services.sh /data/local/tmp/sms-bridge/start-phone-services.sh
adb shell 'chmod 755 /data/local/tmp/sms-bridge/start-phone-services.sh'
```

Start it after a reboot:

```sh
adb shell /data/local/tmp/sms-bridge/start-phone-services.sh
```

The app checks `127.0.0.1:8790/health` and shows whether the bridge is connected.

## API

All protected endpoints require:

```text
Authorization: Bearer <web-access-password>
```

Endpoints:

- `GET /api/messages?limit=10&offset=0`
- `GET /api/sims`
- `POST /api/send`
- `GET /api/config`
- `POST /api/config`
- `GET /api/device`
- `GET /health`

## Security Notes

- Do not expose port `8787` directly to the public internet without additional protection.
- Use frp, a reverse proxy, HTTPS, access control, or VPN where appropriate.
- Use a strong web access password.
- Do not commit frp credentials or web passwords to Git.
- For important verification-code accounts, restrict access to trusted devices and networks.
- Keep the phone charged, connected, and within carrier SMS limits.
