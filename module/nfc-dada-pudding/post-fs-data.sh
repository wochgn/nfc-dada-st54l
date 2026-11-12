#!/system/bin/sh
# post-fs-data: overlay Xiaomi 17 (pudding OS3) ST54L NFC stack onto Xiaomi 15 (dada).
# Runtime-only bind mounts; NEVER writes a partition.
# If any SELinux label cannot be verified, nothing is mounted (stock NFC stays intact).
# v1.2: no forced HAL/NfcService restart (fixes the OS4.0.3.0 boot ANR incident).
MODDIR=${0%/*}
LOG=/data/local/tmp/nfc-dada-pudding.log
exec >> "$LOG" 2>&1
echo "=== $(date) post-fs-data ==="

PUDDING_SHA=22a9e85dfe459676f401fbc67cf9870176c7d9c583aefd9963701b2e112fbe4d
FW=/odm/firmware/st54l_fw.bin
if [ "$(sha256sum "$FW" 2>/dev/null | cut -d' ' -f1)" = "$PUDDING_SHA" ]; then
  echo "overlay already active; nothing to do"
  exit 0
fi

i=0
while [ ! -d /odm/lib64 ] && [ $i -lt 200 ]; do sleep 0.1; i=$((i + 1)); done

SRC="$MODDIR/blobs"
TMP=/mnt/nfcmod_dada

for f in nfc-service-st nfc_nci.st21nfc.st.so st54l_fw.bin libnfc-hal-st.conf st54l_conf.txt; do
  [ -f "$SRC/$f" ] || { echo "missing blob $f"; exit 2; }
done

if mount | grep -q " $TMP "; then umount "$TMP" 2>/dev/null; fi
mkdir -p "$TMP"
mount -t tmpfs -o size=64m,mode=0755,suid,dev tmpfs "$TMP" || { echo "tmpfs failed"; exit 3; }

put() { cp -f "$1" "$2" || return 1; chmod "$3" "$2"; chcon "$4" "$2" || echo "chcon failed: $4"; }
put "$SRC/nfc-service-st"        "$TMP/service" 755 u:object_r:hal_nfc_default_exec:s0
put "$SRC/nfc_nci.st21nfc.st.so" "$TMP/hal.so"  644 u:object_r:vendor_file:s0
put "$SRC/st54l_fw.bin"          "$TMP/fw.bin"  644 u:object_r:vendor_file:s0
put "$SRC/libnfc-hal-st.conf"    "$TMP/hal.conf"  644 u:object_r:vendor_configs_file:s0
put "$SRC/st54l_conf.txt"        "$TMP/st54l.txt" 644 u:object_r:vendor_configs_file:s0

echo "--- labels ---"; ls -lZ "$TMP"
SVC_LINE=$(ls -lZ "$TMP/service")
HAL_LINE=$(ls -lZ "$TMP/hal.so")
echo "svc: $SVC_LINE"
echo "hal: $HAL_LINE"
if ! echo "$SVC_LINE" | grep -q 'u:object_r:hal_nfc_default_exec:s0' || ! echo "$HAL_LINE" | grep -q 'u:object_r:vendor_file:s0'; then
  echo "ABORT: bad labels svc=[$SVC_LINE] hal=[$HAL_LINE]; unmounting, stock NFC kept"
  umount "$TMP"; rmdir "$TMP"
  exit 4
fi

for pair in "service:/odm/bin/hw/android.hardware.nfc-service-st" \
            "hal.so:/odm/lib64/nfc_nci.st21nfc.st.so" \
            "fw.bin:/odm/firmware/st54l_fw.bin" \
            "hal.conf:/odm/etc/libnfc-hal-st.conf" \
            "st54l.txt:/odm/etc/st54l_conf.txt"; do
  s=${pair%%:*}; t=${pair#*:}
  mount --bind "$TMP/$s" "$t" || { echo "bind failed $t"; umount "$TMP"; exit 5; }
done
echo "OK: overlay mounted (post-fs-data)"
exit 0
