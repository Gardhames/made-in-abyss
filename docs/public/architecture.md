# Architecture

## Coordinate and authority model

The target is one continuous XYZ world with the shaft centred at X=0, Z=0 and one block per metre. The project explores an independent 16-cubed runtime rather than representing depth as a HUD value or teleported layers.

The kernel contains coordinate mathematics, cube snapshots/storage, residency/tickets, scheduled-work primitives, terrain samplers, lighting, and LOD planning. The Forge module owns Minecraft integration, native access guards, diagnostic commands, generation bindings, client meshes, and optional render integrations.

Unknown geometry and unknown opacity remain explicit. A missing cube is not evidence of air. Immutable source identities, revisions, cancellation checks, and acknowledgement barriers protect asynchronous publication against a stale world.

## Terrain and persistence

Versioned profiles combine the island height field with procedural terrain and reconstruction rules. First-layer work includes ledges, rock relief, cave connections, water/cascades, and landmarks. Historical profiles remain distinct; changing code must not silently rewrite visited worlds or erase builds.

The compressed island field and recipe are source assets. Saved cubes, runtime caches, and captured acceptance worlds are generated data and stay outside Git.

## Native rendering and distant surfaces

Native cube meshes and DH-derived surfaces serve different ranges. The bridge uses the public Distant Horizons API; it does not ship a patched DH binary. Surface owners are assembled into pages and prepared asynchronously. Geometry completion, GPU upload, first world draw, and exact-light completion are distinct events.

Provisional page lighting allows presentation before all exact values exist. CPU mode refines it using open-side probes and visibility priority. A READY GPU-light path skips that CPU after-acknowledgement probe phase and supplies exact values through the same page-publication machinery.

## GPU exact light

The optical volume uses a sparse 128 m directory and logical 16-cubed bricks. Opacity is mandatory for reachable rays; state/palette data is a separate channel and may be filled lazily. A closed exterior halo and verified analytic rules avoid CPU generator calls from the compute kernel. Unknown or unproven data causes explicit refusal.

Ray traversal uses the accepted Q30 integer DDA and prefix scheme. Attenuation/interpolation still use floating-point arithmetic. A shared-context worker dispatches bounded GPU work and returns results asynchronously; source/code/rules identities prevent an unrelated snapshot from being accepted.

The edit foundations include copy-on-write optical snapshots, brick versions, inverse ray dependencies, palette identity, and an owner cache. Their integration into a live incremental GPU edit transaction is unfinished. The existing `applyEdit` API must not be mistaken for a working client feature.

## Next stages

After local GPU edits and the full G3 regression, the plan is to improve the cold range producer. The same occupancy format is intended to support future GPU LOD meshing. Neither future meshing nor the next flora stage is an accepted current feature.
