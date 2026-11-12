#!/system/bin/sh
# KernelSU action button: re-apply the runtime overlay (post-fs-data logic).
# v1.2 does not restart HAL/NfcService; a reboot is recommended for the new
# overlay to take effect if it was not active during boot.
MODDIR=${0%/*}
LOG=/data/local/tmp/nfc-dada-pudding.log
exec >> "$LOG" 2>&1
echo "=== $(date) action ==="
sh "$MODDIR/post-fs-data.sh"
echo "--- action state ---"
dumpsys nfc 2>/dev/null | head -3
echo "=== action done (reboot to activate a newly-mounted overlay) ==="
