#!/system/bin/sh
# re-apply the Xiaomi 17 (pudding OS3) ST54L NFC stack overlay on Xiaomi 15 (dada)
# after a reboot. Runtime-only: no partition is written; remove with nfc-overlay-remove.sh.
# Requires: /data/local/tmp/nfcmod/{nfc-service-st,nfc_nci.st21nfc.st.so,st54l_fw.bin,libnfc-hal-st.conf,st54l_conf.txt}
#           /data/local/tmp/q13.jar
set -u
LOG=/data/local/tmp/nfc-overlay.log
exec >> "$LOG" 2>&1
echo "=== $(date) apply ==="

SRC=/data/local/tmp/nfcmod
TMP=/mnt/nfcmod

[ -x /system/bin/sh ] || exit 1
for f in nfc-service-st nfc_nci.st21nfc.st.so st54l_fw.bin libnfc-hal-st.conf st54l_conf.txt; do
  [ -f "$SRC/$f" ] || { echo "missing $SRC/$f"; exit 2; }
done

if mount | grep -q " $TMP "; then echo "already mounted"; else
  mkdir -p "$TMP" || exit 3
  mount -t tmpfs -o size=64m,mode=0755,suid,dev tmpfs "$TMP" || { echo "tmpfs failed"; exit 4; }
fi

put() { cp -f "$1" "$2" || return 1; chmod "$3" "$2"; chcon "$4" "$2" || echo "chcon failed: $4 $2"; }

put "$SRC/nfc-service-st"      "$TMP/service" 755 u:object_r:hal_nfc_default_exec:s0
put "$SRC/nfc_nci.st21nfc.st.so" "$TMP/hal.so" 644 u:object_r:vendor_file:s0
put "$SRC/st54l_fw.bin"        "$TMP/fw.bin"  644 u:object_r:vendor_file:s0
put "$SRC/libnfc-hal-st.conf"  "$TMP/hal.conf" 644 u:object_r:vendor_configs_file:s0
put "$SRC/st54l_conf.txt"      "$TMP/st54l.txt" 644 u:object_r:vendor_configs_file:s0

echo "--- labels ---"
ls -lZ "$TMP"
# refuse to mount if the HAL service label is wrong (this is what broke a previous module boot)
SVC_LABEL=$(ls -Z "$TMP/service" | awk '{print $5}')
if [ "$SVC_LABEL" != "u:object_r:hal_nfc_default_exec:s0" ]; then
  echo "ABORT: service label is '$SVC_LABEL'"
  exit 5
fi

for pair in "service:/odm/bin/hw/android.hardware.nfc-service-st" \
            "hal.so:/odm/lib64/nfc_nci.st21nfc.st.so" \
            "fw.bin:/odm/firmware/st54l_fw.bin" \
            "hal.conf:/odm/etc/libnfc-hal-st.conf" \
            "st54l.txt:/odm/etc/st54l_conf.txt"; do
  s=${pair%%:*}; t=${pair#*:}
  umount "$t" 2>/dev/null
  mount --bind "$TMP/$s" "$t" || { echo "bind failed: $t"; exit 6; }
done
echo "bind mounts:"; mount | grep -E "nfcmod|nfc_nci|st54l|Nfc_st" || true

HAL=$(pidof android.hardware.nfc-service-st)
[ -n "$HAL" ] && kill "$HAL" && echo "killed HAL $HAL"
sleep 6
NFC=$(pidof com.android.nfc)
[ -n "$NFC" ] && kill "$NFC" && echo "killed NfcService $NFC"
sleep 8
dumpsys nfc | head -3

# arm the 7A custom polling frame and report
CLASSPATH=/data/local/tmp/q13.jar /system/bin/app_process /system/bin dev.probe.Q13 2>&1 | tail -6
echo "=== done ==="
