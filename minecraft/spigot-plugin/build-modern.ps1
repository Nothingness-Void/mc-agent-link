param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17.0.3.1'
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradle = Join-Path $projectDir 'gradlew.bat'
$releaseDir = Join-Path $projectDir 'build\modern-releases'

if (-not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "JDK 17 was not found at $JavaHome. Pass -JavaHome <path> or install JDK 17."
}

$env:JAVA_HOME = $JavaHome

$targets = @(
    @{ Name = 'modern'; Minecraft = '1.20.1'; Api = '1.20'; SpigotApi = '1.20.1-R0.1-SNAPSHOT'; Artifact = 'agent-link-spigot-modern' },
    @{ Name = '1.20.6'; Minecraft = '1.20.6'; Api = '1.20'; SpigotApi = '1.20.6-R0.1-SNAPSHOT'; Artifact = 'agent-link-spigot-1.20.6' },
    @{ Name = '1.21.1'; Minecraft = '1.21.1'; Api = '1.20'; SpigotApi = '1.21.1-R0.1-SNAPSHOT'; Artifact = 'agent-link-spigot-1.21.1' },
    @{ Name = '1.21.11'; Minecraft = '1.21.11'; Api = '1.20'; SpigotApi = '1.21.11-R0.1-SNAPSHOT'; Artifact = 'agent-link-spigot-1.21.11' }
)

Push-Location $projectDir
try {
    & $gradle clean
    if ($LASTEXITCODE -ne 0) { throw 'Gradle clean failed.' }

    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    foreach ($target in $targets) {
        Write-Host "Building $($target.Name) against Spigot API $($target.SpigotApi)..."
        & $gradle shadowJar `
            "-Pminecraft_version=$($target.Minecraft)" `
            "-Papi_version=$($target.Api)" `
            "-Pspigot_api_version=$($target.SpigotApi)" `
            "-Partifact_name=$($target.Artifact)"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed for $($target.Name)." }

        $jar = Get-ChildItem (Join-Path $projectDir 'build\libs') -File |
            Where-Object { $_.Name -like "$($target.Artifact)-*.jar" -and $_.Name -notlike '*-sources.jar' } |
            Select-Object -First 1
        if ($null -eq $jar) { throw "No shaded jar was produced for $($target.Name)." }
        Copy-Item $jar.FullName (Join-Path $releaseDir $jar.Name) -Force
    }
} finally {
    Pop-Location
}

Write-Host "Modern compatibility artifacts are in $releaseDir"
