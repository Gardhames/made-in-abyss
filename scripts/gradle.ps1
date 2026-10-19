[CmdletBinding()]
param(
    [string[]]$Tasks = @('tasks', '--all'),
    [string]$StorageRoot = '',
    [string]$JdkPath = ''
)

$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
if (-not $StorageRoot) { $StorageRoot = $env:MIA_STORAGE_ROOT }
if (-not $StorageRoot) {
    $localSettingsFile = Join-Path $sourceRoot 'local.properties'
    if (Test-Path -LiteralPath $localSettingsFile) {
        foreach ($settingsLine in Get-Content -LiteralPath $localSettingsFile -Encoding UTF8) {
            if ($settingsLine -match '^\s*miaStorageRoot\s*=\s*(.*?)\s*$') { $StorageRoot = $Matches[1] }
        }
    }
}
if (-not $StorageRoot) { $StorageRoot = '.local' }
if (-not [IO.Path]::IsPathRooted($StorageRoot)) { $StorageRoot = Join-Path $sourceRoot $StorageRoot }
$taskStorageRoot = [IO.Path]::GetFullPath($StorageRoot)
if (-not $JdkPath) {
    $gameJdk = Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-gamma\windows\java-runtime-gamma'
    if (Test-Path -LiteralPath (Join-Path $gameJdk 'bin\javac.exe')) { $JdkPath = $gameJdk }
    elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $JdkPath = $env:JAVA_HOME }
    else { throw 'JDK not found. Supply -JdkPath with a JDK 17 installation.' }
}

$jdkVersion = & (Join-Path $JdkPath 'bin\javac.exe') -version 2>&1
if ($LASTEXITCODE -ne 0 -or [string]$jdkVersion -notmatch '^javac 17\.') {
    throw "JDK 17 is required. Found: $jdkVersion"
}

$originalEnvironment = @{}
foreach ($environmentName in @('JAVA_HOME','GRADLE_USER_HOME','TEMP','TMP')) {
    $originalEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
}
$previousLocation = Get-Location
try {
    foreach ($directoryName in @('gradle-user-home','project-cache','temp')) {
        New-Item -ItemType Directory -Path (Join-Path $taskStorageRoot $directoryName) -Force | Out-Null
    }
    $env:JAVA_HOME = $JdkPath
    $env:GRADLE_USER_HOME = Join-Path $taskStorageRoot 'gradle-user-home'
    $env:TEMP = Join-Path $taskStorageRoot 'temp'
    $env:TMP = $env:TEMP
    Set-Location -LiteralPath $sourceRoot
    $gradleArguments = @('--no-daemon','--console=plain','--project-cache-dir',(Join-Path $taskStorageRoot 'project-cache'),"-PmiaStorageRoot=$taskStorageRoot") + $Tasks
    & (Join-Path $sourceRoot 'gradlew.bat') @gradleArguments
    $taskExitCode = $LASTEXITCODE
} finally {
    Set-Location -LiteralPath $previousLocation.Path
    foreach ($environmentName in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($environmentName, $originalEnvironment[$environmentName], 'Process')
    }
}
exit $taskExitCode
