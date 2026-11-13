# Termux SAF access test plan

Target: Limbo 6.0.1 source, Android 13 / ARM64, Termux 0.118.3.

## Scope of this patch

The SAF result handler accepts valid `content://` URIs from document providers other than Android's external-storage provider. It retains only URI permissions actually returned by the provider and attempts persistable permission only when the result advertises it.

## Device test matrix

1. Open a disk-image picker from Limbo and choose a small test image exposed by Termux's document provider.
2. Confirm Limbo accepts the selection without the "file not supported" dialog.
3. Restart Limbo and confirm the URI remains authorized when the provider granted persistable permission.
4. Start a VM with a disposable disk image and confirm the provider supports the requested read/write mode.
5. Choose an ISO through the same provider and confirm Limbo opens it read-only.
6. Repeat with Android's external-storage provider to check for regressions.
7. Test a provider that grants only temporary access; confirm selection succeeds for the current grant and that no persistable-permission crash occurs.

## Shared Directory limitation

The existing Limbo source explicitly routes `SHARED_DIR` to its legacy filesystem browser because native JNI code expects a real filesystem path and uses `readdir()`. A SAF tree URI is not a POSIX directory path. This patch intentionally does not pretend that changing the picker alone makes Termux directories usable as QEMU shared directories. That requires a separate native/provider bridge (for example, a userspace filesystem layer) and end-to-end VM testing.

## Build status

This repository snapshot does not include a Gradle wrapper, and the Android application module references prebuilt JNI libraries. A successful APK build and runtime test must be recorded separately; this source change alone is not a verified APK.
