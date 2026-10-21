package org.jackhuang.hmcl.uncut.patcher;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Entry point for a single artifact: detects the container format, locates the embedded jar and
 * returns the patched artifact with the original prefix (launcher stub) preserved byte for byte.
 */
public final class Patcher {
    public record Result(byte[] data, String kind, int zipBase, String version) {
    }

    private Patcher() {
    }

    public static Result patch(byte[] input, Consumer<String> log) throws Exception {
        if (Util.startsWith(input, "!<arch>\n")) {
            DebPatcher.Result deb = DebPatcher.patch(input, log);
            return new Result(deb.data(), "deb", -1, deb.version());
        }

        String kind;
        int base;
        if (Util.startsWith(input, "PK\u0003\u0004")) {
            kind = "jar";
            base = 0;
        } else if (Util.startsWith(input, "MZ")) {
            kind = "exe";
            base = Util.findZipBase(input);
            log.accept("定位内嵌 jar：偏移 " + base);
        } else if (Util.startsWith(input, "#!")) {
            kind = "sh";
            base = Util.findZipBase(input);
            log.accept("定位内嵌 jar：偏移 " + base);
        } else {
            throw new IOException("无法识别的文件格式（不是 jar / exe / sh / deb）");
        }

        byte[] jar = Arrays.copyOfRange(input, base, input.length);
        JarPatcher.Result patched = JarPatcher.patch(jar, log);

        byte[] out = new byte[base + patched.jar().length];
        System.arraycopy(input, 0, out, 0, base);
        System.arraycopy(patched.jar(), 0, out, base, patched.jar().length);
        return new Result(out, kind, base, patched.version());
    }
}
