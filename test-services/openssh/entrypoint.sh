#!/bin/sh
# Keys of the test sshd live in /keys (test-services/run/openssh on the host), made once: the host key, a user key
# the tests sign in with and a user CA whose certificates sshd trusts. sshd wants its own copies with strict modes,
# and writes its log next to the keys, where the tests read "Accepted certificate ID …".
set -eu
k=/keys
mkdir -p "$k"
[ -s "$k/host_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -C gw-test-host -f "$k/host_ed25519"
[ -s "$k/user_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -C gw-test-user -f "$k/user_ed25519"
[ -s "$k/user_ca" ] || ssh-keygen -q -t ed25519 -N '' -C gw-test-user-ca -f "$k/user_ca"
chmod 644 "$k"/*

install -m 600 "$k/host_ed25519" /etc/ssh/gw_host_ed25519
install -m 644 "$k/user_ed25519.pub" /etc/ssh/gw_authorized_keys
install -m 644 "$k/user_ca.pub" /etc/ssh/gw_user_ca.pub
cat > /etc/ssh/gw_sshd_config <<'CONFIG'
Port 22
ListenAddress 0.0.0.0
HostKey /etc/ssh/gw_host_ed25519
PidFile /run/sshd.pid
AuthorizedKeysFile /etc/ssh/gw_authorized_keys
TrustedUserCAKeys /etc/ssh/gw_user_ca.pub
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
AllowTcpForwarding yes
MaxSessions 10
LogLevel VERBOSE
CONFIG
: > "$k/sshd.log"
exec /usr/sbin/sshd -D -f /etc/ssh/gw_sshd_config -E "$k/sshd.log"
