# Optional integrations

The project targets Forge, not Fabric. Do not substitute Fabric API, Loom, or Yarn into this build.

## Dependency pins

The authoritative definitions are in [forge/build.gradle](../../forge/build.gradle). Optional dependencies use `https://api.modrinth.com/maven`; their JARs are not committed.

| Component | Exact Maven pin | Activation |
| --- | --- | --- |
| DH API | `maven.modrinth:DistantHorizonsApi:7.2.0` | Compile-only, always needed for compilation |
| Distant Horizons | `maven.modrinth:distanthorizons:3.3.3-1.20.1` | `miaDistantHorizons=true` |
| Alex's Caves | `maven.modrinth:alexs-caves:lC8HHXOF` | `miaAlexCaves=true` |
| Citadel | `maven.modrinth:citadel:lTAAe4sZ` | `miaAlexCaves=true` |
| ModernFix | `maven.modrinth:modernfix:hHwYTQwa` | `miaOptimizationMods=true` |
| FerriteCore | `maven.modrinth:ferrite-core:DG5Fn9Sz` | `miaOptimizationMods=true` |
| ImmediatelyFast | `maven.modrinth:immediatelyfast:xNaL38Bs` | `miaOptimizationMods=true` |
| Embeddium | `maven.modrinth:embeddium:UTbfe5d1` | `miaShaderPipeline=true` |
| Oculus | `maven.modrinth:oculus:iQ1SwGc3` | `miaShaderPipeline=true` |

The pinned upstream versions are Alex's Caves 2.0.2, Citadel 2.6.3, ModernFix 5.27.85+mc1.20.1, FerriteCore 6.0.1, ImmediatelyFast 1.5.0+1.20.4-forge (also declares Minecraft 1.20.1 support), Embeddium 0.3.31+mc1.20.1, and Oculus 1.20.1-1.8.0. These names were checked against the version IDs using the official Modrinth API.

Identifier-like pins are fixed Modrinth version IDs, not placeholders. Upstream links are in [third-party notices](../../THIRD_PARTY_NOTICES.md).

## DH surfaces

The default DH pipeline is `legacy`. Set `miaDhLodPipeline=surfaces` and `miaDhSurfaceMesh=true` together for the custom surface path used by recent GPU-light work. DH must also be enabled. Adding the dependency alone does not select this path.

## Shaders

`miaShaderPipeline=true` adds Embeddium and Oculus. It does not download a shader pack. Shader packs are separate user installations with their own terms; no original or modified pack is distributed here.

Compatibility is specific to tested versions, renderer paths, profiles, and flags. Changing the shader or native draw window changes the workload and is not an equivalent performance measurement.
