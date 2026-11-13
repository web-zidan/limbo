import importlib.util
import unittest
from pathlib import Path

PATCHER = Path(__file__).with_name("apply_termux_saf_patch.py")
spec = importlib.util.spec_from_file_location("saf_patcher", PATCHER)
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)

LEGACY = """    public static String getFileUriFromIntent(Activity activity, Intent data, boolean write) {
        if (data == null)
            return null;
        Uri uri = data.getData();
        DocumentFile pickedFile = DocumentFile.fromSingleUri(activity, uri);
        String file = uri.toString();
        if (!file.contains("com.android.externalstorage.documents")) {
            showFileNotSupported(activity);
            return null;
        }
        activity.grantUriPermission(activity.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        activity.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        return file;
    }
"""


class TermuxSafPatchTests(unittest.TestCase):
    def test_accepts_content_uri_from_any_provider(self):
        patched = patcher.patch_source_text(LEGACY)
        self.assertNotIn("com.android.externalstorage.documents", patched)
        self.assertIn('"content".equalsIgnoreCase(uri.getScheme())', patched)

    def test_persists_only_granted_mode_flags(self):
        patched = patcher.patch_source_text(LEGACY)
        self.assertIn("data.getFlags() & requestedModeFlags", patched)
        self.assertIn("takePersistableUriPermission(uri, grantedModeFlags)", patched)

    def test_tolerates_non_persistable_provider(self):
        patched = patcher.patch_source_text(LEGACY)
        self.assertIn("catch (SecurityException | IllegalArgumentException", patched)
        self.assertIn("Document provider did not grant persistable access", patched)

    def test_requires_read_permission_and_warns_on_missing_write(self):
        patched = patcher.patch_source_text(LEGACY)
        self.assertIn("Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0", patched)
        self.assertIn("writable virtual disks may not be usable", patched)

    def test_patch_is_idempotent(self):
        first = patcher.patch_source_text(LEGACY)
        self.assertEqual(first, patcher.patch_source_text(first))


if __name__ == "__main__":
    unittest.main(verbosity=2)
