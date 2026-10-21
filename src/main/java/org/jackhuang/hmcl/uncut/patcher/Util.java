package org.jackhuang.hmcl.uncut.patcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class Util {
    private Util() {
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static boolean startsWith(byte[] data, String ascii) {
        return startsWith(data, ascii.getBytes(StandardCharsets.US_ASCII));
    }

    static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static long u32(byte[] b, int off) {
        return (long) (b[off] & 0xFF)
                | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16)
                | ((long) (b[off + 3] & 0xFF) << 24);
    }

    /**
     * Locates the start offset of an appended ZIP archive (self-extracting exe/sh, or a plain jar).
     * The ZIP central directory offsets are relative to the archive start, so the base can be derived
     * from the end-of-central-directory record.
     */
    static int findZipBase(byte[] data) {
        int min = Math.max(0, data.length - 65557);
        for (int i = data.length - 22; i >= min; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4B && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                long cdSize = u32(data, i + 12);
                long cdOffset = u32(data, i + 16);
                long base = i - cdSize - cdOffset;
                if (base >= 0 && base <= Integer.MAX_VALUE) {
                    int b = (int) base;
                    if (b + 4 <= data.length
                            && data[b] == 0x50 && data[b + 1] == 0x4B
                            && data[b + 2] == 0x03 && data[b + 3] == 0x04) {
                        return b;
                    }
                }
            }
        }
        throw new IllegalArgumentException("找不到内嵌的 ZIP/JAR 结构");
    }

    static String sha256(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IOException("SHA-256 计算失败", e);
        }
    }
}
