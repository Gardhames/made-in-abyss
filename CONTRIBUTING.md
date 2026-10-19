# Contributing

This is an experimental, source-visible project under an All Rights Reserved notice. Viewing the source does not grant a general reuse or redistribution license. Discuss substantial contributions with the maintainer first; permission to incorporate a contribution must be agreed explicitly. No copyright transfer or contributor license agreement is implied.

## Before making a change

Read the [status](docs/public/status.md), [architecture](docs/public/architecture.md), and [development guide](docs/public/development.md). Use Minecraft 1.20.1, Forge 47.4.20, and JDK 17. Keep the standalone kernel free of Minecraft dependencies.

For a bug, provide an observation, a hypothesis, a minimal reproduction, and expected versus actual results. Preserve failing cases. Discuss changes to geometry, palette, density, fog, or other artistic decisions before implementing them.

## Invariants

- Keep one continuous coordinate space; one block is one metre.
- Never silently turn unknown geometry or opacity into air.
- Preserve edits, persistence, revisions, cancellation, and renderer acknowledgement.
- Do not silently regenerate visited regions under a new profile.
- Do not bundle third-party mods, shader packs, copyrighted reference images, worlds, caches, or private reports.
- Keep public documentation, comments, issue text, and commit messages in English.

## Verification

Use focused tests for mathematical or behavioral changes, including negative coordinates and boundaries. Run `:kernel:test` and checks relevant to the changed Forge path. A build, a GameTest, an installed-client test, and visual review are different results: state exactly which were run and which remain open.

Do not mark a known failing suite as passing by excluding its failures. The publication records its actual limitations in [status](docs/public/status.md).

## Pull requests

Describe the problem, resulting behavior, and evidence. Give exact commands and exit statuses. Include images only when you have permission to publish them, and redact personal paths and credentials from logs.

Use concise English conventional commit messages, such as `fix(lighting): preserve owner revisions during cancellation`. Ordinary local snapshots are not pushed individually; see the [milestone policy](docs/public/publishing.md).
