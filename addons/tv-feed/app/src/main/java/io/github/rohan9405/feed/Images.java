package io.github.rohan9405.feed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Posters downloaded once and kept small: a tile is 400 px wide and a hero backdrop 1280 px, as JPEG, so the
 * launcher decodes a few KB per tile instead of an app's full-size art.
 */
final class Images {
    static final int TILE_W = 400;
    static final int BACKDROP_W = 1280;
    /** The only file names FeedProvider will open. */
    static final Pattern NAME = Pattern.compile("[0-9a-f]{16}-[tb]\\.jpg");
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private Images() {
    }

    static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The cached file's name, fetching the art when it is not there yet; empty when it cannot be had. */
    static String cache(Context ctx, String source, boolean backdrop) {
        if (source == null || source.isEmpty()) return "";
        String name = hash(source) + (backdrop ? "-b.jpg" : "-t.jpg");
        File f = new File(dir(ctx), name);
        if (f.length() > 0) return name;
        try {
            byte[] data = load(ctx, source);
            Bitmap b = decode(data, backdrop ? BACKDROP_W : TILE_W);
            if (b == null) return "";
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, backdrop ? 82 : 85, out);
            } finally {
                b.recycle();
            }
            return tmp.renameTo(f) ? name : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Deletes the files no item refers to any more. */
    static void prune(Context ctx, Set<String> keep) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (!keep.contains(f.getName())) f.delete();
        }
    }

    private static byte[] load(Context ctx, String source) throws IOException {
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        if ("https".equals(scheme) || "http".equals(scheme)) return Net.get(source, MAX_BYTES);
        // android.resource:// and an app's own content:// image provider (Jellyfin's, for example).
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no stream");
            return Net.read(in, MAX_BYTES);
        } catch (SecurityException e) {
            throw new IOException(e);
        }
    }

    private static Bitmap decode(byte[] data, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null || b.getWidth() <= width) return b;
        Bitmap scaled = Bitmap.createScaledBitmap(b, width, Math.max(1, b.getHeight() * width / b.getWidth()), true);
        if (scaled != b) b.recycle();
        return scaled;
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
