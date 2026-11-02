#!/system/bin/sh
# remove the runtime pudding NFC-stack overlay and return to the stock /odm files.
# NOTE: the ST54L controller firmware was upgraded to 02.07.2128 during this experiment.
# After removing the overlay, the stock HAL will compare its 02.04.1188 FW file with the
# controller and may re-flash the controller back to 02.04.1188 (normal vendor update path).
set -u
LOG=/data/local/tmp/nfc-overlay.log
exec >> "$LOG" 2>&1
echo "=== $(date) remove ==="

for t in /odm/bin/hw/android.hardware.nfc-service-st \
         /odm/lib64/nfc_nci.st21nfc.st.so \
         /odm/firmware/st54l_fw.bin \
         /odm/etc/libnfc-hal-st.conf \
         /odm/etc/st54l_conf.txt; do
  if mount | grep -q " $t "; then umount "$t" && echo "unmounted $t"; fi
done
if mount | grep -q " /mnt/nfcmod "; then umount /mnt/nfcmod && echo "unmounted /mnt/nfcmod"; fi
rmdir /mnt/nfcmod 2>/dev/null || true

HAL=$(pidof android.hardware.nfc-service-st)
[ -n "$HAL" ] && kill "$HAL" && echo "killed HAL $HAL"
sleep 6
NFC=$(pidof com.android.nfc)
[ -n "$NFC" ] && kill "$NFC" && echo "killed NfcService $NFC"
sleep 8
dumpsys nfc | head -3
echo "=== done ==="
