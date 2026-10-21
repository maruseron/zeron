$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$pom = [xml](Get-Content -LiteralPath (Join-Path $repositoryRoot "pom.xml") -Raw)
$projectVersion = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='version']").InnerText
$versionsSource = Get-Content -LiteralPath (Join-Path $repositoryRoot "src\main\java\com\maruseron\zeron\ZeronVersions.java") -Raw
$libraryIndexMatch = [regex]::Match(
    $versionsSource, '(?m)^\s*public\s+static\s+final\s+int\s+API_INDEX_SCHEMA_VERSION\s*=\s*(\d+)\s*;')
if (-not $libraryIndexMatch.Success) {
    throw "Could not read the Zeron library-index version."
}
$libraryIndexVersion = $libraryIndexMatch.Groups[1].Value
$standardLibraryMatch = [regex]::Match(
    $versionsSource, '(?m)^\s*public\s+static\s+final\s+int\s+STANDARD_LIBRARY_API_VERSION\s*=\s*(\d+)\s*;')
if (-not $standardLibraryMatch.Success) {
    throw "Could not read the Zeron standard-library API version."
}
$standardLibraryApiVersion = $standardLibraryMatch.Groups[1].Value
$packageName = "zeron-$projectVersion"
$workRoot = Join-Path $repositoryRoot "target\release-work"
$stageRoot = Join-Path $workRoot $packageName
$releaseRoot = Join-Path $repositoryRoot "target\releases"
$archivePath = Join-Path $releaseRoot "$packageName.zip"
$compilerJar = Join-Path $stageRoot "lib\zeron.jar"
$standardLibraryJarName = "zeron-stdlib-index-v$libraryIndexVersion-api-v$standardLibraryApiVersion.jar"
$standardLibraryJar = Join-Path $stageRoot "lib\$standardLibraryJarName"

Push-Location $repositoryRoot
try {
    & mvn clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE." }

    $builtCompilerJar = Join-Path $repositoryRoot "target\zeron-$projectVersion.jar"
    if (-not (Test-Path -LiteralPath $builtCompilerJar -PathType Leaf)) {
        throw "Expected shaded compiler JAR was not produced: $builtCompilerJar"
    }

    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $archivePath) {
        Remove-Item -LiteralPath $archivePath -Force
    }
    New-Item -ItemType Directory -Path (Join-Path $stageRoot "bin") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stageRoot "lib") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stageRoot "share\zeron\errorcode") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stageRoot "share\zeron\stdlib") -Force | Out-Null

    Copy-Item -LiteralPath $builtCompilerJar -Destination $compilerJar
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "distribution\bin\zeron") `
        -Destination (Join-Path $stageRoot "bin\zeron")
    Copy-Item -LiteralPath (Join-Path $repositoryRoot "distribution\bin\zeron.cmd") `
        -Destination (Join-Path $stageRoot "bin\zeron.cmd")
    Copy-Item -Path (Join-Path $repositoryRoot "src\main\resources\errorcode\*.md") `
        -Destination (Join-Path $stageRoot "share\zeron\errorcode")
    Copy-Item -Path (Join-Path $repositoryRoot "src\main\resources\stdlib\*") `
        -Destination (Join-Path $stageRoot "share\zeron\stdlib") -Recurse
    $expectedSourceCount = (Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "src\main\resources\stdlib") `
        -Filter "*.zn" -File -Recurse | Measure-Object).Count
    $bundledSourceCount = (Get-ChildItem -LiteralPath (Join-Path $stageRoot "share\zeron\stdlib") `
        -Filter "*.zn" -File -Recurse | Measure-Object).Count
    if ($bundledSourceCount -ne $expectedSourceCount) {
        throw "Expected $expectedSourceCount standard-library source files but bundled $bundledSourceCount."
    }

    & java -jar $compilerJar --build-stdlib (Join-Path $stageRoot "lib\stdlib") `
        --jar-output $standardLibraryJar
    if ($LASTEXITCODE -ne 0) { throw "Standard-library packaging failed with exit code $LASTEXITCODE." }

    $launcher = Join-Path $stageRoot "bin\zeron.cmd"
    $explanation = & $launcher explain ZR2014
    if ($LASTEXITCODE -ne 0 -or ($explanation -join "`n") -notmatch "ZR2014: Type mismatch") {
        throw "Packaged compiler failed the diagnostic explanation smoke test."
    }

    $versionOutput = & $launcher version
    if ($LASTEXITCODE -ne 0 -or ($versionOutput -join "`n") -ne "zeron $projectVersion") {
        throw "Packaged compiler reported an unexpected version: $($versionOutput -join "`n")"
    }

    $smokeRoot = Join-Path $stageRoot "smoke"
    $smokeSource = Join-Path $smokeRoot "src\Main.zn"
    New-Item -ItemType Directory -Path (Split-Path -Parent $smokeSource) -Force | Out-Null
    $smokeProgram = @'
import zeron.io.println;
fn main(): Unit {
    println("zeron-release-smoke-ok");
}
'@
    [System.IO.File]::WriteAllText($smokeSource, $smokeProgram, (New-Object System.Text.UTF8Encoding($false)))
    $smokeManifest = @"
[project]
entry = "src/Main.zn"
source-roots = ["src"]
output = "output"

[platform]
stdlib = "compiled"

[dependencies]
zeron-libraries = ["../lib/$standardLibraryJarName"]
"@
    [System.IO.File]::WriteAllText((Join-Path $smokeRoot "zeron.toml"),
        $smokeManifest, (New-Object System.Text.UTF8Encoding($false)))
    $smokeOutput = & $launcher run --manifest (Join-Path $smokeRoot "zeron.toml") 2>&1
    if ($LASTEXITCODE -ne 0 -or ($smokeOutput -join "`n") -notmatch "zeron-release-smoke-ok") {
        throw "Packaged compiled-standard-library smoke test failed: $($smokeOutput -join "`n")"
    }

    $sourceSmokeManifest = @"
[project]
entry = "src/Main.zn"
source-roots = ["src"]
output = "output-source"
"@
    $sourceSmokeManifestPath = Join-Path $smokeRoot "zeron-source.toml"
    [System.IO.File]::WriteAllText($sourceSmokeManifestPath,
        $sourceSmokeManifest, (New-Object System.Text.UTF8Encoding($false)))
    $sourceSmokeOutput = & $launcher run --manifest $sourceSmokeManifestPath 2>&1
    if ($LASTEXITCODE -ne 0 -or ($sourceSmokeOutput -join "`n") -notmatch "zeron-release-smoke-ok") {
        throw "Packaged bundled-source smoke test failed: $($sourceSmokeOutput -join "`n")"
    }
    Remove-Item -LiteralPath $smokeRoot -Recurse -Force

    New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $archivePath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        Get-ChildItem -LiteralPath $stageRoot -File -Recurse |
            Sort-Object FullName |
            ForEach-Object {
                $relativePath = $_.FullName.Substring($stageRoot.Length).TrimStart([char[]]@('\', '/')) -replace '\\', '/'
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive, $_.FullName, "$packageName/$relativePath",
                    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
    } finally {
        $archive.Dispose()
    }
    $archiveEntries = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $entryNames = $archiveEntries.Entries.FullName
        foreach ($requiredEntry in @(
                "$packageName/lib/zeron.jar",
                "$packageName/lib/$standardLibraryJarName",
                "$packageName/share/zeron/errorcode/ZR2014.md",
                "$packageName/share/zeron/stdlib/zeron/lang/option.zn",
                "$packageName/bin/zeron",
                "$packageName/bin/zeron.cmd")) {
            if ($entryNames -notcontains $requiredEntry) {
                throw "Release archive is missing $requiredEntry."
            }
        }
    } finally {
        $archiveEntries.Dispose()
    }
    $hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$archivePath.sha256" -Encoding ascii -Value "$hash  $packageName.zip"

    Write-Output "Release prototype created:"
    Write-Output "  $archivePath"
    Write-Output "  $archivePath.sha256"
} finally {
    Pop-Location
}
