package dev.madeinabyss.kernel.cube;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

/** Versioned diagnostic snapshots; not an Anvil replacement or a production world format. */
public final class FileCubeStore implements CubeStore {
    private static final int MAGIC = 0x4D494133; // MIA3
    private static final int VERSION = 1;
    private static final int PAYLOAD_BYTES = 24 + CubeSnapshot.BLOCK_COUNT * Integer.BYTES;
    private static final int FILE_BYTES = PAYLOAD_BYTES + Integer.BYTES;
    private final Path root;

    public FileCubeStore(Path root) { this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); }

    public Optional<CubeSnapshot> read(CubeKey key) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path(key))) {
            // Bound reads even if a damaged file is unexpectedly enormous.
            bytes = input.readNBytes(FILE_BYTES + 1);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        if (bytes.length != FILE_BYTES) throw new IOException("invalid cube snapshot length: " + bytes.length);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("unrecognized cube format");
            int version = input.readInt();
            if (version != VERSION) throw new IOException("unsupported cube version: " + version);
            var recorded = new CubeKey(input.readInt(), input.readInt(), input.readInt());
            if (!recorded.equals(key)) throw new IOException("cube coordinate mismatch: " + recorded + " vs " + key);
            if (input.readInt() != CubeSnapshot.BLOCK_COUNT) throw new IOException("invalid cube state count");
            int[] states = new int[CubeSnapshot.BLOCK_COUNT];
            for (int index = 0; index < states.length; index++) states[index] = input.readInt();
            var checksum = new CRC32();
            checksum.update(bytes, 0, PAYLOAD_BYTES);
            if (input.readInt() != (int) checksum.getValue()) throw new IOException("cube checksum mismatch: " + key);
            try {
                return Optional.of(new CubeSnapshot(recorded, states));
            } catch (IllegalArgumentException invalidStates) {
                throw new IOException("invalid cube states", invalidStates);
            }
        }
    }

    public void write(CubeSnapshot snapshot) throws IOException {
        // Numeric diagnostic v1 has no tick tuple; refusing it preserves both the old file and resident work.
        if (!snapshot.ticks().isEmpty()) throw new IOException("numeric cube format cannot persist scheduled ticks");
        if (!snapshot.blockEntities().isEmpty()) throw new IOException("numeric cube format cannot persist block entities");
        var bytes = new ByteArrayOutputStream(FILE_BYTES);
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(snapshot.key().x());
            output.writeInt(snapshot.key().y());
            output.writeInt(snapshot.key().z());
            output.writeInt(CubeSnapshot.BLOCK_COUNT);
            for (int state : snapshot.states()) output.writeInt(state);
            var checksum = new CRC32();
            checksum.update(bytes.toByteArray());
            output.writeInt((int) checksum.getValue());
        }
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "cube-", ".pending");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // An unsupported atomic move is a save error; retain the resident cube instead of hiding it.
            Files.move(temporary, path(snapshot.key()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path path(CubeKey key) {
        return root.resolve("c." + key.x() + "." + key.y() + "." + key.z() + ".mia3");
    }
}
