package com.opendeeptutor.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.GZIPInputStream;

/**
 * Unpacks the bundled Linux guest (assets/runtime/payload.tgz) into the app's
 * private files directory. Re-runs only when the bundled payload version changes,
 * so app updates refresh the runtime while user data (kept outside it) survives.
 */
final class PayloadInstaller {
    interface Progress {
        void update(int percent);
    }

    private static final String ASSET_DIR = "runtime";
    private static final String MARKER = ".payload-version";

    private PayloadInstaller() {}

    static JSONObject metadata(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/payload.json")) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Invalid payload metadata", e);
        }
    }

    static boolean installed(Context context, File rootfs) throws IOException {
        File marker = new File(rootfs, MARKER);
        if (!marker.isFile()) return false;
        String current = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
        return current.equals(metadata(context).optString("version"));
    }

    static void ensure(Context context, File rootfs, Progress progress) throws IOException {
        if (installed(context, rootfs)) return;
        JSONObject meta = metadata(context);
        long compressed = meta.optLong("compressed", 1);
        long uncompressed = meta.optLong("uncompressed", 0);

        File partial = new File(context.getFilesDir(), "rootfs.partial");
        deleteTree(partial);
        deleteTree(rootfs);
        long needed = uncompressed + 200L * 1024 * 1024;
        long free = context.getFilesDir().getUsableSpace();
        if (free < needed) {
            throw new IOException(String.format(
                    "存储空间不足：解压运行环境需要约 %.1f GB，当前可用 %.1f GB",
                    needed / 1e9, free / 1e9));
        }
        if (!partial.mkdirs()) throw new IOException("Cannot create " + partial);

        progress.update(0);
        AssetManager assets = context.getAssets();
        try (CountingInputStream counted = new CountingInputStream(new BufferedInputStream(
                assets.open(ASSET_DIR + "/payload.tgz", AssetManager.ACCESS_STREAMING), 1 << 16));
             InputStream tar = new GZIPInputStream(counted, 1 << 16)) {
            extract(tar, partial, () -> {
                int percent = (int) Math.min(99, counted.count * 100 / Math.max(1, compressed));
                progress.update(percent);
            });
        }
        Files.write(new File(partial, MARKER).toPath(),
                meta.optString("version").getBytes(StandardCharsets.UTF_8));
        if (!partial.renameTo(rootfs)) throw new IOException("Cannot activate " + rootfs);
        progress.update(100);
    }

    /** Minimal ustar/GNU tar reader: regular files, directories, symlinks and hard links. */
    private static void extract(InputStream in, File dest, Runnable tick) throws IOException {
        byte[] header = new byte[512];
        byte[] buffer = new byte[1 << 16];
        String longName = null;
        String longLink = null;
        String root = dest.getCanonicalPath();
        int entries = 0;
        while (true) {
            if (!readFully(in, header)) break;
            if (isZero(header)) break;
            String name = cString(header, 0, 100);
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String link = cString(header, 157, 100);
            String magic = cString(header, 257, 6);
            if ("ustar".equals(magic) && header[262] == 0) {
                // POSIX ustar splits long paths into prefix + name.
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            if (type == 'L' || type == 'K') {
                String value = cString(readEntry(in, size), 0, (int) size);
                if (type == 'L') longName = value; else longLink = value;
                continue;
            }
            if (type == 'x' || type == 'g') {
                skip(in, padded(size));
                continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }

            while (name.startsWith("./")) name = name.substring(2);
            while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.isEmpty()) { skip(in, padded(size)); continue; }
            File target = new File(dest, name);
            String canonicalParent = target.getParentFile().getCanonicalPath();
            if (!canonicalParent.startsWith(root)) throw new IOException("Unsafe path in payload: " + name);
            target.getParentFile().mkdirs();

            try {
                switch (type) {
                    case '5':
                        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("mkdir " + name);
                        Os.chmod(target.getPath(), (mode & 07777) | 0700);
                        skip(in, padded(size));
                        break;
                    case '2':
                        removeEntry(target);
                        Os.symlink(link, target.getPath());
                        skip(in, padded(size));
                        break;
                    case '1':
                        removeEntry(target);
                        File source = new File(dest, link);
                        try {
                            Os.link(source.getPath(), target.getPath());
                        } catch (ErrnoException e) {
                            // Android's SELinux policy usually forbids hard links in app data.
                            Files.copy(source.toPath(), target.toPath());
                            Os.chmod(target.getPath(), (int) (mode & 07777));
                        }
                        skip(in, padded(size));
                        break;
                    case '0':
                    case 0:
                    case '7':
                        removeEntry(target);
                        try (OutputStream out = new FileOutputStream(target)) {
                            long remaining = size;
                            while (remaining > 0) {
                                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (n < 0) throw new IOException("Truncated payload at " + name);
                                out.write(buffer, 0, n);
                                remaining -= n;
                            }
                        }
                        skip(in, padded(size) - size);
                        Os.chmod(target.getPath(), (mode & 07777) | 0600);
                        break;
                    default:
                        // Device nodes and FIFOs are never needed: /dev is bound from the host.
                        skip(in, padded(size));
                }
            } catch (ErrnoException e) {
                throw new IOException(name + ": " + e.getMessage(), e);
            }
            if ((++entries & 255) == 0) tick.run();
        }
        tick.run();
    }

    private static void removeEntry(File file) {
        // File.exists() follows symlinks; a dangling one would be missed.
        if (Files.isSymbolicLink(file.toPath()) || file.exists()) file.delete();
    }

    static void deleteTree(File root) throws IOException {
        Path path = root.toPath();
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                dir.toFile().setWritable(true, true);
                dir.toFile().setExecutable(true, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long padded(long size) { return (size + 511) & ~511L; }

    private static byte[] readEntry(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) padded(size)];
        if (!readFully(in, data)) throw new IOException("Truncated payload");
        return data;
    }

    private static boolean readFully(InputStream in, byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new IOException("Truncated payload");
            }
            off += n;
        }
        return true;
    }

    private static void skip(InputStream in, long count) throws IOException {
        byte[] scratch = null;
        while (count > 0) {
            long n = in.skip(count);
            if (n <= 0) {
                if (scratch == null) scratch = new byte[8192];
                int r = in.read(scratch, 0, (int) Math.min(scratch.length, count));
                if (r < 0) throw new IOException("Truncated payload");
                n = r;
            }
            count -= n;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static String cString(byte[] data, int off, int len) {
        int end = off;
        while (end < off + len && end < data.length && data[end] != 0) end++;
        return new String(data, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] data, int off, int len) {
        if ((data[off] & 0x80) != 0) {
            // GNU base-256 encoding for large values.
            long value = data[off] & 0x7f;
            for (int i = off + 1; i < off + len; i++) value = (value << 8) | (data[i] & 0xff);
            return value;
        }
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte b = data[i];
            if (b == 0 || b == ' ') {
                if (value != 0 || b == 0) break;
                continue;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static final class CountingInputStream extends FilterInputStream {
        volatile long count;

        CountingInputStream(InputStream in) { super(in); }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count += s;
            return s;
        }
    }
}
