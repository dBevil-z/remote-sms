#!/system/bin/sh
mkdir -p /data/local/tmp/sms-bridge
ps -A -o PID,ARGS 2>/dev/null | grep '[S]msBridge' | while read old_pid old_args; do
  kill "$old_pid" 2>/dev/null
done
nohup dalvikvm -cp /data/local/tmp/sms-bridge/sms-bridge.jar SmsBridge \
  >/data/local/tmp/sms-bridge/sms-bridge.log 2>&1 &
echo "sms bridge started"
