package com.max2idea.android.limbo.files;

/** Pure conflict policy shared by the SAF sync implementation and JVM tests. */
public final class SharedFolderSyncPolicy {
    public enum Decision { UNCHANGED, WRITE_BACK, CREATE, CONFLICT }

    private SharedFolderSyncPolicy() { }

    public static Decision evaluate(String baselineHash, String localHash,
                                    boolean remoteExists, String remoteHash) {
        if (baselineHash == null)
            return remoteExists ? Decision.CONFLICT : Decision.CREATE;
        if (baselineHash.equals(localHash))
            return Decision.UNCHANGED;
        if (!remoteExists || !baselineHash.equals(remoteHash))
            return Decision.CONFLICT;
        return Decision.WRITE_BACK;
    }
}
