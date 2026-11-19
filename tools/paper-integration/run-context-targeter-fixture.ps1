param(
    [ValidatePattern('^r[1-9][0-9]*$')][string]$EvidenceRevision = 'r1',
    [ValidateRange(1024, 65535)][int]$Port = 25735,
    [string]$MythicMobsJarPath = '',
    [string]$PaperApiJarPath = '',
    [string]$VanillaCacheDirectory = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sources = Join-Path $PSScriptRoot 'context-targeter-fixture'
$date = [DateTime]::UtcNow.ToString('yyyyMMdd')
$fixtureId = "srv-paper-context-eligibility-exploratory-$date-$EvidenceRevision"
$fixture = Join-Path $root $fixtureId
$reportName = "paper-1.21.1-context-eligibility-exploratory-$date-$EvidenceRevision"
$archive = Join-Path $PSScriptRoot "reports/$reportName"
$paper = Join-Path $root 'srv-1.21.1/paper.jar'
$plugin = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
if (-not $MythicMobsJarPath) {
    $MythicMobsJarPath = Join-Path $PSScriptRoot '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
}
if (-not $PaperApiJarPath) {
    $mavenRepo = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'
    $PaperApiJarPath = Join-Path $mavenRepo 'io/papermc/paper/paper-api/1.21.1-R0.1-SNAPSHOT/paper-api-1.21.1-R0.1-SNAPSHOT.jar'
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Start-ProbeProcess([string]$File, [string[]]$Arguments, [string]$Directory) {
    $info = [Diagnostics.ProcessStartInfo]::new($File)
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    return [Diagnostics.Process]::Start($info)
}

foreach ($path in @($fixture, "$archive.json", "$archive.log.gz")) {
    if (Test-Path -LiteralPath $path) { throw "Refusing to reuse fixture or report: $path" }
}
foreach ($path in @($paper, $plugin, $MythicMobsJarPath, $PaperApiJarPath,
        (Join-Path $PSScriptRoot 'node_modules/mineflayer/package.json'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required fixture input is missing: $path" }
}
$paperHash = Get-Sha256 $paper
$mythicHash = Get-Sha256 $MythicMobsJarPath
$versionMatrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw | ConvertFrom-Json
$pinnedPaper = @($versionMatrix.combinations | Where-Object {
    $_.releaseValidated -eq $true -and $_.serverVersion -ceq '1.21.1-133'
})
if ($pinnedPaper.Count -ne 1 -or $paperHash -cne [string]$pinnedPaper[0].serverJarSha256) {
    throw 'Expected the pinned Paper 1.21.1-133 Paperclip JAR.'
}
if ($mythicHash -cne '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f') {
    throw 'Expected the pinned MythicMobs 5.7.2 (Modrinth FTWvGuE5) JAR.'
}
$paperApi = (Resolve-Path -LiteralPath $PaperApiJarPath).Path
$classes = Join-Path $fixture 'probe-classes'
$compileLibraries = Join-Path $fixture 'compile-libraries'
$mythicRoot = Join-Path $fixture 'plugins/MythicMobs'
$skillsPath = Join-Path $mythicRoot 'Skills/context-targeter-skills.yml'
$mobsPath = Join-Path $mythicRoot 'Mobs/context-targeter-mobs.yml'
foreach ($directory in @($classes, $compileLibraries, (Split-Path -Parent $skillsPath),
        (Split-Path -Parent $mobsPath), (Join-Path $fixture 'plugins/helsteraMobs'))) {
    [void][IO.Directory]::CreateDirectory($directory)
}
Copy-Item -LiteralPath $paper -Destination (Join-Path $fixture 'paper.jar')
Copy-Item -LiteralPath $plugin -Destination (Join-Path $fixture 'plugins/helsteraMobs.jar')
Copy-Item -LiteralPath $MythicMobsJarPath -Destination (Join-Path $fixture 'plugins/MythicMobs.jar')
Copy-Item -LiteralPath (Join-Path $sources 'context-targeter-skills.yml') -Destination $skillsPath
Copy-Item -LiteralPath (Join-Path $sources 'context-targeter-mobs.yml') -Destination $mobsPath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination $fixture
if ($VanillaCacheDirectory) {
    Copy-Item -LiteralPath $VanillaCacheDirectory -Destination (Join-Path $fixture 'cache') -Recurse
}
[IO.File]::WriteAllText((Join-Path $fixture 'eula.txt'), "eula=true`n")
[IO.File]::WriteAllText((Join-Path $fixture 'plugins/helsteraMobs/config.yml'), "web:`n  enabled: false`n")

# Use compile dependencies from the same pinned Paperclip rather than a second server installation.
$paperZip = [IO.Compression.ZipFile]::OpenRead($paper)
try {
    foreach ($entry in $paperZip.Entries) {
        if ($entry.FullName -match '^META-INF/libraries/net/kyori/.+\.jar$') {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $compileLibraries $entry.Name))
        }
    }
} finally { $paperZip.Dispose() }
$fixtureSource = Join-Path $sources 'ContextTargeterProbe.java'
$classPath = @($paperApi, $plugin, (Join-Path $compileLibraries '*')) -join [IO.Path]::PathSeparator
& javac --release 21 -cp $classPath -d $classes $fixtureSource
if ($LASTEXITCODE -ne 0) { throw 'Context targeter fixture compilation failed.' }
$probeJar = Join-Path $fixture 'plugins/ContextTargeterProbe.jar'
& jar --create --file $probeJar -C $classes . -C $sources plugin.yml
if ($LASTEXITCODE -ne 0) { throw 'Context targeter fixture packaging failed.' }

$server = Start-ProbeProcess 'java' @('-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui', '--port', "$Port") $fixture
$serverOutput = $server.StandardOutput.ReadToEndAsync()
$serverError = $server.StandardError.ReadToEndAsync()
$client = $null
$latest = Join-Path $fixture 'logs/latest.log'
try {
    $startupDeadline = [DateTime]::UtcNow.AddMinutes(4)
    while (-not $server.HasExited -and [DateTime]::UtcNow -lt $startupDeadline -and
            (-not (Test-Path -LiteralPath $latest) -or
                -not (Select-String -LiteralPath $latest -Pattern 'Done \(' -Quiet))) {
        Start-Sleep -Milliseconds 250
    }
    if ($server.HasExited -or -not (Test-Path -LiteralPath $latest) -or
            -not (Select-String -LiteralPath $latest -Pattern 'Done \(' -Quiet)) {
        throw "Paper did not finish startup. Inspect $fixture."
    }
    $client = Start-ProbeProcess 'node' @((Join-Path $sources 'client.js'), "$Port") $PSScriptRoot
    $clientOutput = $client.StandardOutput.ReadToEndAsync()
    $clientError = $client.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds(75)
    while (-not $server.HasExited -and -not $client.HasExited -and [DateTime]::UtcNow -lt $deadline -and
            -not (Select-String -LiteralPath $latest -Pattern 'CONTEXT_TARGETER_(COMPLETE|FAILED)' -Quiet)) {
        Start-Sleep -Milliseconds 250
    }
    $server.StandardInput.WriteLine('stop')
    $server.StandardInput.Flush()
    if (-not $server.WaitForExit(30000) -or $server.ExitCode -ne 0) { throw 'Paper did not stop cleanly.' }
} finally {
    if ($null -ne $client) {
        if (-not $client.HasExited) { $client.Kill(); $client.WaitForExit() }
        [IO.File]::WriteAllText((Join-Path $fixture 'context-client.log'),
            $clientOutput.GetAwaiter().GetResult() + $clientError.GetAwaiter().GetResult())
        $client.Dispose()
    }
    if (-not $server.HasExited) {
        try { $server.StandardInput.WriteLine('stop'); $server.StandardInput.Flush() } catch { }
        if (-not $server.WaitForExit(15000)) { $server.Kill(); $server.WaitForExit() }
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'console.log'),
        $serverOutput.GetAwaiter().GetResult() + $serverError.GetAwaiter().GetResult())
    $server.Dispose()
}

$log = Get-Content -LiteralPath $latest -Raw -Encoding utf8
$observedPaper = [regex]::Match($log, 'This server is running Paper version (1\.21\.1-133[^\r\n]+?) \(Implementing API version ([^\r\n]+?)\)')
$observations = @([regex]::Matches($log,
    '(?m)CONTEXT_TARGETER_CASE ([a-z_-]+) (native=(?:true|false) imported=(?:true|false))\r?$') |
    ForEach-Object { $_.Groups[1].Value + ' ' + $_.Groups[2].Value })
$expectedOutcomes = [ordered]@{
    'creative-ct_trigger' = 'native=false imported=false'
    'creative-ct_modes_on' = 'native=false imported=false'
    'creative-ct_modes_off' = 'native=false imported=false'
    'creative-ct_players_off' = 'native=false imported=false'
    'survival-ct_trigger' = 'native=true imported=true'
    'survival-ct_modes_on' = 'native=true imported=true'
    'survival-ct_modes_off' = 'native=true imported=true'
    'survival-ct_players_off' = 'native=false imported=false'
    'adventure-ct_trigger' = 'native=true imported=true'
    'adventure-ct_modes_on' = 'native=true imported=true'
    'adventure-ct_modes_off' = 'native=true imported=true'
    'adventure-ct_players_off' = 'native=false imported=false'
    'spectator-ct_trigger' = 'native=false imported=false'
    'spectator-ct_modes_on' = 'native=false imported=false'
    'spectator-ct_modes_off' = 'native=false imported=false'
    'spectator-ct_players_off' = 'native=false imported=false'
    'creative-living-players-off' = 'native=false imported=false'
    'survival-living-players-off' = 'native=false imported=false'
    'adventure-living-players-off' = 'native=false imported=false'
    'spectator-living-players-off' = 'native=false imported=false'
    'different-ai-target-ai' = 'native=true imported=true'
    'different-ai-target-inherited' = 'native=false imported=false'
    'missing-ai-target-ai' = 'native=false imported=false'
    'missing-ai-target-inherited' = 'native=false imported=false'
    'inherited-only-ai' = 'native=false imported=false'
    'inherited-only-inherited' = 'native=false imported=false'
    'event-bound-target' = 'native=false imported=false'
}
$observedOutcomes = @{}
foreach ($observation in $observations) {
    $parts = $observation -split ' ', 2
    if ($parts.Count -ne 2 -or $observedOutcomes.ContainsKey($parts[0])) {
        throw "Malformed or duplicate comparison observation: $observation"
    }
    $observedOutcomes[$parts[0]] = $parts[1]
}
if (-not $observedPaper.Success -or $log -match 'CONTEXT_TARGETER_FAILED' -or
        $log -notmatch '(?m)CONTEXT_TARGETER_COMPLETE cleanup=true\r?$' -or
        -not (Select-String -LiteralPath (Join-Path $fixture 'context-client.log') -Pattern '^CONTEXT_TARGETER_PLAYER_READY$' -Quiet) -or
        $observations.Count -ne $expectedOutcomes.Count -or
        @($expectedOutcomes.Keys | Where-Object {
            -not $observedOutcomes.ContainsKey($_) -or $observedOutcomes[$_] -cne $expectedOutcomes[$_]
        }).Count -gt 0 -or
        @($observedOutcomes.Keys | Where-Object { -not $expectedOutcomes.Contains($_) }).Count -gt 0) {
    throw "Incomplete targeter observations. Inspect $fixture."
}
$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = $fixtureId
    evidenceMode = 'exploratory'
    paperVersion = $observedPaper.Groups[1].Value
    paperApiVersion = $observedPaper.Groups[2].Value
    pluginEnabled = $true
    pluginSha256 = Get-Sha256 (Join-Path $fixture 'plugins/helsteraMobs.jar')
    paperJarSha256 = $paperHash
    externalPlugin = 'MythicMobs 5.7.2 (Modrinth FTWvGuE5)'
    externalPluginSha256 = $mythicHash
    runnerSha256 = Get-Sha256 $PSCommandPath
    fixtureProbeSourceSha256 = Get-Sha256 $fixtureSource
    fixtureProbeJarSha256 = Get-Sha256 $probeJar
    sharedSourceSha256 = Get-Sha256 $skillsPath
    log = 'logs/latest.log'
    expectedProbeIds = @('contextEligibilityComparison')
    probes = @([ordered]@{ id = 'contextEligibilityComparison'; status = 'observed'; observations = $observations })
}
$reportText = $report | ConvertTo-Json -Depth 8
$reportPath = Join-Path $fixture 'integration-report.json'
[IO.File]::WriteAllText($reportPath, $reportText)
$python = & (Join-Path $PSScriptRoot 'resolve-python.ps1')
& $python.Source @($python.Prefix) (Join-Path $PSScriptRoot 'validate-report.py') `
    (Join-Path $PSScriptRoot 'integration-report.schema.json') $reportPath
if ($LASTEXITCODE -ne 0) { throw 'Targeter comparison report does not match the schema.' }

$safeLog = [regex]::Replace($log, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
$safeLog = [regex]::Replace($safeLog, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
$safeLog = [regex]::Replace($safeLog, '(令牌[ \t]*[:：][ \t]*)\S+', '$1[REDACTED]')
$file = [IO.File]::Open("$archive.log.gz", [IO.FileMode]::CreateNew)
try {
    $gzip = [IO.Compression.GzipStream]::new($file, [IO.Compression.CompressionLevel]::Optimal)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($safeLog)
        $gzip.Write($bytes, 0, $bytes.Length)
    } finally { $gzip.Dispose() }
} finally { $file.Dispose() }
[IO.File]::Copy($reportPath, "$archive.json")
Write-Output "Context targeter comparison: $archive.json"
$observations | Write-Output
