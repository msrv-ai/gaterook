abi <abi/4.0>,
include <tunables/global>

# GateRook (.deb, ARCHITECTURE.md §7.1): Ubuntu 24.04 and later allow unprivileged user namespaces only to
# profiled programs, and Chromium's sandbox needs them. Unconfined otherwise; installed by electron-builder's
# after-install script when the system's AppArmor supports abi 4.0. A release never runs without the sandbox.
profile "${executable}" "/opt/${sanitizedProductName}/${executable}" flags=(unconfined) {
  userns,

  # Site-specific additions and overrides. See local/README for details.
  include if exists <local/${executable}>
}
