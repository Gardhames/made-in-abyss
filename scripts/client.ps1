[CmdletBinding()]
param(
    [string]$Action = 'status',
    [hashtable]$Params = @{},
    [ValidatePattern('^([a-z0-9][a-z0-9-]{0,31})?$')]
    [string]$Instance = $env:MIA_CLIENT_INSTANCE
)
$sourceRoot = Split-Path -Parent $PSScriptRoot
$previousInstance = [Environment]::GetEnvironmentVariable('MIA_CLIENT_INSTANCE', 'Process')
try {
    $env:MIA_CLIENT_INSTANCE = $Instance
    $Params | ConvertTo-Json -Compress -Depth 10 | & node (Join-Path $sourceRoot 'tools\client\cli.mjs') $Action --stdin
    $controlExitCode = $LASTEXITCODE
} finally {
    [Environment]::SetEnvironmentVariable('MIA_CLIENT_INSTANCE', $previousInstance, 'Process')
}
exit $controlExitCode
