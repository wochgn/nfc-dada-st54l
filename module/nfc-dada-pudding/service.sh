#!/system/bin/sh
# late_start: fallback/verify path. If post-fs-data could not mount the overlay
# (e.g. SELinux label check failed at early boot), re-apply it here (normal root
# context), restart HAL/NFC service, then verify NFC is usable.
MODDIR=${0%/*}
LOG=/data/local/tmp/nfc-dada-pudding.log
exec >> "$LOG" 2>&1
echo "=== $(date) service ==="

FW=pudding  # marker
OID_FW=/odm/firmware/st54l_fw.bin
PUDDING_SHA=22a9e85dfe459676f401fbc67cf9870176c7d9c583aefd9963701b2e112fbe4d
SRC="$MODDIR/blobs"
TMP=/mnt/nfcmod_dada

wait_boot() { i=0; while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do sleep 1; i=$((i+1)); done; }

overlay() {
  for f in nfc-service-st nfc_nci.st21nfc.st.so st54l_fw.bin libnfc-hal-st.conf st54l_conf.txt; do
    [ -f "$SRC/$f" ] || { echo "missing blob $f"; return 1; }
  done
  if mount | grep -q " $TMP "; then
    # already mounted by post-fs-data; verify label + hash
    :
  else
    mkdir -p "$TMP"
    mount -t tmpfs -o size=64m,mode=0755,suid,dev tmpfs "$TMP" || { echo "tmpfs failed"; return 1; }
    put() { cp -f "$1" "$2"; chmod "$3" "$2"; chcon "$4" "$2"; }
    put "$SRC/nfc-service-st" "$TMP/service" 755 u:object_r:hal_nfc_default_exec:s0
    put "$SRC/nfc_nci.st21nfc.st.so" "$TMP/hal.so" 644 u:object_r:vendor_file:s0
    put "$SRC/st54l_fw.bin" "$TMP/fw.bin" 644 u:object_r:vendor_file:s0
    put "$SRC/libnfc-hal-st.conf" "$TMP/hal.conf" 644 u:object_r:vendor_configs_file:s0
    put "$SRC/st54l_conf.txt" "$TMP/st54l.txt" 644 u:object_r:vendor_configs_file:s0
    SVC_LINE=$(ls -lZ "$TMP/service")
    echo "svc: $SVC_LINE"
    echo "$SVC_LINE" | grep -q 'u:object_r:hal_nfc_default_exec:s0' || { echo "ABORT labels: $SVC_LINE"; umount "$TMP"; return 1; }
    for pair in "service:/odm/bin/hw/android.hardware.nfc-service-st" \
                "hal.so:/odm/lib64/nfc_nci.st21nfc.st.so" \
                "fw.bin:/odm/firmware/st54l_fw.bin" \
                "hal.conf:/odm/etc/libnfc-hal-st.conf" \
                "st54l.txt:/odm/etc/st54l_conf.txt"; do
      s=${pair%%:*}; t=${pair#*:}
      mount --bind "$TMP/$s" "$t" || { echo "bind failed $t"; return 1; }
    done
  fi
  return 0
}

restart_nfc() {
  H=$(pidof android.hardware.nfc-service-st); [ -n "$H" ] && kill "$H" && echo "killed HAL $H"
  sleep 8
  N=$(pidof com.android.nfc); [ -n "$N" ] && kill "$N" && echo "killed NfcService $N"
  sleep 10
}

wait_boot
echo "boot completed"

if [ "$(sha256sum "$OID_FW" 2>/dev/null | cut -d' ' -f1)" != "$PUDDING_SHA" ]; then
  echo "overlay not active, applying now"
  overlay || echo "overlay failed; leaving stock NFC"
fi

if [ "$(sha256sum "$OID_FW" 2>/dev/null | cut -d' ' -f1)" = "$PUDDING_SHA" ]; then
  restart_nfc
  # one retry if NFC did not come up
  if [ "$(dumpsys nfc 2>/dev/null | head -3 | grep -c 'mState=on')" = "0" ]; then
    echo "NFC not on, retry"
    restart_nfc
  fi
fi

echo "--- state ---"
dumpsys nfc 2>/dev/null | head -3
ls -lZ /odm/bin/hw/android.hardware.nfc-service-st /odm/firmware/st54l_fw.bin 2>/dev/null
echo "=== done ==="

