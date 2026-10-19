# Status and acceptance boundaries

Status date: **2026-10-20**. This is an experimental engine and terrain probe, not a complete survival/adventure release.

## Observed before the initial source publication

| Scope | Evidence and limit |
| --- | --- |
| GPU kernel differential | 1,508,192 sampled owners matched CPU exact SKY for the checked scene |
| Standalone GPU controls | Axis/rim/m600 about 1.40/1.38/1.69 seconds with a prebuilt volume; excludes client feed/publication |
| Actual client axis run | Geometry acknowledgement about 144 seconds; exact completion about 56 seconds later; zero remaining owners; clean +60 second frame |
| Client FPS | Roughly 119 before refinement, 91-99 while refining, then about 106; no separate frame-overhead acceptance inferred |
| Explicit GPU buffers | 617,200,304 bytes in the checked G3 configuration; process VRAM is broader |
| Cold optical volume | About 188 seconds on 12 workers; target of at most 60 seconds remains open |
| Local edit foundations | Targeted kernel contracts tested; GPU transaction/client edit integration unwired |

These observations used controlled local fixtures. Worlds, optical snapshots, original captures, and internal reports are not redistributed. Results do not certify every machine, camera, profile, or multiplayer setup.

## Open before G3 acceptance

- Local updates of edited 16-cubed bricks, their halo/dependencies, and parent LOD regions while preserving the rest of the GPU volume and light.
- Equality of a local GPU patch with independent full recomputation.
- Client block-break/place acceptance without global CPU fallback.
- Full G3 and CPU provisional after-acknowledgement regression.

The GPU kernel has intentionally unfinished edit entry points. Do not call them as though they were implemented.

## Roadmap

1. Finish G3 incremental edits and regression.
2. Improve the cold hierarchical volume producer, targeting at most 60 seconds.
3. Build future GPU LOD meshes from the shared occupancy volume.
4. Resume the next first-layer flora stage and performance gates.
5. Continue native draw-distance and full-depth gameplay acceptance.

Deeper layers, a finished Orth, story systems, detailed NPCs, and a complete ecology are not delivered by this publication.

## Publication verification

A fresh source copy and new Gradle user home were built on Windows with JDK 17, without reading the private runtime caches or snapshots:

| Check | Result |
| --- | --- |
| `:forge:build` | PASS; mod JAR produced with version `0.1.0-probe` and ARR metadata |
| Full `:kernel:test` | 951 tests: 941 passed, 10 failed, 0 errors, 0 skipped |
| Independent wall oracle after restoring its required source file | 5 tests passed; the prototype is preserved byte-for-byte because its SHA256 is part of the oracle |
| Milestone publication integration tests | 11 passed, using temporary local repositories and the real Gitleaks scanner |
| Client-control storage precedence | 4 cases passed, including unchanged private-workstation configuration |

The ten kernel failures are three native draw-distance/first-view expectations (`NativeFirstViewPlanTest` and `NativeRenderDistanceTest`) and seven unfinished B2 geometry-light contracts (`LivingAbyssGeometrySky155B2Test`). They are included and remain visible; no tests were disabled to publish this snapshot. The full Gradle `build` therefore exits nonzero. A passing CI workflow/build badge is deliberately not included.

The Forge artifact audit found only project class packages and no nested third-party JARs. Build outputs are excluded from Git and are not attached as a player release. No Minecraft client was launched during this publication audit. Publication changes are limited to approved storage/text portability, documentation, version metadata, and the milestone workflow; unfinished gameplay algorithms were not repaired or declared complete.
