package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.cube.SavedCubeBlockEntity;
import dev.madeinabyss.kernel.BlockCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Bounded native full-metadata chest codec shared by runtime and persistent store. */
public final class ChestBlockEntityCodec {
    private ChestBlockEntityCodec() { }
    public static SavedCubeBlockEntity encode(ChestBlockEntity chest) throws IOException {
        try {
            var position = chest.getBlockPos();
            var tag = chest.saveWithFullMetadata();
            validateTree(tag, 0, new int[]{0});
            var bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(new OutputStream() {
                @Override public void write(int value) throws IOException {
                    reserve(1); bytes.write(value);
                }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    reserve(length); bytes.write(data,offset,length);
                }
                private void reserve(int length) throws IOException {
                    if (length < 0 || length > SavedCubeBlockEntity.MAX_PAYLOAD_BYTES - bytes.size())
                        throw new IOException("native chest payload exceeds byte budget");
                }
            }));
            var record = new SavedCubeBlockEntity(new BlockCoordinate(position.getX(),position.getY(),position.getZ()),"minecraft:chest",bytes.toByteArray());
            validateAndRead(record,chest.getBlockState());
            return record;
        } catch (RuntimeException invalid) {
            throw new IOException("invalid native chest save",invalid);
        }
    }
    public static ChestBlockEntity decode(SavedCubeBlockEntity saved, BlockState state) throws IOException {
        CompoundTag tag = validateAndRead(saved,state);
        try {
            var position = saved.position();
            var chest = new ChestBlockEntity(new BlockPos(position.x(),position.y(),position.z()),state);
            chest.load(tag);
            return chest;
        } catch (RuntimeException invalid) {
            throw new IOException("native chest failed to restore",invalid);
        }
    }
    public static CompoundTag validateAndRead(SavedCubeBlockEntity saved, BlockState state) throws IOException {
        if (!"minecraft:chest".equals(saved.type()) || !state.is(Blocks.CHEST)
                || state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
                || state.getValue(BlockStateProperties.WATERLOGGED))
            throw new IOException("only dry native single chest metadata is supported");
        byte[] data = saved.data();
        if (data.length < 1 || data.length > SavedCubeBlockEntity.MAX_PAYLOAD_BYTES) throw new IOException("invalid chest payload size");
        try (var input = new DataInputStream(new ByteArrayInputStream(data))) {
            CompoundTag tag = NbtIo.read(input,new NbtAccounter(1024L*1024));
            if (tag == null || input.available() != 0) throw new IOException("invalid or trailing native chest NBT");
            validateTree(tag,0,new int[]{0});
            if (!tag.contains("id",Tag.TAG_STRING) || !tag.getString("id").equals(saved.type())) throw new IOException("native chest NBT type mismatch");
            var position = saved.position();
            if (!tag.contains("x",Tag.TAG_INT) || !tag.contains("y",Tag.TAG_INT) || !tag.contains("z",Tag.TAG_INT)
                    || tag.getInt("x") != position.x() || tag.getInt("y") != position.y() || tag.getInt("z") != position.z())
                throw new IOException("native chest NBT coordinate mismatch");
            if (tag.contains("LootTable") || tag.contains("LootTableSeed")) throw new IOException("native chest loot table is unsupported");
            if (tag.contains("CustomName")) {
                if (!tag.contains("CustomName",Tag.TAG_STRING)) throw new IOException("invalid native chest custom name type");
                String name = tag.getString("CustomName");
                validateNameEnvelope(name);
                if (Component.Serializer.fromJson(name) == null)
                    throw new IOException("invalid native chest custom name");
            }
            if (tag.contains("Lock") && !tag.contains("Lock",Tag.TAG_STRING)) throw new IOException("invalid native chest lock");
            optionalCompound(tag,"ForgeData");
            optionalCompound(tag,"ForgeCaps");
            validateItems(tag);
            return tag;
        } catch (RuntimeException invalid) {
            throw new IOException("invalid or over-budget native chest NBT",invalid);
        }
    }

    private static void validateItems(CompoundTag root) throws IOException {
        if (!root.contains("Items",Tag.TAG_LIST)) throw new IOException("native chest lacks inventory list");
        var list = (ListTag) root.get("Items");
        if ((list.size() > 0 && list.getElementType() != Tag.TAG_COMPOUND) || list.size() > 27)
            throw new IOException("invalid native chest inventory structure");
        var slots = new HashSet<Integer>();
        for (Tag entry : list) {
            var item = (CompoundTag) entry;
            if (!item.contains("Slot",Tag.TAG_BYTE) || !item.contains("Count",Tag.TAG_BYTE) || !item.contains("id",Tag.TAG_STRING))
                throw new IOException("incomplete native chest item metadata");
            int slot = Byte.toUnsignedInt(item.getByte("Slot"));
            if (slot >= 27 || !slots.add(slot)) throw new IOException("invalid or duplicate native chest inventory slot");
            String name = item.getString("id");
            var id = ResourceLocation.tryParse(name);
            if (id == null || !id.toString().equals(name) || !BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.get(id) == Items.AIR)
                throw new IOException("unknown native chest item ID");
            int count = item.getByte("Count");
            if (count < 1 || count > BuiltInRegistries.ITEM.get(id).getMaxStackSize()) throw new IOException("invalid native chest item count");
            if (item.contains("tag") && !item.contains("tag",Tag.TAG_COMPOUND)) throw new IOException("invalid native chest nested item tag");
            optionalCompound(item,"ForgeCaps");
        }
    }

    private static void optionalCompound(CompoundTag tag, String key) throws IOException {
        if (tag.contains(key) && !tag.contains(key,Tag.TAG_COMPOUND)) throw new IOException("invalid native chest compound: " + key);
    }

    /** Physics mirrors carry metadata only within 4096 bytes; names must leave room for the native NBT envelope. */
    private static void validateNameEnvelope(String name) throws IOException {
        if (name.length() > 3072 || name.getBytes(StandardCharsets.UTF_8).length > 3072)
            throw new IOException("native chest custom name exceeds UTF-8 budget");
        int modifiedBytes = 0;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            // NBT uses DataOutput.writeUTF: supplementary code points use two three-byte surrogate code units.
            modifiedBytes += character == 0 || character > 0x7ff ? 3 : character > 0x7f ? 2 : 1;
            if (modifiedBytes > 3072) throw new IOException("native chest custom name exceeds modified UTF budget");
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{' || character == '[') {
                if (++depth > 32) throw new IOException("native chest custom name JSON nesting budget exceeded");
            } else if (character == '}' || character == ']') {
                if (--depth < 0) throw new IOException("invalid native chest custom name JSON envelope");
            }
        }
        if (quoted || escaped || depth != 0) throw new IOException("invalid native chest custom name JSON envelope");
    }

    private static void validateTree(Tag tag, int depth, int[] nodes) throws IOException {
        if (depth > 32 || ++nodes[0] > 4096) throw new IOException("native chest NBT nesting budget exceeded");
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) validateTree(compound.get(key),depth+1,nodes);
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) validateTree(child,depth+1,nodes);
        }
    }
}
