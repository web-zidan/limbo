/*
 * Shared-folder staging bridge for Android SAF tree providers.
 * Copyright (C) 2026
 */
package com.max2idea.android.limbo.files;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies a SAF tree to a POSIX directory for QEMU's vvfat backend, then copies
 * guest changes back without overwriting source-side changes made during the VM run.
 *
 * Deletions in the staging directory are intentionally not propagated.
 */
public final class SharedFolderSync {
    private static final String TAG = "SharedFolderSync";
    private static final int BUFFER_SIZE = 32768;
    private static final int MAX_DEPTH = 64;
    private static Session activeSession;

    private SharedFolderSync() { }

    private static final class Session {
        final DocumentFile root;
        final File stage;
        final Map<String, String> baseline = new HashMap<>();
        int copiedBack;
        int conflicts;
        int failures;

        Session(DocumentFile root, File stage) {
            this.root = root;
            this.stage = stage;
        }
    }

    /** Returns a local directory for content:// tree URIs, or the original path otherwise. */
    public static synchronized String prepare(Context context, String source) throws Exception {
        finishStaleSession();
        if (source == null || !source.startsWith("content://"))
            return source;

        Uri treeUri = Uri.parse(source);
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.isDirectory() || !root.canRead() || !root.canWrite())
            throw new IllegalStateException("Shared folder provider is not readable and writable");

        File stage = new File(context.getFilesDir(),
                "shared-folder-stage-" + System.currentTimeMillis());
        if (!stage.mkdirs())
            throw new IllegalStateException("Cannot create local Shared Directory staging folder");

        Session session = new Session(root, stage);
        try {
            copySourceToStage(context, root, stage, "", session, 0);
            activeSession = session;
            Log.i(TAG, "Staged Shared Directory at " + stage.getAbsolutePath()
                    + " (" + session.baseline.size() + " entries)");
            return stage.getAbsolutePath();
        } catch (Exception e) {
            deleteLocalTree(stage);
            throw e;
        }
    }

    /** Must be called only after QEMU has fully exited. */
    public static synchronized String finish(Context context) {
        Session session = activeSession;
        activeSession = null;
        if (session == null)
            return null;

        List<File> entries = new ArrayList<>();
        collectLocalEntries(session.stage, session.stage, entries, 0);
        Collections.sort(entries, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return relative(session.stage, a).compareTo(relative(session.stage, b));
            }
        });

        for (File local : entries) {
            String path = relative(session.stage, local);
            try {
                if (local.isDirectory()) {
                    ensureRemoteDirectory(session, path);
                } else if (local.isFile()) {
                    syncLocalFile(context, session, local, path);
                }
            } catch (Exception e) {
                session.failures++;
                Log.e(TAG, "Could not sync " + path, e);
            }
        }

        String result = "Shared Directory sync: " + session.copiedBack + " file(s) copied, "
                + session.conflicts + " conflict(s), " + session.failures + " error(s).";
        if (session.conflicts == 0 && session.failures == 0) {
            deleteLocalTree(session.stage);
            Log.i(TAG, result + " Staging cleaned up.");
        } else {
            result += " Recovery copy retained in app storage: " + session.stage.getAbsolutePath();
            Log.w(TAG, result);
        }
        return result;
    }

    private static void finishStaleSession() {
        if (activeSession != null)
            throw new IllegalStateException("A Shared Directory sync session is already active");
    }

    private static void copySourceToStage(Context context, DocumentFile directory, File localDir,
                                          String prefix, Session session, int depth) throws Exception {
        if (depth > MAX_DEPTH)
            throw new IllegalStateException("Shared Directory nesting limit exceeded");
        DocumentFile[] children = directory.listFiles();
        for (DocumentFile child : children) {
            String name = child.getName();
            if (!validName(name))
                throw new IllegalStateException("Unsupported document name in Shared Directory");
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            File local = new File(localDir, name);
            if (child.isDirectory()) {
                if (!local.mkdir() && !local.isDirectory())
                    throw new IllegalStateException("Cannot create staging directory: " + path);
                session.baseline.put(path, "D");
                copySourceToStage(context, child, local, path, session, depth + 1);
            } else {
                String sourceHash = hashDocument(context, child);
                try (InputStream in = context.getContentResolver().openInputStream(child.getUri());
                     FileOutputStream out = new FileOutputStream(local)) {
                    if (in == null)
                        throw new IllegalStateException("Provider returned no input stream for " + path);
                    copy(in, out);
                }
                session.baseline.put(path, sourceHash);
            }
        }
    }

    private static void ensureRemoteDirectory(Session session, String path) throws Exception {
        DocumentFile remote = findRemote(session.root, path);
        String baseline = session.baseline.get(path);
        if (baseline != null) {
            if (remote == null || !remote.isDirectory())
                session.conflicts++;
            return;
        }
        if (remote != null) {
            if (!remote.isDirectory())
                session.conflicts++;
            return;
        }
        DocumentFile parent = findRemoteParent(session.root, path);
        String name = leaf(path);
        if (parent == null || parent.createDirectory(name) == null)
            throw new IllegalStateException("Provider cannot create directory " + path);
    }

    private static void syncLocalFile(Context context, Session session, File local, String path)
            throws Exception {
        String localHash = hashFile(local);
        String baselineHash = session.baseline.get(path);
        DocumentFile remote = findRemote(session.root, path);

        String remoteHash = remote == null || remote.isDirectory()
                ? null : hashDocument(context, remote);
        SharedFolderSyncPolicy.Decision decision = SharedFolderSyncPolicy.evaluate(
                baselineHash, localHash, remote != null, remoteHash);

        if (decision == SharedFolderSyncPolicy.Decision.UNCHANGED)
            return;
        if (decision == SharedFolderSyncPolicy.Decision.CONFLICT) {
            session.conflicts++;
            Log.w(TAG, "Preserving conflict without overwriting source: " + path);
            return;
        }

        if (decision == SharedFolderSyncPolicy.Decision.WRITE_BACK) {
            writeRemote(context, remote, local);
            session.copiedBack++;
            return;
        }

        DocumentFile parent = findRemoteParent(session.root, path);
        if (parent == null)
            throw new IllegalStateException("Missing destination directory for " + path);
        DocumentFile created = parent.createFile("application/octet-stream", leaf(path));
        if (created == null)
            throw new IllegalStateException("Provider cannot create file " + path);
        writeRemote(context, created, local);
        session.copiedBack++;
    }

    private static void writeRemote(Context context, DocumentFile target, File local) throws Exception {
        try (FileInputStream in = new FileInputStream(local);
             OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "wt")) {
            if (out == null)
                throw new IllegalStateException("Provider returned no output stream for " + target.getName());
            copy(in, out);
        }
    }

    private static DocumentFile findRemote(DocumentFile root, String path) {
        DocumentFile current = root;
        for (String part : path.split("/")) {
            if (current == null || !current.isDirectory())
                return null;
            current = current.findFile(part);
        }
        return current;
    }

    private static DocumentFile findRemoteParent(DocumentFile root, String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? root : findRemote(root, path.substring(0, slash));
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static boolean validName(String name) {
        return name != null && !name.isEmpty() && !".".equals(name) && !"..".equals(name)
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static void collectLocalEntries(File root, File directory, List<File> result, int depth) {
        if (depth > MAX_DEPTH)
            return;
        File[] children = directory.listFiles();
        if (children == null)
            return;
        for (File child : children) {
            result.add(child);
            if (child.isDirectory())
                collectLocalEntries(root, child, result, depth + 1);
        }
    }

    private static String relative(File root, File file) {
        return root.toURI().relativize(file.toURI()).getPath();
    }

    private static String hashDocument(Context context, DocumentFile document) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(document.getUri())) {
            if (in == null)
                throw new IllegalStateException("Provider returned no input stream for " + document.getName());
            return hashStream(in);
        }
    }

    private static String hashFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return hashStream(in);
        }
    }

    private static String hashStream(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = in.read(buffer)) != -1)
            digest.update(buffer, 0, count);
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest())
            value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = in.read(buffer)) != -1)
            out.write(buffer, 0, count);
        out.flush();
    }

    private static void deleteLocalTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null)
                for (File child : children)
                    deleteLocalTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
