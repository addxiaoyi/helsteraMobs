param(
    [ValidatePattern('^r[1-9][0-9]*$')][string]$EvidenceRevision = 'r1',
    [switch]$ObserveOnly,
    [string]$CacheDirectory = ''
)
$ErrorActionPreference = 'Stop'
function Protect-Log([string]$Text) {
    $safe = [regex]::Replace($Text, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
    $safe = [regex]::Replace($safe, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
    $safe = [regex]::Replace($safe, '(令牌[ \t]*[:：][ \t]*)[A-Za-z0-9._~-]+', '$1[REDACTED]')
    return [regex]::Replace($safe, '(?i)(\bauthorization\s*:\s*bearer\s+)[^\s"''<>]+', '$1[REDACTED]')
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$mode = if ($ObserveOnly) { 'native' } else { 'comparison' }
$fixture = "srv-paper-variable-persistence-$mode-$([DateTime]::UtcNow.ToString('yyyyMMdd'))-$EvidenceRevision"
$server = Join-Path $root $fixture
if (Test-Path -LiteralPath $server) { throw "Refusing to reuse $fixture" }
$paper = Join-Path $root 'srv-1.21.1/paper.jar'
$mythic = Join-Path $PSScriptRoot '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
$plugin = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$paperHash = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
$mythicHash = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
if ((Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant() -cne $paperHash -or
        (Get-FileHash -LiteralPath $mythic -Algorithm SHA256).Hash.ToLowerInvariant() -cne $mythicHash) {
    throw 'Persistence comparison requires the pinned Paper 1.21.1-133 and public MythicMobs 5.7.2.'
}
$fixtureModule = Join-Path $PSScriptRoot 'random-spawn-fixture'
& mvn -q -f (Join-Path $fixtureModule 'pom.xml') package
if ($LASTEXITCODE -ne 0) { throw 'Could not build persistence probe.' }
New-Item -ItemType Directory -Path $server | Out-Null
if ($CacheDirectory) {
    foreach ($name in @('cache', 'libraries', 'versions')) {
        if (Test-Path -LiteralPath (Join-Path $CacheDirectory $name)) {
            Copy-Item -LiteralPath (Join-Path $CacheDirectory $name) -Destination $server -Recurse
        }
    }
}
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination $server
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
$data = Join-Path $plugins 'helsteraMobs'
$probeData = Join-Path $plugins 'HelsteraVariablePersistenceProbe'
$nativeMobs = Join-Path $plugins 'MythicMobs/Mobs'
foreach ($path in @($data, $probeData, $nativeMobs)) { New-Item -ItemType Directory -Path $path -Force | Out-Null }
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'helsteraMobs.jar')
Copy-Item -LiteralPath $mythic -Destination (Join-Path $plugins 'MythicMobs.jar')
$probeJar = Join-Path $plugins 'HelsteraVariablePersistenceProbe.jar'
Copy-Item -LiteralPath (Join-Path $fixtureModule 'target/random-spawn-paper-fixture-1.0.0.jar') -Destination $probeJar
Add-Type -AssemblyName System.IO.Compression
$zip = [System.IO.Compression.ZipFile]::Open($probeJar, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $zip.GetEntry('plugin.yml').Delete()
    $writer = [System.IO.StreamWriter]::new($zip.CreateEntry('plugin.yml').Open())
    try { $writer.Write("name: HelsteraVariablePersistenceProbe`nversion: '1.0'`napi-version: '1.21'`nmain: dev.helstera.test.randomspawn.VariablePersistenceProbe`ndepend: [helsteraMobs, MythicMobs]`n") }
    finally { $writer.Dispose() }
} finally { $zip.Dispose() }
$nativeText = "HelsteraRadiusAuditMob:`n  Type: COW`n  Health: 20`n  Options:`n    NoAI: true`n    Despawn: PERSISTENT`n    PreventOtherDrops: true`n"
[System.IO.File]::WriteAllText((Join-Path $nativeMobs 'variable-persistence.yml'), $nativeText)
$config = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'config.yml'))
$config = [regex]::Replace($config, '(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', '  $1: false')
$config = [regex]::Replace($config, '(?m)^  trace-triggers:.*$', '  trace-triggers: false')
$config = [regex]::Replace($config, '(?m)^  mythicmobs: false\s*$', '  mythicmobs: true')
if ($config -match '(?m)^web:\s*\r?\n[ \t]+enabled:\s*true\b') { throw 'Web must stay disabled in this fixture.' }
[System.IO.File]::WriteAllText((Join-Path $data 'config.yml'), $config)
foreach ($name in @('items.yml','loot.yml')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $data }
[System.IO.File]::WriteAllText((Join-Path $data 'skills.yml'), "skills: {}`n")
[System.IO.File]::WriteAllText((Join-Path $data 'spawners.yml'), "spawners: {}`n")
$pluginHash = (Get-FileHash -LiteralPath $plugin -Algorithm SHA256).Hash.ToLowerInvariant()
$probes = @()
$snapshots = @()
$logs = @()
foreach ($stage in @('write', 'verify', 'final')) {
    [System.IO.File]::WriteAllText((Join-Path $probeData 'config.yml'), "stage: $stage`nobserve-only: $($ObserveOnly.IsPresent.ToString().ToLowerInvariant())`n")
    $latestLog = Join-Path $server 'logs/latest.log'
    if (Test-Path -LiteralPath $latestLog) { Move-Item -LiteralPath $latestLog -Destination (Join-Path $server "before-$stage.log") }
    $info = [System.Diagnostics.ProcessStartInfo]::new('java')
    foreach ($arg in @('-Xms1G','-Xmx2G','-jar','paper.jar','nogui')) { $info.ArgumentList.Add($arg) }
    $info.WorkingDirectory = $server
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw "Paper failed to start for $stage" }
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(150)
        while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
            if ((Test-Path -LiteralPath $latestLog) -and
                    (Select-String -LiteralPath $latestLog -Pattern 'VARIABLE_PERSIST_SUMMARY\|' -Quiet)) { break }
            Start-Sleep -Milliseconds 250
        }
        $text = [System.IO.File]::ReadAllText($latestLog)
        $expectedMode = if ($ObserveOnly) { 'native-observation' } else { 'comparison' }
        if ($text -notmatch "VARIABLE_PERSIST_SUMMARY\|stage=$stage\|observations=144\|mode=$expectedMode\|passed=true") {
            throw "Incomplete variable persistence $stage; inspect retained log."
        }
        $rows = [regex]::Matches($text, "(?m)^.*VARIABLE_PERSIST\|stage=$stage[^\r\n]+$")
        if ($rows.Count -ne 144 -or (-not $ObserveOnly -and @($rows | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count)) {
            throw "Mismatched persistence observations during $stage"
        }
        $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush()
        if (-not $process.WaitForExit(30000)) { throw 'Paper did not stop gracefully.' }
        if ($process.ExitCode -ne 0) { throw 'Paper exited with a failure.' }
        $stageLog = Join-Path $server "persistence-$stage.log"
        [System.IO.File]::WriteAllText($stageLog, (Protect-Log ([System.IO.File]::ReadAllText($latestLog))))
        $logs += [ordered]@{path="persistence-$stage.log"; sha256=(Get-FileHash -LiteralPath $stageLog -Algorithm SHA256).Hash.ToLowerInvariant()}
        if (-not $ObserveOnly) {
            $snapshot = Join-Path $server "persistence-$stage.yml"
            Copy-Item -LiteralPath (Join-Path $data 'scoped-variables.yml') -Destination $snapshot
            $snapshots += [ordered]@{path="persistence-$stage.yml"; sha256=(Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
        $probes += [ordered]@{id="variable.persistence.$stage"; status='passed'; observations=@($rows | ForEach-Object {$_.Value})}
    } finally {
        if (-not $process.HasExited) {
            $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush()
            if (-not $process.WaitForExit(30000)) { $process.Kill() }
        }
        [System.IO.File]::WriteAllText((Join-Path $server "$stage.stdout.log"), (Protect-Log ($stdout.GetAwaiter().GetResult())))
        [System.IO.File]::WriteAllText((Join-Path $server "$stage.stderr.log"), (Protect-Log ($stderr.GetAwaiter().GetResult())))
        $process.Dispose()
    }
    if ($stage -eq 'write') { Start-Sleep -Seconds 21 }
}
if ((Get-FileHash -LiteralPath (Join-Path $plugins 'helsteraMobs.jar') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pluginHash) { throw 'Plugin artifact changed.' }
$report = [ordered]@{
    schemaVersion=1; fixtureDirectory=$fixture; generatedAtUtc=[DateTime]::UtcNow.ToString('o')
    evidenceMode= $(if ($ObserveOnly) {'native-observation'} else {'comparison'})
    pluginSha256=$pluginHash; paperJarSha256=$paperHash; mythicMobsSha256=$mythicHash
    probeSourceSha256=(Get-FileHash -LiteralPath (Join-Path $fixtureModule 'src/main/java/dev/helstera/test/randomspawn/VariablePersistenceProbe.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    probeJarSha256=(Get-FileHash -LiteralPath $probeJar -Algorithm SHA256).Hash.ToLowerInvariant()
    harnessSourceSha256=(Get-FileHash -LiteralPath (Join-Path $fixtureModule 'src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    runnerSourceSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    probes=$probes; logs=$logs; snapshots=$snapshots
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $server 'variable-persistence-report.json') -Encoding utf8
if (-not $ObserveOnly) {
    $python = & (Join-Path $PSScriptRoot 'resolve-python.ps1')
    & $python.Source @($python.Prefix) (Join-Path $PSScriptRoot 'validate-variable-persistence.py') $server
    if ($LASTEXITCODE -ne 0) { throw 'Variable persistence evidence validation failed.' }
    $reportName = "paper-1.21.1-variable-persistence-$([DateTime]::UtcNow.ToString('yyyyMMdd'))-$EvidenceRevision"
    $reportPath = Join-Path $PSScriptRoot "reports/$reportName.json"
    $archivePath = Join-Path $PSScriptRoot "reports/$reportName-logs.zip"
    if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $archivePath)) { throw 'Refusing to overwrite archived evidence.' }
    $archive = [System.IO.Compression.ZipFile]::Open($archivePath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($item in @($logs) + @($snapshots)) {
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $server $item.path), $item.path)
        }
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $probeData 'identities.yml'), 'identities.yml')
    } finally { $archive.Dispose() }
    $report.logArchive = Split-Path -Leaf $archivePath
    $report.logArchiveSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding utf8
}
Write-Output "Variable persistence $mode fixture passed: $fixture (432 observations, three boots)"
