# Security Policy

## Supported Versions

The following table details the branches and general kernel versions actively receiving custom security disclosures, GKI upstream mergers, and companion dependency updates (e.g., KernelSU-Next and SusFS patches):

| Version | Supported | Notes |
| :--- | :--- | :--- |
| Laguna (Pixel 10) / GKI 6.6 | :white_check_mark: Active | Primary target with automated CI compilations. |
| GKI 6.1 | :white_check_mark: Active | Upstreamed with generic common updates. |
| GKI 5.15 | :warning: Critical Only | Security backports and core hooks only. |
| GKI 5.10 | :x: Unsupported | Deprecated. Upgrade source trees to generic upstream. |

---

## :warning: CRITICAL HARDWARE WARNING: Rollback Brick Index (AVB 2.0 / HSM)

Flashing custom kernels or firmware packages compiled with updated toolchains and configuration levels carries an inherent hardware-level security risk.

### 1. What is Anti-Rollback Protection?
Modern Android flagships—including the **Google Pixel 10 (Tensor G5 "Laguna")** and other modern GKI-compliant platforms—enforce **Android Verified Boot (AVB) 2.0** combined with on-chip secure storage hardware (HSM/TPM modules). 

These systems maintain dedicated global secure registers known as **Rollback Counters** (Rollback Indexes) inside non-volatile memory (e.g., UFS replay-protected memory blocks, or RPMB).

### 2. How the Rollback Counter Works
- Every time you flash or boot a verified GKI kernel image or bootloader partition containing a higher `security_version` metadata flag, the hardware security module increments the rollback counter stored on the hardware chip to match.
- Once updated, **the hardware fuse is set.** The chip will refuse to execute or pass the secure boot verify checks for any boot image carrying a Rollback Index lower than the security counter now stored on-die.

### 3. The Brick Trigger (Downgrade Brick)
**If you attempt to flash an older kernel, restore a historical kernel backup, or downgrade your Android OS version after the security rollback counter has been incremented by a new compiler build or system OTA, your bootloader will face a hard signature match failure.**

- **Outcome**: The device will immediately enter an unbootable state ("Verification Failed", lockup in QUSB_BULK / EDL mode, or complete dark brick).
- **Remedy**: On devices with locked bootloaders, this brick is **permanent and irreversible** without official hardware mainboard replacement. On unlocked bootloaders, you must immediately reflashing a system block package carrying an equivalent or higher rollback counter.

### 4. Custom Kernel Compilation Safeguard
Before flashing your compiled kernel ZIP outputs, always verify the patch level of your parent kernel repository branch against your target handset's running security patch level. Ensure that your current compiler flags do not mismatch or artificially downgrade the secure boot variables defined within your Board Config or device tree definitions.

---

## Reporting a Vulnerability

We take the security of custom root modules, kernel cloaking hooks, and network engines seriously. If you discover a vulnerability or security override issue, please report it following these steps:

1. **Do Not Open a Public Issue**: To protect end-user installations from active exploits, do not open public tracking issues on GitHub.
2. **Contact Privately**: Send a detailed description of your proof-of-concept, target GKI trunk version, and security posture findings directly to our security coordination team.
3. **Response Cycle**: We aim to acknowledge and triage non-public reports within **48 hours**, and coordinate an updated public patch release within **14 days**.
