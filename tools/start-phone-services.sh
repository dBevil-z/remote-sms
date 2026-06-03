#!/system/bin/sh
mkdir -p /data/local/tmp/sms-bridge
for old_pid in $(pidof dalvikvm 2>/dev/null); do
  kill "$old_pid" 2>/dev/null
done
nohup dalvikvm -cp /data/local/tmp/sms-bridge/sms-bridge.jar SmsBridge \
  >/data/local/tmp/sms-bridge/sms-bridge.log 2>&1 &
echo "sms bridge started"
