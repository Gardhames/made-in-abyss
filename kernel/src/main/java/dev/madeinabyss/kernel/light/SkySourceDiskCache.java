package dev.madeinabyss.kernel.light;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, disposable snapshots of exact source lighting queries. */
public final class SkySourceDiskCache {
    public static final int POINT_LIMIT = 262144;
    public static final int COLUMN_LIMIT = 131072;
    private static final long BYTE_LIMIT = 32L * 1024 * 1024;
    private static final int MAGIC = 0x534b5943, VERSION = 1;
    private SkySourceDiskCache() {}

    public record Visibility(int x, int y, int z, double fraction) {
        public Visibility {
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                throw new IllegalArgumentException("Invalid sky fraction");
        }
    }
    public record Exposure(int x, int z, int firstY, byte[] attenuation, int rainY) {
        public Exposure {
            Objects.requireNonNull(attenuation);
            if (attenuation.length > 15) throw new IllegalArgumentException("Exposure too long");
            attenuation = attenuation.clone();
            for (byte value : attenuation) if (value < 0 || value > 15)
                throw new IllegalArgumentException("Invalid attenuation");
        }
        @Override public byte[] attenuation() { return attenuation.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Exposure e && x == e.x && z == e.z && firstY == e.firstY
                && rainY == e.rainY && Arrays.equals(attenuation, e.attenuation);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(x, z, firstY, rainY) + Arrays.hashCode(attenuation);
        }
    }
    public record Entry(int minY, int maxY, List<Visibility> visibility, List<Exposure> exposure) {
        public Entry {
            domain(minY, maxY);
            if (visibility.size() > POINT_LIMIT || exposure.size() > COLUMN_LIMIT)
                throw new IllegalArgumentException("Snapshot count limit");
            visibility = List.copyOf(visibility); exposure = List.copyOf(exposure);
            Set<Point> points = new HashSet<>(); Set<Column> columns = new HashSet<>();
            for (Visibility v : visibility) {
                if (v.y < minY || v.y >= maxY || !points.add(new Point(v.x, v.y, v.z)))
                    throw new IllegalArgumentException("Invalid or duplicate visibility");
            }
            for (Exposure e : exposure) {
                if ((long)e.firstY < (long)minY - 1 || e.firstY >= maxY
                    || e.rainY < minY || e.rainY > maxY || !columns.add(new Column(e.x, e.z)))
                    throw new IllegalArgumentException("Invalid or duplicate exposure");
            }
        }
    }
    public record Read(Optional<Entry> entry, String status) {}
    private record Point(int x, int y, int z) {}
    private record Column(int x, int z) {}

    public static Read read(Path directory, String identity, int minY, int maxY) {
        key(identity); domain(minY, maxY);
        Path file = slot(directory, identity);
        try {
            if (!Files.exists(file)) return miss("MISS");
            if (Files.size(file) > BYTE_LIMIT) return miss("OVERSIZE");
            try (InputStream compressed = new LimitedInput(Files.newInputStream(file));
                 InputStream raw = new LimitedInput(new BufferedInputStream(new GZIPInputStream(compressed), 65536))) {
                MessageDigest digest = sha();
                DigestInputStream hashing = new DigestInputStream(raw, digest);
                DataInputStream in = new DataInputStream(hashing);
                if (in.readInt() != MAGIC || in.readInt() != VERSION) return miss("FORMAT_MISS");
                byte[] storedKey = new byte[64]; in.readFully(storedKey);
                if (!identity.equals(new String(storedKey, StandardCharsets.US_ASCII))) return miss("KEY_MISS");
                if (in.readInt() != minY || in.readInt() != maxY) return miss("DOMAIN_MISS");
                int points = in.readInt(), columns = in.readInt();
                if (points < 0 || points > POINT_LIMIT || columns < 0 || columns > COLUMN_LIMIT)
                    return miss("COUNT_LIMIT");
                List<Visibility> visibility = new ArrayList<>(points);
                List<Exposure> exposure = new ArrayList<>(columns);
                for (int i = 0; i < points; i++) visibility.add(new Visibility(in.readInt(), in.readInt(), in.readInt(), in.readDouble()));
                for (int i = 0; i < columns; i++) {
                    int x = in.readInt(), z = in.readInt(), firstY = in.readInt(), rainY = in.readInt();
                    int length = in.readUnsignedByte();
                    if (length > 15) return miss("CORRUPT");
                    byte[] values = new byte[length]; in.readFully(values);
                    exposure.add(new Exposure(x, z, firstY, values, rainY));
                }
                byte[] actual = digest.digest(); hashing.on(false);
                byte[] expected = new byte[32]; in.readFully(expected);
                if (!MessageDigest.isEqual(actual, expected) || in.read() != -1) return miss("CORRUPT");
                return new Read(Optional.of(new Entry(minY, maxY, visibility, exposure)), "HIT");
            }
        } catch (IOException | IllegalArgumentException e) { return miss("CORRUPT"); }
    }

    public static void write(Path directory, String identity, Entry entry) throws IOException {
        key(identity); Objects.requireNonNull(entry);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".sky-", ".tmp");
        try {
            try (OutputStream compressed = new LimitedOutput(Files.newOutputStream(temporary));
                 GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                MessageDigest digest = sha();
                DigestOutputStream hashing = new DigestOutputStream(new BufferedOutputStream(new LimitedOutput(gzip), 65536), digest);
                DataOutputStream out = new DataOutputStream(hashing);
                out.writeInt(MAGIC); out.writeInt(VERSION);
                out.write(identity.getBytes(StandardCharsets.US_ASCII));
                out.writeInt(entry.minY); out.writeInt(entry.maxY);
                out.writeInt(entry.visibility.size()); out.writeInt(entry.exposure.size());
                for (Visibility v : entry.visibility) {
                    out.writeInt(v.x); out.writeInt(v.y); out.writeInt(v.z); out.writeDouble(v.fraction);
                }
                for (Exposure e : entry.exposure) {
                    out.writeInt(e.x); out.writeInt(e.z); out.writeInt(e.firstY); out.writeInt(e.rainY);
                    out.writeByte(e.attenuation.length); out.write(e.attenuation);
                }
                byte[] checksum = digest.digest(); hashing.on(false); out.write(checksum); out.flush();
            }
            try { Files.move(temporary, slot(directory, identity), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, slot(directory, identity), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Read miss(String status) { return new Read(Optional.empty(), status); }
    private static void domain(int minY, int maxY) {
        if (minY >= maxY) throw new IllegalArgumentException("Empty lighting domain");
    }
    private static void key(String identity) {
        if (identity == null || !identity.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA-256 identity");
    }
    private static Path slot(Path directory, String identity) {
        return directory.resolve("slot-" + (Character.digit(identity.charAt(63), 16) & 7) + ".sky.gz");
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static final class LimitedInput extends FilterInputStream {
        private long count;
        LimitedInput(InputStream in) { super(in); }
        private void consumed(int n) throws IOException {
            if (n > 0 && (count += n) > BYTE_LIMIT) throw new IOException("Cache byte limit");
        }
        @Override public int read() throws IOException { int b = in.read(); consumed(b < 0 ? 0 : 1); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, (int)Math.min(len, BYTE_LIMIT - count + 1)); consumed(n); return n;
        }
    }
    private static final class LimitedOutput extends FilterOutputStream {
        private long count;
        LimitedOutput(OutputStream out) { super(out); }
        private void reserve(int n) throws IOException {
            if ((count += n) > BYTE_LIMIT) throw new IOException("Cache byte limit");
        }
        @Override public void write(int b) throws IOException { reserve(1); out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { reserve(len); out.write(b, off, len); }
    }
}
