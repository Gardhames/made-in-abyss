package dev.madeinabyss.forge.probe.client.compat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Benchmark-only UTF-8/LF oracle; sorted owners spill without retaining the whole scene. */
final class LodSpeedCanonicalDigest {
    private LodSpeedCanonicalDigest() { }
    static final class Stream {
        private final MessageDigest digest;
        private boolean finished;
        Stream() {
            try { digest=MessageDigest.getInstance("SHA-256"); }
            catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
        }
        void add(String line) {
            if(finished)throw new IllegalStateException("Digest already finished");
            digest.update(line.getBytes(StandardCharsets.UTF_8));digest.update((byte)'\n');
        }
        String finish() {
            if(finished)throw new IllegalStateException("Digest already finished");
            finished=true;return HexFormat.of().formatHex(digest.digest());
        }
    }
    record Result(String sha256,int count) { }
    /** Lines are newline-free Owner.toString records; ordering is Java String natural order. */
    static final class SortedUnique implements AutoCloseable {
        private record Head(String line,int run) { }
        private final Path root;
        private final int maximumRecords;
        private final TreeSet<String> pending=new TreeSet<>();
        private final ArrayList<Path> files=new ArrayList<>();
        private Path directory;
        private boolean finished,closed;
        SortedUnique(Path root,int maximumRecords) {
            this.root=Objects.requireNonNull(root);
            if(maximumRecords<1)throw new IllegalArgumentException("Positive oracle spill threshold required");
            this.maximumRecords=maximumRecords;
        }
        void add(String line)throws IOException {
            if(finished||closed)throw new IllegalStateException("Owner digest closed");
            Objects.requireNonNull(line);
            if(line.indexOf('\n')>=0||line.indexOf('\r')>=0)throw new IllegalArgumentException("Owner record contains a line break");
            pending.add(line);if(pending.size()>=maximumRecords)spill();
        }
        int spillCount() { return files.size(); }
        private void spill()throws IOException {
            if(pending.isEmpty())return;
            if(directory==null){Files.createDirectories(root);directory=Files.createTempDirectory(root,"oracle-owners-");}
            var file=Files.createTempFile(directory,"sorted-",".utf8");files.add(file);
            try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {
                for(var line:pending){writer.write(line);writer.write('\n');}
            }
            pending.clear();
        }
        Result finish()throws IOException {
            if(finished||closed)throw new IllegalStateException("Owner digest already finished");
            finished=true;var digest=new Stream();
            if(files.isEmpty()){for(var line:pending)digest.add(line);return new Result(digest.finish(),pending.size());}
            spill();
            var readers=new ArrayList<BufferedReader>();
            var heads=new PriorityQueue<Head>(Comparator.comparing(Head::line).thenComparingInt(Head::run));
            Throwable primary=null;
            try {
                for(var file:files){int id=readers.size();var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8);readers.add(reader);
                    var line=reader.readLine();if(line!=null)heads.add(new Head(line,id));}
                String previous=null;int count=0;
                while(!heads.isEmpty()) {
                    var head=heads.remove();
                    if(!head.line().equals(previous)){digest.add(head.line());previous=head.line();count=Math.incrementExact(count);}
                    var line=readers.get(head.run()).readLine();if(line!=null)heads.add(new Head(line,head.run()));
                }
                return new Result(digest.finish(),count);
            } catch(IOException|RuntimeException|Error error) {
                primary=error;throw error;
            } finally {
                IOException failure=null;
                for(var reader:readers)try{reader.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
                if(failure!=null){if(primary!=null)primary.addSuppressed(failure);else throw failure;}
            }
        }
        @Override public void close()throws IOException {
            if(closed)return;closed=true;pending.clear();IOException failure=null;
            // Only paths created by this instance are removed; never recursively delete a root.
            for(var file:files)try{Files.deleteIfExists(file);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(directory!=null)try{Files.deleteIfExists(directory);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(failure!=null)throw failure;
        }
    }
}
