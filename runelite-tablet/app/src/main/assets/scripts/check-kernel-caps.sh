#!/data/data/com.termux/files/usr/bin/bash
# Kernel capability check — detects seccomp-bpf and user namespace support
# for potential ptrace overhead reduction
# Usage: check-kernel-caps.sh

echo "KERNEL_CAPS: start"

# Check kernel version
KERNEL_VERSION=$(uname -r 2>/dev/null || echo "unknown")
echo "KERNEL_CAPS: kernel_version=$KERNEL_VERSION"

# Check kernel config for seccomp
SECCOMP_FILTER="unknown"
SECCOMP_BPF="unknown"
if [ -f /proc/config.gz ]; then
    SECCOMP_FILTER=$(zcat /proc/config.gz 2>/dev/null | grep "CONFIG_SECCOMP_FILTER=" | head -1 || echo "not_found")
    SECCOMP_BPF=$(zcat /proc/config.gz 2>/dev/null | grep "CONFIG_SECCOMP=" | head -1 || echo "not_found")
elif [ -f "/boot/config-$(uname -r)" ]; then
    SECCOMP_FILTER=$(grep "CONFIG_SECCOMP_FILTER=" "/boot/config-$(uname -r)" 2>/dev/null | head -1 || echo "not_found")
    SECCOMP_BPF=$(grep "CONFIG_SECCOMP=" "/boot/config-$(uname -r)" 2>/dev/null | head -1 || echo "not_found")
else
    echo "KERNEL_CAPS: config_source=none"
fi
echo "KERNEL_CAPS: seccomp_filter=$SECCOMP_FILTER"
echo "KERNEL_CAPS: seccomp=$SECCOMP_BPF"

# Check proot seccomp support
PROOT_SECCOMP="unsupported"
if command -v proot >/dev/null 2>&1; then
    if proot --help 2>&1 | grep -qi seccomp; then
        PROOT_SECCOMP="supported"
    fi
fi
echo "KERNEL_CAPS: proot_seccomp=$PROOT_SECCOMP"

# Check user namespace support
USER_NS="unsupported"
USER_NS_RESULT=$(unshare --user --map-root-user echo "user_ns_works" 2>&1)
if echo "$USER_NS_RESULT" | grep -q "user_ns_works"; then
    USER_NS="supported"
fi
echo "KERNEL_CAPS: user_ns=$USER_NS"
echo "KERNEL_CAPS: user_ns_detail=$USER_NS_RESULT"

# Summary decision
SECCOMP_VIABLE=false
USER_NS_VIABLE=false

if [ "$PROOT_SECCOMP" = "supported" ] && echo "$SECCOMP_FILTER" | grep -q "=y"; then
    SECCOMP_VIABLE=true
fi
if [ "$USER_NS" = "supported" ]; then
    USER_NS_VIABLE=true
fi

echo "KERNEL_CAPS: seccomp_viable=$SECCOMP_VIABLE user_ns_viable=$USER_NS_VIABLE"
echo "KERNEL_CAPS: done"
