package org.jackhuang.hmcl.uncut.patcher;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles Debian packages. A .deb contains {@code data.tar.gz} (or .xz), and the HMCL package ships
 * the self-extracting {@code .sh} launcher inside it, so the deb is rewritten by patching that entry
 * and rebuilding the tar and the outer ar archive.
 */
public final class DebPatcher {
    public record Result(byte[] data, String version) {
    }

    private record ArEntry(byte[] header, String name, byte[] data) {
    }

    private record TarItem(String name, boolean directory, int mode, long time,
                           String user, String group, byte[] data) {
    }

    private record TarResult(byte[] data, String version) {
    }

    private DebPatcher() {
    }

    public static Result patch(byte[] deb, Consumer<String> log) throws Exception {
        List<ArEntry> entries = parseAr(deb);

        String version = null;
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            ArEntry entry = entries.get(i);
            if (!entry.name().startsWith("data.tar")) {
                continue;
            }
            log.accept("展开 " + entry.name());
            TarResult tar = patchDataTar(entry.name(), entry.data(), log);
            version = tar.version();
            entries.set(i, new ArEntry(entry.header(), entry.name(), tar.data()));
            found = true;
        }

        if (!found) {
            throw new IOException("deb 里找不到 data.tar.*，这可能不是一个完整的 Debian 包");
        }
        return new Result(writeAr(entries), version);
    }

    private static List<ArEntry> parseAr(byte[] deb) throws IOException {
        List<ArEntry> entries = new ArrayList<>();
        int pos = 8;
        while (pos + 60 <= deb.length) {
            String header = new String(deb, pos, 60, StandardCharsets.US_ASCII);
            String name = header.substring(0, 16).trim();
            String sizeText = header.substring(48, 58).trim();
            if (name.isEmpty() || sizeText.isEmpty()) {
                break;
            }

            int size = Integer.parseInt(sizeText);
            int dataStart = pos + 60;
            if (dataStart + size > deb.length) {
                throw new IOException("deb 结构损坏：" + name);
            }

            entries.add(new ArEntry(
                    Arrays.copyOfRange(deb, pos, pos + 60),
                    name,
                    Arrays.copyOfRange(deb, dataStart, dataStart + size)));

            pos = dataStart + size + (size % 2);
        }
        if (entries.isEmpty()) {
            throw new IOException("deb 里没有 ar 条目");
        }
        return entries;
    }

    private static byte[] writeAr(List<ArEntry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        for (ArEntry entry : entries) {
            byte[] header = entry.header().clone();
            byte[] size = String.format("%-10d", entry.data().length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size, 0, header, 48, 10);
            out.write(header);
            out.write(entry.data());
            if (entry.data().length % 2 == 1) {
                out.write('\n');
            }
        }
        return out.toByteArray();
    }

    private static TarResult patchDataTar(String name, byte[] data, Consumer<String> log) throws Exception {
        boolean gzip = name.endsWith(".gz");
        boolean xz = name.endsWith(".xz");
        if (!gzip && !xz) {
            throw new IOException("不支持的 data.tar 压缩格式：" + name);
        }

        List<TarItem> items = new ArrayList<>();
        String version = null;

        try (InputStream raw = new ByteArrayInputStream(data);
             InputStream decompressed = gzip ? new GzipCompressorInputStream(raw) : new XZCompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {

            ArchiveEntry archiveEntry;
            while ((archiveEntry = tar.getNextEntry()) != null) {
                if (!(archiveEntry instanceof TarArchiveEntry entry)) {
                    break;
                }
                if (!entry.isFile() && !entry.isDirectory()) {
                    throw new IOException("data.tar 里有不支持的条目类型：" + entry.getName());
                }

                byte[] content = entry.isDirectory() ? new byte[0] : tar.readAllBytes();
                if (entry.isFile() && content.length > 1_000_000 && entry.getName().endsWith(".sh")) {
                    log.accept("改写 " + entry.getName());
                    Patcher.Result patched = Patcher.patch(content, log);
                    content = patched.data();
                    version = patched.version();
                }

                long time = entry.getModTime() == null ? 0L : entry.getModTime().getTime();
                items.add(new TarItem(entry.getName(), entry.isDirectory(), entry.getMode(), time,
                        entry.getUserName(), entry.getGroupName(), content));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        try (OutputStream compressed = gzip ? new GzipCompressorOutputStream(out) : new XZCompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {

            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            for (TarItem item : items) {
                TarArchiveEntry entry = new TarArchiveEntry(item.name());
                entry.setMode(item.mode());
                entry.setModTime(item.time());
                if (item.user() != null && !item.user().isEmpty()) {
                    entry.setUserName(item.user());
                }
                if (item.group() != null && !item.group().isEmpty()) {
                    entry.setGroupName(item.group());
                }
                entry.setSize(item.directory() ? 0 : item.data().length);

                tar.putArchiveEntry(entry);
                if (!item.directory()) {
                    tar.write(item.data());
                }
                tar.closeArchiveEntry();
            }
        }

        return new TarResult(out.toByteArray(), version);
    }
}
