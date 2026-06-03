# Remote SMS

Self-hosted Android SMS inbox and sender for a phone that stays at home.

## Layout

- `android/`: Android app. It stores incoming SMS locally, serves the web UI on port `8787`, and can submit outgoing SMS.
- `tools/sms-bridge/`: A small shell-side bridge used on Smartisan OS, where normal app SMS sending is silently blocked.
- `server/`: Legacy Node.js web/API server kept for optional desktop testing.

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

## Phone-Only Mode

The Android app includes an embedded web server:

```text
http://<phone-ip>:8787
```

For USB testing:

```sh
adb forward tcp:8787 tcp:8787
```

Then open:

```text
http://127.0.0.1:8787
```

Enter the web access password configured in the app.

## Smartisan SMS Sending Bridge

On the tested Smartisan Pro 2S, normal `SmsManager` calls from an app UID are accepted but do not reach radio. The workaround is a local bridge started by adb shell, which submits SMS through Android's `isms` service.

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
- `GET /health`
