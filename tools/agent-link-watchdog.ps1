[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int] $ProcessId,

    [Parameter(Mandatory = $true)]
    [string] $ServerDirectory,

    [ValidateRange(100, 60000)]
    [int] $PollMilliseconds = 500,

    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$serverRoot = (Resolve-Path -LiteralPath $ServerDirectory).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $serverRoot 'diagnostics'
}
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)

$process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
if ($null -eq $process) {
    throw "Process $ProcessId was not found. Start the server first and pass its Java process id."
}
$observedProcess = $process
$exitCode = $null

$initialStartTime = $null
try { $initialStartTime = $process.StartTime.ToUniversalTime().ToString('o') } catch { }
$processName = $process.ProcessName
$processPath = $null
try { $processPath = $process.Path } catch { }
$commandLine = $null
$parentProcessId = $null
try {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    $commandLine = $processInfo.CommandLine
    $parentProcessId = $processInfo.ParentProcessId
} catch { }

Write-Host "Watching process $ProcessId in $serverRoot (started $initialStartTime)"
$exitReason = 'process_exit'
while ($true) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        try {
            $observedProcess.Refresh()
            if ($observedProcess.HasExited) { $exitCode = $observedProcess.ExitCode }
        } catch { }
        break
    }
    try {
        $currentStartTime = $process.StartTime.ToUniversalTime().ToString('o')
        if ($null -ne $initialStartTime -and $currentStartTime -ne $initialStartTime) {
            $exitReason = 'pid_reused'
            break
        }
    } catch { }
    Start-Sleep -Milliseconds $PollMilliseconds
}

$detectedAt = [DateTimeOffset]::Now
$stamp = $detectedAt.ToString('yyyyMMdd-HHmmss-fff')
$bundle = Join-Path $outputRoot "incident-$stamp"
New-Item -ItemType Directory -Path $bundle -Force | Out-Null

$copied = @()
$artifacts = @()

function Copy-IncidentArtifact {
    param(
        [Parameter(Mandatory = $true)][string] $SourcePath,
        [Parameter(Mandatory = $true)][string] $RelativeName,
        [int64] $MaxBytes = 16MB
    )
    if (!(Test-Path -LiteralPath $SourcePath -PathType Leaf)) { return }
    $item = Get-Item -LiteralPath $SourcePath
    $record = [ordered]@{
        source = $RelativeName.Replace('\', '/')
        size_bytes = [int64]$item.Length
        copied = $false
        reason = $null
    }
    if ($item.Length -gt $MaxBytes) {
        $record.reason = "larger_than_${MaxBytes}_byte_copy_limit"
        $script:artifacts += [pscustomobject]$record
        return
    }
    $destination = Join-Path $bundle ($RelativeName.Replace('/', '\'))
    $destinationParent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
    Copy-Item -LiteralPath $SourcePath -Destination $destination -Force
    $record.copied = $true
    $script:copied += $RelativeName.Replace('\', '/')
    $script:artifacts += [pscustomobject]$record
}

$latestLog = Join-Path $serverRoot 'logs\latest.log'
Copy-IncidentArtifact -SourcePath $latestLog -RelativeName 'logs/latest.log' -MaxBytes 32MB
Copy-IncidentArtifact -SourcePath (Join-Path $serverRoot 'logs\debug.log') -RelativeName 'logs/debug.log' -MaxBytes 32MB

$crashDirectory = Join-Path $serverRoot 'crash-reports'
$crash = Get-ChildItem -LiteralPath $crashDirectory -Filter '*.txt' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -ne $crash) {
    Copy-IncidentArtifact -SourcePath $crash.FullName -RelativeName "crash-reports/$($crash.Name)" -MaxBytes 16MB
}

foreach ($pattern in @('hs_err_pid*.log', 'replay_pid*.log', 'java_error_in_*.log')) {
    Get-ChildItem -LiteralPath $serverRoot -Filter $pattern -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 3 |
        ForEach-Object { Copy-IncidentArtifact -SourcePath $_.FullName -RelativeName $_.Name -MaxBytes 16MB }
}

# Heap dumps are often enormous. Preserve their metadata for an operator instead of copying them.
$heapArtifacts = Get-ChildItem -LiteralPath $serverRoot -Include '*.hprof', '*.dmp' -File -ErrorAction SilentlyContinue
foreach ($heap in $heapArtifacts) {
    $artifacts += [pscustomobject][ordered]@{
        source = $heap.Name
        size_bytes = [int64]$heap.Length
        copied = $false
        reason = 'large_dump_not_copied'
    }
}

$ledgerCandidates = @(
    (Join-Path $serverRoot 'diagnostics\incident-ledger.json'),
    (Join-Path $serverRoot 'config\agent-link\incident-ledger.json')
)
foreach ($ledger in $ledgerCandidates) {
    Copy-IncidentArtifact -SourcePath $ledger -RelativeName 'incident-ledger.json' -MaxBytes 4MB
}

$metadata = [ordered]@{
    schema_version = 2
    detected_at = $detectedAt.ToString('o')
    process_id = $ProcessId
    process_name = $processName
    process_path = $processPath
    command_line = $commandLine
    parent_process_id = $parentProcessId
    process_start_time = $initialStartTime
    exit_reason = $exitReason
    exit_code = $exitCode
    server_directory = $serverRoot
    bundle_directory = $bundle
    copied_files = $copied
    artifacts = $artifacts
    latest_crash_report = if ($null -eq $crash) { $null } else { $crash.Name }
    note = 'The watchdog does not restart the server or execute commands. Large heap dumps are metadata-only.'
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $bundle 'incident.json') -Encoding UTF8

Write-Output $bundle
