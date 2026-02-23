#!/data/data/com.termux/files/usr/bin/bash
# check-x11-socket.sh — Check if X11 socket is ready
# Output: "READY" if X11 socket exists, "WAITING" if not.
# Always exits 0.

X11_SOCKET="$PREFIX/tmp/.X11-unix/X0"

if [ -S "$X11_SOCKET" ] || [ -e "$X11_SOCKET" ]; then
    echo "READY"
else
    echo "WAITING"
fi

exit 0
