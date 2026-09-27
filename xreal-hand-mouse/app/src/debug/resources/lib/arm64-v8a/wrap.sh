#!/system/bin/sh
LD_PRELOAD="$(dirname "$0")/libioctltap.so" exec "$@"
