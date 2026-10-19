# Development

## Exact build inputs

Minecraft 1.20.1, Forge 47.4.20, ForgeGradle 6.0.24, Gradle 8.8, JDK 17, fastutil 8.5.9, and JUnit Jupiter 5.10.2 are pinned in the build files. The wrapper distribution SHA256 is `a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612`.

The wrapper came from the [official Forge MDK](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.20/forge-1.20.1-47.4.20-mdk.zip). Its recorded MDK SHA256 is `b27fe807c7d65bdbd1d8ad1ee186508e661c4801edb5b7e1df35d0724a8358ac`.

## Storage

Resolution order is explicit `-PmiaStorageRoot`, `MIA_STORAGE_ROOT`, ignored `local.properties`, then the repository's `.local` directory. The PowerShell wrapper's explicit `-StorageRoot` parameter becomes the Gradle property and has the highest priority. Paths in `local.properties` should use forward slashes.

All module outputs are under `<storage>/build/<module>/`. Forge run directories are under `<storage>/run/`. The PowerShell wrapper additionally isolates Gradle's user home, project cache, and temporary directory beneath that root, restoring its process environment afterward. Direct `gradlew` uses the caller's normal Gradle user home unless `GRADLE_USER_HOME` is set explicitly.

Do not commit local settings, caches, generated worlds, launch templates, captures, or logs.

## Commands

PowerShell with JDK 17 in `JAVA_HOME`:

```powershell
.\scripts\gradle.ps1 -JdkPath $env:JAVA_HOME -Tasks @('--version')
.\scripts\gradle.ps1 -JdkPath $env:JAVA_HOME -Tasks @('tasks', '--all')
.\scripts\gradle.ps1 -JdkPath $env:JAVA_HOME -Tasks @(':kernel:test')
.\scripts\gradle.ps1 -JdkPath $env:JAVA_HOME -Tasks @(':forge:build')
.\scripts\gradle.ps1 -JdkPath $env:JAVA_HOME -Tasks @('build')
```

Equivalent POSIX-shell tasks:

```sh
./gradlew --no-daemon :kernel:test
./gradlew --no-daemon :forge:build
./gradlew --no-daemon build
```

`build` includes kernel tests and the Forge build. `:forge:build` compiles/packages the Forge module; it does not replace a full kernel test run or live client/server acceptance. `gameTestServer` is a separate integration entry point; some diagnostic modes require generated fixtures not distributed here.

## Client control

Launch an isolated userdev client with `:forge:runClient`. G1 is the portable documented GC selection:

```powershell
.\scripts\gradle.ps1 -JdkPath $env:JAVA_HOME -Tasks @(':forge:runClient', '-PmiaClientGc=G1')
.\scripts\client.ps1 -Action status
```

Or query directly:

```sh
node tools/client/cli.mjs status
```

The launcher enables `mia.client.automation`; requests and replies live in the configured instance's `automation` directory. `MIA_CLIENT_INSTANCE` selects a named instance. Launcher and controller must use the same storage root. An absent `ready.json` or retired process is an error, not a reason to attach to a different client.

Private frozen-runtime launchers and acceptance collectors are excluded because they depend on captured launch arguments and local worlds. A fresh source build does not recreate those fixtures automatically.

## Experimental GPU switches

| JVM property | Meaning |
| --- | --- |
| `mia.light.gpu=true` | Opt into GPU exact light; default is off |
| `mia.light.gpu.snapshot` | Matching checked optical snapshot; none is shipped |
| `mia.light.gpu.cpuFallback=false` | Refuse explicitly instead of falling back to CPU exact |
| `mia.light.gpu.budgetMillis` | Worker scheduling budget; current default is 1.5 |

Gradle's client run accepts JVM properties through `-PmiaClientJvmArgs=...`. Enabling GPU mode without a prepared matching snapshot is not a complete setup. GPU block-edit incrementality is unfinished; see [status](status.md).

## Evidence

Record the source revision, profile fingerprint, relevant flags, exact command, exit code, and whether work was headless or in a real client. Distinguish IMPLEMENTED, BUILDS, AUTOTESTED, RUNTIME_TESTED, and VISUALLY_REVIEWED. None implies the next.

Keep reports local until redacted and reviewed. A private report is not a public build dependency. Only completed major checkpoints are published under the [milestone policy](publishing.md).
