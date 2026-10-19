package dev.madeinabyss.forge.cube;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;

/** Complete named properties; registry defaults never substitute unavailable names or values. */
final class StableBlockStatePalette {
    private StableBlockStatePalette() {}

    static void writeState(DataOutputStream out, BlockState state) throws IOException {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IOException("unregistered block in stable palette");
        writeText(out, id.toString(), 256);
        var properties = state.getProperties().stream().sorted(Comparator.comparing(Property::getName)).toList();
        if (properties.size() > 64) throw new IOException("stable state property budget exceeded");
        out.writeByte(properties.size());
        for (Property<?> property : properties) {
            writeText(out, property.getName(), 64);
            writeText(out, propertyValue(state, property), 128);
        }
    }

    static BlockState readState(DataInputStream input) throws IOException {
        String name = readText(input, 256);
        var id = ResourceLocation.tryParse(name);
        if (id == null || !id.toString().equals(name) || !BuiltInRegistries.BLOCK.containsKey(id))
            throw new IOException("unknown or noncanonical stable block name: " + name);
        var block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = block.defaultBlockState();
        int count = input.readUnsignedByte();
        if (count > 64 || count != state.getProperties().size()) throw new IOException("incomplete stable block property set: " + name);
        var seen = new HashSet<String>();
        for (int index = 0; index < count; index++) {
            String propertyName = readText(input, 64);
            String value = readText(input, 128);
            Property<?> property = block.getStateDefinition().getProperty(propertyName);
            if (property == null || !seen.add(propertyName)) throw new IOException("unknown or duplicate stable block property: " + propertyName);
            state = setProperty(state, property, value);
        }
        return state;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String text) throws IOException {
        T value = property.getValue(text).orElseThrow(() -> new IOException("unknown stable property value: " + property.getName() + "=" + text));
        if (!property.getName(value).equals(text)) throw new IOException("noncanonical stable property value");
        return state.setValue(property, value);
    }

    static void writeText(DataOutputStream output, String text, int limit) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > limit) throw new IOException("stable text length outside budget");
        output.writeShort(bytes.length); output.write(bytes);
    }
    static String readText(DataInputStream input, int limit) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > limit) throw new IOException("stable text length outside budget");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("invalid stable UTF-8 text", malformed);
        }
    }
}
