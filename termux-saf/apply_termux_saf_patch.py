#!/usr/bin/env python3
"""Apply the provider-agnostic SAF URI fix to Limbo 6.0.1 source."""
from pathlib import Path
import argparse
import re
import sys

TARGET = Path("limbo-android-lib/src/main/java/com/max2idea/android/limbo/files/FileUtils.java")

NEW_METHOD = '''    public static String getFileUriFromIntent(Activity activity, Intent data, boolean write) {
        if (data == null)
            return null;

        Uri uri = data.getData();
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            showFileNotSupported(activity);
            return null;
        }

        // Accept SAF documents from any provider; Android's URI grant is the authority.
        int requestedModeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (write)
            requestedModeFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

        int grantedModeFlags = data.getFlags() & requestedModeFlags;
        if ((grantedModeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            showFileNotSupported(activity);
            return null;
        }

        // Persist only flags actually granted by the picker. Temporary grants remain usable.
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                activity.getContentResolver().takePersistableUriPermission(uri, grantedModeFlags);
            } catch (SecurityException | IllegalArgumentException e) {
                Log.w(TAG, "Could not persist document permission for " + uri, e);
            }
        } else {
            Log.w(TAG, "Document provider did not grant persistable access for " + uri);
        }

        if (write && (grantedModeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            Log.w(TAG, "Document provider granted read-only access for " + uri
                    + "; writable virtual disks may not be usable");
        }

        return uri.toString();
    }
'''

METHOD_RE = re.compile(
    r"(?ms)^    public static String getFileUriFromIntent\(Activity activity, Intent data, boolean write\) \{.*?^    \}\n"
)


def patch_source_text(source: str) -> str:
    if NEW_METHOD.rstrip() in source:
        return source
    matches = list(METHOD_RE.finditer(source))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one Limbo 6.0.1 method; found {len(matches)}")
    return METHOD_RE.sub(lambda _m: NEW_METHOD, source, count=1)


def apply_to_project(project_dir: Path) -> Path:
    target = project_dir / TARGET
    if not target.is_file():
        raise FileNotFoundError(f"Limbo source file not found: {target}")
    original = target.read_text(encoding="utf-8")
    updated = patch_source_text(original)
    if updated != original:
        target.write_text(updated, encoding="utf-8")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project_dir", type=Path)
    args = parser.parse_args()
    try:
        print(f"Patched: {apply_to_project(args.project_dir.resolve())}")
    except (OSError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
