[CmdletBinding()]
param(
    [switch] $CheckBuiltArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolSpecPath = Join-Path $root 'minecraft\forge-mod\src\main\resources\agent-link\mcp-tools.json'
$nodeSourcePath = Join-Path $root 'packages\mcp-server\src\index.ts'
$watchdogPath = Join-Path $root 'tools\agent-link-watchdog.ps1'
$diagnosticsPath = Join-Path $root 'minecraft\forge-mod\src\main\java\world\agentlink\diagnostics\ServerDiagnostics.java'
$ledgerPath = Join-Path $root 'minecraft\forge-mod\src\main\java\world\agentlink\diagnostics\IncidentLedger.java'
$tickIncidentPath = Join-Path $root 'minecraft\forge-mod\src\main\java\world\agentlink\diagnostics\TickIncidentRecorder.java'
$requestApiPath = Join-Path $root 'minecraft\forge-mod\src\main\java\world\agentlink\api\AgentRequestApi.java'

function Assert-Contract([bool] $condition, [string] $message) {
    if (!$condition) { throw "Contract check failed: $message" }
}

$spec = Get-Content -LiteralPath $toolSpecPath -Raw | ConvertFrom-Json
$names = @($spec | ForEach-Object { $_.name })
Assert-Contract ($names.Count -eq (@($names | Sort-Object -Unique).Count)) 'duplicate built-in MCP tool name'
$diagnose = $spec | Where-Object name -eq 'server_diagnose'
Assert-Contract ($null -ne $diagnose) 'server_diagnose is missing from mcp-tools.json'
Assert-Contract ($null -ne $diagnose.inputSchema.properties.timeout_ms) 'server_diagnose.timeout_ms is missing from the schema'
Assert-Contract ($null -ne ($spec | Where-Object name -eq 'tick_incidents')) 'tick_incidents is missing from mcp-tools.json'

$nodeSource = Get-Content -LiteralPath $nodeSourcePath -Raw
Assert-Contract ($nodeSource.Contains('name: "server_diagnose"')) 'Node bridge does not advertise server_diagnose'
Assert-Contract ($nodeSource.Contains('timeout_ms')) 'Node bridge does not forward timeout_ms'
Assert-Contract ($nodeSource.Contains('name: "tick_incidents"')) 'Node bridge does not advertise tick_incidents'

$watchdogTokens = $null
$watchdogErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($watchdogPath, [ref]$watchdogTokens, [ref]$watchdogErrors) | Out-Null
Assert-Contract (@($watchdogErrors).Count -eq 0) 'watchdog PowerShell syntax is invalid'

$diagnosticsSource = Get-Content -LiteralPath $diagnosticsPath -Raw
Assert-Contract ($diagnosticsSource.Contains('timeoutMs()')) 'diagnostics collector has no total timeout budget'
Assert-Contract ($diagnosticsSource.Contains('status", "timeout"')) 'diagnostics collector has no timeout component status'
$ledgerSource = Get-Content -LiteralPath $ledgerPath -Raw
Assert-Contract ($ledgerSource.Contains('clean_shutdown')) 'incident ledger has no clean shutdown marker'
$tickIncidentSource = Get-Content -LiteralPath $tickIncidentPath -Raw
Assert-Contract ($tickIncidentSource.Contains('INCIDENT_COOLDOWN_MS')) 'tick incident recorder has no coalescing cooldown'
$requestApiSource = Get-Content -LiteralPath $requestApiPath -Raw
Assert-Contract ($requestApiSource.Contains('claimOwned')) 'request API has no owned claim facade'
Assert-Contract ($requestApiSource.Contains('replyOwned')) 'request API has no owned completion facade'
Assert-Contract ($requestApiSource.Contains('failOwned')) 'request API has no owned failure facade'
Assert-Contract ($requestApiSource.Contains('MutationOutcome')) 'request API has no detailed mutation outcome'
$requestToolsDir = Join-Path $root 'minecraft\forge-mod\src\main\java\world\agentlink\dispatch\tools'
$requestToolSources = Get-ChildItem -LiteralPath $requestToolsDir -Filter '*.java' -File | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }
Assert-Contract (-not (($requestToolSources -join "`n").Contains('AgentRequestBuffer'))) 'request tools bypass AgentRequestApi'

if ($CheckBuiltArtifacts) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $root 'minecraft\forge-mod\build\libs') -Filter '*.jar' -File |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Assert-Contract ($null -ne $jar) 'no built Forge jar found'
    $nodeDist = Join-Path $root 'packages\mcp-server\dist\index.js'
    Assert-Contract (Test-Path -LiteralPath $nodeDist -PathType Leaf) 'Node bridge dist/index.js is missing'
}

Write-Output 'agent-link contract checks passed'
