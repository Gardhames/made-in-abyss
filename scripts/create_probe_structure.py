"""Generate the owned empty GameTest template; Minecraft loads it in integration tests."""
import gzip
import struct
from pathlib import Path


def name(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


root = b"\x0a\x00\x00"
root += b"\x03" + name("DataVersion") + struct.pack(">i", 3465)
root += b"\x09" + name("size") + b"\x03" + struct.pack(">iiii", 3, 3, 3, 3)
root += b"\x09" + name("palette") + b"\x0a" + struct.pack(">i", 1)
root += b"\x08" + name("Name") + name("minecraft:air") + b"\x00"
for list_name in ("blocks", "entities"):
    root += b"\x09" + name(list_name) + b"\x0a" + struct.pack(">i", 0)
root += b"\x00"
destination = Path(__file__).resolve().parents[1] / "forge/src/main/resources/data/madeinabyss/structures/empty.nbt"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_bytes(gzip.compress(root, mtime=0))
print(destination)
