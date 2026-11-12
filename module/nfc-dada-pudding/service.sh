#!/system/bin/sh
# late_start: verification only. v1.2 deliberately does NOT kill/restart the
# HAL or com.android.nfc here — doing that during boot caused an NfcService
# startup ANR on OS4.0.3.0 (see docs/OTA-OS4.0.3.0-incident.md).
MODDIR=${0%/*}
LOG=/data/local/tmp/nfc-dada-pudding.log
exec >> "$LOG" 2>&1
echo "=== $(date) service (verify only) ==="

PUDDING_SHA=22a9e85dfe459676f401fbc67cf9870176c7d9c583aefd9963701b2e112fbe4d
FW=/odm/firmware/st54l_fw.bin

i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do sleep 1; i=$((i + 1)); done
echo "boot_completed=$(getprop sys.boot_completed)"

if [ "$(sha256sum "$FW" 2>/dev/null | cut -d' ' -f1)" = "$PUDDING_SHA" ]; then
  echo "overlay active (post-fs-data)"
else
  echo "WARNING: overlay NOT active; stock NFC kept. Re-run action/scripts or check labels."
fi

echo "--- state ---"
dumpsys nfc 2>/dev/null | head -4
pidof android.hardware.nfc-service-st com.android.nfc
echo "=== done ==="
