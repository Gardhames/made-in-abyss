# Milestone publication policy

The owner authorizes automatic publication after a **completed major checkpoint**, with an English summary and a distinct version. Individual edits, intermediate experiments, and partial checkpoints remain local.

## Local work

Use Git normally for inspection. A local snapshot can be created with:

```sh
python tools/release/checkpoint.py
```

The default action is `snapshot`. It captures the public-source working tree, including nonignored new files, in `refs/mia-snapshots/<UTC timestamp>`. It leaves `main`, the working tree, and the normal index unchanged. It performs no network operation. Private reports/settings remain separate local files.

These snapshot refs are never included by the milestone publisher. Do not use `git push --mirror`, push all refs, or turn each local snapshot into a public release.

## What qualifies for publication

A major checkpoint has a coherent user-visible or architectural result, all checks agreed for that stage, and any required runtime/visual acceptance. Passing an API test or preparing an experiment alone does not qualify. Preserve the distinction between implemented, compiled, automated, runtime-tested, and visually reviewed.

A new version must describe the accepted result. Use SemVer with a prerelease suffix while this remains a probe. Update [VERSION](../../VERSION), an English [CHANGELOG](../../CHANGELOG.md) entry, and current status. Cache schemas, world profiles, and generator fingerprints have separate compatibility contracts; changing a repository version must not silently migrate them.

## Prepare and publish

Create a local UTF-8 Markdown summary with `### Added`, `### Changed`, `### Fixed`, or another Keep a Changelog section. Describe outcomes and important limits, not the conversation or every intermediate edit.

```sh
python tools/release/checkpoint.py prepare --version 0.1.1-probe --summary-file .local/milestone-summary.md
python tools/release/checkpoint.py publish --accepted-checkpoint "Completed and accepted milestone"
```

The example version is illustrative; choose the next unused version for the actual result. `prepare` changes only VERSION and CHANGELOG locally. The Forge resource-processing step obtains the mod version from VERSION, so the JAR metadata and repository version stay aligned.

`publish` requires an existing published `main`, a clean normal index, `main` equal to the published main tip, the expected `origin`, a prepared changelog entry, passing validation commands, a privacy/binary audit, and Gitleaks. The default validation is `gradlew --no-daemon build`; it does not silently exclude known test failures. A failure leaves the candidate local. Do not weaken checks to obtain a passing publication gate.

Gitleaks must be on PATH, in `GITLEAKS_BIN`, or supplied with `--gitleaks`. Check commands are argument arrays in [checks.json](../../tools/release/checks.json), not shell strings. Stage-specific validation must also include the runtime, visual, or performance gates agreed for that milestone; the script cannot substitute for them.

After validation, the tool creates one summarized conventional commit, an annotated `v<version>` tag, and one atomic push of `main` and that tag. It verifies the remote main commit afterward. It never force-pushes or publishes local snapshot refs. Unpublished ordinary local commits are refused, so an intermediate commit history cannot be pushed accidentally; keep work in snapshot refs until the milestone.

If a push fails, the local commit and tag remain available for inspection and a deliberate retry. Do not rewrite history or delete tags automatically. Publishing a GitHub Release or attaching a JAR is a separate decision, not an automatic consequence of this command.

## Initial publication and new approval boundaries

The first push requires the owner's explicit review of file list, size, scans, visibility, license, and approved images. The milestone tool deliberately refuses an empty remote, so it cannot bypass that stop.

The standing authorization covers later accepted major checkpoints to the same repository under the same policy. Ask the owner before changing visibility, licensing, ownership, the repository destination, public asset permissions, or the privacy boundary. Existing internal agent instructions and reports stay local.

The initial probe may be published with explicitly documented unfinished work and known failing tests after the owner's review. That exception does not make a failing test suite pass or enable a misleading green CI badge.
