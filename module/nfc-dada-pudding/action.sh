#!/system/bin/sh
# KernelSU action button: re-apply the overlay and restart NFC (same logic as service.sh).
MODDIR=${0%/*}
exec sh "$MODDIR/service.sh"
