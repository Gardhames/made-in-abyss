package dev.madeinabyss.forge.cube;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

/** One acknowledged atomic MIEP frame per managed UUID; independent of vanilla entity regions. */
public final class ManagedItemStore implements ManagedItemPersistence {
    public static final int MAX_FILE_BYTES = 65569;
    private static final int MAGIC=0x4d494550;
    private final Path root;
    public ManagedItemStore(Path root) { this.root=Objects.requireNonNull(root).toAbsolutePath().normalize(); }
    @Override public Optional<ManagedItemSnapshot> load(UUID uuid) throws IOException {
        byte[] frame;
        try(var in=Files.newInputStream(file(uuid))) { frame=in.readNBytes(MAX_FILE_BYTES+1); }
        catch(NoSuchFileException absent) { return Optional.empty(); }
        if(frame.length<33 || frame.length>MAX_FILE_BYTES) throw new IOException("invalid managed item frame size");
        var crc=new CRC32(); crc.update(frame,0,frame.length-4);
        if(ByteBuffer.wrap(frame).getInt(frame.length-4)!=(int)crc.getValue()) throw new IOException("managed item frame CRC mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(frame,0,frame.length-4))) {
            if(in.readInt()!=MAGIC || in.readInt()!=1) throw new IOException("unsupported managed item frame");
            var recorded=new UUID(in.readLong(),in.readLong());
            if(!recorded.equals(uuid)) throw new IOException("managed item frame UUID mismatch");
            int state=in.readUnsignedByte(); int size=in.readInt();
            if(state==0) {
                if(size!=0 || in.available()!=0) throw new IOException("invalid managed item tombstone");
                return Optional.empty();
            }
            if(state!=1 || size<1 || size>ManagedItemCodec.MAX_PAYLOAD_BYTES || size!=in.available())
                throw new IOException("invalid managed item payload envelope");
            byte[] data=new byte[size]; in.readFully(data);
            ManagedItemCodec.readAndValidate(uuid,data);
            return Optional.of(new ManagedItemSnapshot(uuid,data));
        } catch(RuntimeException invalid) { throw new IOException("invalid managed item frame",invalid); }
    }
    @Override public void save(ManagedItemSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot,"snapshot");
        byte[] data=snapshot.data(); ManagedItemCodec.readAndValidate(snapshot.uuid(),data);
        load(snapshot.uuid()); // Never overwrite existing corrupt/unknown evidence.
        replace(snapshot.uuid(),1,data);
    }
    @Override public void erase(UUID uuid) throws IOException {
        load(uuid); // An unreadable record cannot silently become a tombstone.
        replace(uuid,0,new byte[0]);
    }
    @Override public Path file(UUID uuid) { return root.resolve("e."+Objects.requireNonNull(uuid)+".miaentity"); }
    private void replace(UUID uuid,int state,byte[] data) throws IOException {
        byte[] frame=new byte[data.length+33]; var out=ByteBuffer.wrap(frame);
        out.putInt(MAGIC).putInt(1).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits())
                .put((byte)state).putInt(data.length).put(data);
        var crc=new CRC32(); crc.update(frame,0,frame.length-4); out.putInt((int)crc.getValue());
        Files.createDirectories(root); Path pending=Files.createTempFile(root,"entity-",".pending");
        try {
            try(var channel=FileChannel.open(pending,StandardOpenOption.WRITE)) {
                var bytes=ByteBuffer.wrap(frame); while(bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            Files.move(pending,file(uuid),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }
}
