package com.max2idea.android.limbo.files;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SharedFolderSyncPolicyTest {
    @Test
    public void unchangedGuestFileDoesNotWriteBack() {
        assertEquals(SharedFolderSyncPolicy.Decision.UNCHANGED,
                SharedFolderSyncPolicy.evaluate("base", "base", true, "changed-on-host"));
    }

    @Test
    public void guestChangeWritesBackWhenHostStillMatchesBaseline() {
        assertEquals(SharedFolderSyncPolicy.Decision.WRITE_BACK,
                SharedFolderSyncPolicy.evaluate("base", "guest", true, "base"));
    }

    @Test
    public void concurrentHostAndGuestChangesBecomeConflict() {
        assertEquals(SharedFolderSyncPolicy.Decision.CONFLICT,
                SharedFolderSyncPolicy.evaluate("base", "guest", true, "host"));
    }

    @Test
    public void guestNewFileIsCreatedOnlyWhenHostPathIsUnused() {
        assertEquals(SharedFolderSyncPolicy.Decision.CREATE,
                SharedFolderSyncPolicy.evaluate(null, "guest", false, null));
        assertEquals(SharedFolderSyncPolicy.Decision.CONFLICT,
                SharedFolderSyncPolicy.evaluate(null, "guest", true, "host"));
    }

    @Test
    public void guestChangeConflictsWhenHostFileWasDeleted() {
        assertEquals(SharedFolderSyncPolicy.Decision.CONFLICT,
                SharedFolderSyncPolicy.evaluate("base", "guest", false, null));
    }
}
