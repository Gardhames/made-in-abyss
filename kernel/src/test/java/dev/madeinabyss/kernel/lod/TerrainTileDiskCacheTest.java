package dev.madeinabyss.kernel.lod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import static org.junit.jupiter.api.Assertions.*;
class TerrainTileDiskCacheTest {
 @TempDir Path directory;
 private final String identity="a".repeat(64);
 @Test void exactPartialNegativeTilesAndEmptyCellsSurviveRestart() throws Exception {
  var k=new TerrainTileDiskCache.Key(-17,-80,-33,-2,-17,-18,2);
  var boxes=List.of(new TerrainLodColumns.Box(-17,-80,-33,-15,-72,-31,TerrainMaterial.ROCK));
  var empty=new TerrainTileDiskCache.Key(0,0,0,15,63,15,1);
  var entries=Map.of(k,boxes,empty,List.<TerrainLodColumns.Box>of());
  TerrainTileDiskCache.save(directory,identity,entries);
  var read=TerrainTileDiskCache.load(directory,identity);
  assertEquals("HIT",read.status());assertEquals(entries,read.tiles());
  assertTrue(TerrainTileDiskCache.load(directory,"b".repeat(64)).tiles().isEmpty());
 }
 @Test void corruptionIsCacheMissAndCannotBecomeEmptyAuthoritativeWorld() throws Exception {
  TerrainTileDiskCache.save(directory,identity,Map.of());
  var path=TerrainLodDiskCache.file(directory,identity);var bytes=Files.readAllBytes(path);bytes[12]^=1;Files.write(path,bytes);
  var read=TerrainTileDiskCache.load(directory,identity);assertTrue(read.status().startsWith("INVALID"));assertTrue(read.tiles().isEmpty());
 }
 @Test void refusesOutOfBoundsOverlappingOrMisalignedBoxesBeforeReplacingGoodFile() throws Exception {
  TerrainTileDiskCache.save(directory,identity,Map.of());var before=Files.readAllBytes(TerrainLodDiskCache.file(directory,identity));
  var k=new TerrainTileDiskCache.Key(-16,-64,-16,-1,-1,-1,2);
  var outside=new TerrainLodColumns.Box(-17,-64,-16,-15,-63,-14,TerrainMaterial.ROCK);
  assertThrows(IllegalArgumentException.class,()->TerrainTileDiskCache.save(directory,identity,Map.of(k,List.of(outside))));
  var valid=new TerrainLodColumns.Box(-16,-64,-16,-14,-60,-14,TerrainMaterial.ROCK);
  assertThrows(IllegalArgumentException.class,()->TerrainTileDiskCache.save(directory,identity,Map.of(k,List.of(valid,valid))));
  assertArrayEquals(before,Files.readAllBytes(TerrainLodDiskCache.file(directory,identity)));
 }
 @Test void extremeCoordinatesStayDistinctAndInvalidKeysRefuse(){
  assertNotEquals(new TerrainTileDiskCache.Key(Integer.MIN_VALUE,-64,0,Integer.MIN_VALUE+15,-1,15,1),new TerrainTileDiskCache.Key(0,-64,0,15,-1,15,1));
  assertThrows(IllegalArgumentException.class,()->new TerrainTileDiskCache.Key(0,0,0,16,63,15,1));
  assertThrows(IllegalArgumentException.class,()->new TerrainTileDiskCache.Key(0,0,0,15,63,15,3));
 }
}
