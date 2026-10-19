# Third-party notices and asset provenance

The [All Rights Reserved notice](LICENSE) applies only to original project material. It does not relicense dependencies or claim their trademarks and assets.

## Included files

| Item | Provenance and terms |
| --- | --- |
| Gradle wrapper scripts and `gradle-wrapper.jar` | Gradle 8.8 wrapper from the official Forge 1.20.1-47.4.20 MDK; Apache License 2.0. The license is in `third_party/gradle-wrapper/LICENSE`. |
| Compressed island height field | Original procedural island reconstruction generated for this project, not an extracted game/anime asset. Its adjacent recipe records dimensions, encoding, and SHA256 digests. Covered by the project ARR notice. |
| Empty GameTest `.nbt` structures | Original air-only templates; reproducible with `scripts/create_probe_structure.py`. Covered by the project ARR notice. |
| Compute shaders | Original exact-light implementation, not a redistributed shader pack. Covered by the project ARR notice. |

No manga/anime reference images or third-party textures are included. The two published images are approved derivatives of one project gameplay capture. Only the top diagnostic-text strip was removed; the README image was resized, and the social preview was resized and padded without further cropping. No generative editing was used. The capture depicts Minecraft and separately installed rendering integrations; those rendered elements are not relicensed by the code notice.

## Dependencies and optional integrations

Dependencies are fetched from upstream repositories. Their binaries are not committed or embedded by this source-publication process.

- [Minecraft](https://www.minecraft.net/) and Mojang official mappings: their respective terms.
- [Minecraft Forge](https://github.com/MinecraftForge/MinecraftForge) and [ForgeGradle](https://github.com/MinecraftForge/ForgeGradle): upstream terms.
- [Gradle](https://github.com/gradle/gradle): Apache-2.0.
- [fastutil](https://github.com/vigna/fastutil): Apache-2.0.
- [JUnit 5](https://github.com/junit-team/junit5): EPL-2.0.
- [LWJGL](https://github.com/LWJGL/lwjgl3): BSD-3-Clause; supplied through Minecraft runtime dependencies.
- [Distant Horizons](https://modrinth.com/mod/distanthorizons): upstream terms; its public API is compile-only and is not embedded.
- [Alex's Caves](https://modrinth.com/mod/alexs-caves) and [Citadel](https://modrinth.com/mod/citadel): optional upstream projects.
- [Embeddium](https://modrinth.com/mod/embeddium), [Oculus](https://modrinth.com/mod/oculus), [ModernFix](https://modrinth.com/mod/modernfix), [FerriteCore](https://modrinth.com/mod/ferrite-core), and [ImmediatelyFast](https://modrinth.com/mod/immediatelyfast): optional upstream projects.

See [integration identifiers](docs/public/integrations.md) for exact pins. No shader pack is distributed. Obtain optional components from their official authors and follow their terms.

## Unofficial fan project

*Made in Abyss* and associated intellectual property belong to their respective rights holders, including Akihito Tsukushi and Takeshobo. Minecraft belongs to Mojang/Microsoft. This project is unofficial and is not endorsed by or affiliated with those rights holders. CANON, RECONSTRUCTION, and ORIGINAL describe provenance; they do not grant redistribution rights.
