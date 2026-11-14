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
8. Import a BIOS or machine archive from a read-only SAF provider; confirm the app opens the URI in read-only mode and closes the descriptor after the stream is consumed.

## Shared Directory staging/synchronization (approach B)

A SAF tree URI is not passed to QEMU as a POSIX path. The app now:
1. Uses the Android SAF tree picker for `SHARED_DIR`.
2. Copies the selected tree into an app-private staging directory before QEMU starts.
3. Gives QEMU the staging directory through its existing `fat:rw:` backend.
4. After QEMU exits, compares guest-side file hashes with the pre-start baseline.
5. Writes a guest-modified file back only if the Termux-side copy still matches the baseline.
6. Leaves host/guest concurrent changes as conflicts. Guest deletions are not propagated.
7. Retains the staging directory if conflicts or write errors occur.

This is snapshot synchronization, not live access. Changes made in Termux while the VM is running do not appear inside the guest until the next VM start.

## Device validation matrix for Shared Directory

Use a disposable Termux folder and keep a backup before testing.

1. Select a Termux folder in Limbo's Shared Directory picker; confirm the URI is accepted.
2. Start a VM and confirm the folder contents are visible through the guest's FAT drive.
3. Create a new file in the guest, shut down the VM, and confirm the file appears in Termux.
4. Modify an existing file in the guest, shut down, and confirm the Termux copy changes.
5. Modify a different file in Termux while the VM is running; confirm the guest's unchanged copy does not overwrite the Termux edit.
6. Modify the same file in both places; confirm the Termux copy is not overwritten and a conflict is reported.
7. Delete a file in the guest; confirm the Termux original is not deleted.
8. Test nested directories, empty directories, large files, and filenames with spaces/non-ASCII characters.
9. Test a read-only or non-writable provider; VM startup must fail rather than silently using an unusable shared folder.
10. Force-stop the app during VM execution; document that automatic sync cannot be guaranteed after process death.

## Build status

A GitHub Actions workflow now checks out this modified branch, restores the prebuilt native libraries from the original APK, runs the Shared Directory policy unit tests, and builds a side-by-side debug APK. A successful CI build is not equivalent to runtime validation on the ADVAN 1013; the device matrix above remains required.
