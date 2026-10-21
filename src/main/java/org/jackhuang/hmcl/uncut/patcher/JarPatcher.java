package org.jackhuang.hmcl.uncut.patcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites the class entry inside an HMCL jar and returns a new jar.
 * <p>
 * Ordering, timestamps and the remaining entries are preserved. The original archive is rebuilt from
 * scratch (all entries are deflated again), so the byte content of the other entries is unchanged even
 * if their compressed representation is not.
 */
public final class JarPatcher {
    static final String CLASS_PATH = "org/jackhuang/hmcl/util/RegionUtils.class";
    static final String PROPERTIES_PATH = "assets/hmcl.properties";

    public record Result(byte[] jar, String version) {
    }

    private record Entry(String name, long time, byte[] data, boolean directory) {
    }

    private JarPatcher() {
    }

    public static Result patch(byte[] jarBytes, Consumer<String> log) throws Exception {
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (!names.add(name)) {
                    throw new IOException("jar 中存在重复条目：" + name);
                }
                entries.add(new Entry(name, entry.getTime(), Util.readAll(in), entry.isDirectory()));
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("jar 为空");
        }

        String version = null;
        boolean patched = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (PROPERTIES_PATH.equals(entry.name())) {
                version = readVersion(entry.data());
            }
            if (CLASS_PATH.equals(entry.name())) {
                log.accept("改写 " + CLASS_PATH);
                byte[] newClass = ClassPatcher.patch(entry.data());
                ClassPatcher.verifyPatched(newClass);
                entries.set(i, new Entry(entry.name(), entry.time(), newClass, false));
                patched = true;
            }
        }
        if (!patched) {
            throw new IOException("找不到 " + CLASS_PATH + "，这可能不是 HMCL 的 jar");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(jarBytes.length + 4096);
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            zout.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (Entry entry : entries) {
                ZipEntry ze = new ZipEntry(entry.name());
                if (entry.time() >= 0) {
                    ze.setTime(entry.time());
                }
                zout.putNextEntry(ze);
                if (!entry.directory()) {
                    zout.write(entry.data());
                }
                zout.closeEntry();
            }
        }

        byte[] result = out.toByteArray();
        verify(result);
        return new Result(result, version);
    }

    /**
     * Re-opens the rebuilt jar and checks that the target method is the patched constant.
     * This reads the archive the same way the JVM will, including self-extracting prefixes.
     */
    public static void verify(byte[] jarBytes) throws Exception {
        boolean found = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = Util.readAll(in);
                if (CLASS_PATH.equals(entry.getName())) {
                    ClassPatcher.verifyPatched(data);
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IOException("补丁后的 jar 里找不到 " + CLASS_PATH);
        }
    }

    private static String readVersion(byte[] properties) {
        String text = new String(properties, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            if (line.startsWith("hmcl.version=")) {
                return line.substring("hmcl.version=".length()).trim();
            }
        }
        return null;
    }
}
