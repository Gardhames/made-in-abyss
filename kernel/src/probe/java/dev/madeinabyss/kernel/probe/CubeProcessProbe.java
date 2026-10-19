package dev.madeinabyss.kernel.probe;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.FileCubeStore;
import dev.madeinabyss.kernel.cube.PersistentCubeSpace;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Reproducible diagnostic using two JVMs; deliberately not shipped in the mod JAR. */
public final class CubeProcessProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("write") || arguments[0].equals("read"))) {
            throw new IllegalArgumentException("expected write|read and an isolated snapshot directory");
        }
        String phase = arguments[0];
        Path root = Path.of(arguments[1]).toAbsolutePath().normalize();
        long pid = ProcessHandle.current().pid();
        if (phase.equals("write")) {
            // Never silently overwrite an earlier experiment or its saved construction.
            Files.createDirectory(root);
            Files.writeString(root.resolve("writer.pid"), Long.toString(pid), StandardCharsets.UTF_8);
        } else {
            long writer = Long.parseLong(Files.readString(root.resolve("writer.pid"), StandardCharsets.UTF_8));
            if (writer == pid) throw new IllegalStateException("reader must be a different JVM process");
        }
        var store = new FileCubeStore(root);
        var space = new SparseCubeSpace();
        var manager = new PersistentCubeSpace(space, store);
        int[] depths = {64, -4096, -15904, -20000};
        var rows = new ArrayList<String>();
        long fileBytes = 0;
        for (int index = 0; index < depths.length; index++) {
            var position = new BlockCoordinate(-17, depths[index], -1);
            var key = CubeKey.fromBlock(position);
            if (space.block(position).isPresent()) throw new IllegalStateException("initial region should be unloaded");
            manager.load(key);
            if (phase.equals("write")) {
                space.setBlock(position, 101 + index);
                if (!manager.saveAndUnload(key)) throw new IllegalStateException("save must unload existing cube");
                if (space.block(position).isPresent()) throw new IllegalStateException("saved region remained loaded");
            } else if (space.block(position).orElseThrow() != 101 + index) {
                throw new IllegalStateException("persisted state mismatch at " + position);
            }
            fileBytes += Files.size(store.path(key));
            rows.add("{\"y\":" + position.y() + ",\"cube_y\":" + key.y() + ",\"state\":" + (101 + index) + "}");
        }
        String report = "{\n  \"status\":\"PASSED\",\n  \"scope\":\"Standalone cube storage, no Minecraft world\",\n"
                + "  \"phase\":\"" + phase + "\",\n  \"pid\":" + pid + ",\n"
                + "  \"loaded_cubes\":" + space.loadedCubeCount() + ",\n  \"snapshot_bytes\":" + fileBytes + ",\n"
                + "  \"markers\":[" + String.join(",", rows) + "]\n}\n";
        Files.writeString(root.resolve(phase + ".json"), report, StandardCharsets.UTF_8);
        System.out.print(report);
    }
}
