param(
    [ValidatePattern('^r[1-9][0-9]*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$PaperJarPath = '',
    [string]$VanillaCachePath = '',
    [switch]$MobRadiusAudit,
    [switch]$MobRadiusStrict
)

$ErrorActionPreference = 'Stop'
$MobRadiusAudit = $MobRadiusAudit -or $MobRadiusStrict
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixtureDir = Join-Path $PSScriptRoot 'random-spawn-fixture'
$reports = Join-Path $PSScriptRoot 'reports'
$date = [DateTime]::UtcNow.ToString('yyyyMMdd')
$fixtureId = "srv-paper-random-spawn-replace-$date-$EvidenceRevision"
$reportName = "paper-1.21.1-random-spawn-replace-$date-$EvidenceRevision"
$server = Join-Path $root $fixtureId
$paper = if ($PaperJarPath) { [System.IO.Path]::GetFullPath($PaperJarPath) }
    else { Join-Path $root 'srv-1.21.1/paper.jar' }
$pluginJar = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$mythicJar = Join-Path $PSScriptRoot '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
$mythicSha256 = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
if (-not (Test-Path -LiteralPath $mythicJar -PathType Leaf) -or
        (Get-FileHash -LiteralPath $mythicJar -Algorithm SHA256).Hash.ToLowerInvariant() -cne $mythicSha256) {
    throw 'Player condition comparison requires the pinned MythicMobs 5.7.2 artifact in .external-cache.'
}
$probeJar = Join-Path $fixtureDir 'target/random-spawn-paper-fixture-1.0.0.jar'
$reportPath = Join-Path $reports "$reportName.json"
$logArchivePath = Join-Path $reports "$reportName.log.gz"
$matrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$release = @($matrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($release.Count -ne 1 -or $release[0].serverVersion -ne '1.21.1-133') {
    throw 'RandomSpawn Paper fixture requires the pinned Paper 1.21.1-133 release artifact.'
}
if (-not (Test-Path -LiteralPath $paper -PathType Leaf) -or
        (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        [string]$release[0].serverJarSha256) {
    throw 'Paper jar does not match the pinned 1.21.1-133 release artifact.'
}
if (-not (Test-Path -LiteralPath $pluginJar -PathType Leaf)) {
    throw 'Build the complete Helstera plugin first.'
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules/mineflayer/package.json') -PathType Leaf)) {
    throw 'Install the paper-integration npm dependencies before running the natural spawn probe.'
}
& mvn -q -f (Join-Path $fixtureDir 'pom.xml') package
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $probeJar)) {
    throw 'Could not build the RandomSpawn Paper fixture plugin.'
}
if ($server -eq $root -or -not $server.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture path escaped the repository root.' }
if (Test-Path -LiteralPath $server) { throw "Refusing to reuse Paper fixture directory: $server" }
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $logArchivePath)) {
    throw "Refusing to overwrite archived evidence for $EvidenceRevision. Choose a new revision."
}

[void][System.IO.Directory]::CreateDirectory($server)
[void][System.IO.Directory]::CreateDirectory($reports)
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
if ($VanillaCachePath) {
    $paperArchive = [System.IO.Compression.ZipFile]::OpenRead($paper)
    try {
        $entry = $paperArchive.GetEntry('META-INF/download-context')
        if ($null -eq $entry) { throw 'Pinned Paper jar has no download-context metadata.' }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $downloadContext = $reader.ReadToEnd().Trim() -split '\s+' }
        finally { $reader.Dispose() }
    } finally { $paperArchive.Dispose() }
    if ($downloadContext.Count -ne 3 -or $downloadContext[2] -cne 'mojang_1.21.1.jar' -or
            -not (Test-Path -LiteralPath $VanillaCachePath -PathType Leaf) -or
            (Get-FileHash -LiteralPath $VanillaCachePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
                $downloadContext[0]) {
        throw 'Vanilla cache must match the SHA-256 and filename in the pinned Paper download-context.'
    }
    $cache = Join-Path $server 'cache'
    [void][System.IO.Directory]::CreateDirectory($cache)
    Copy-Item -LiteralPath $VanillaCachePath -Destination (Join-Path $cache $downloadContext[2])
}
$serverProperties = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'server.properties'))
$serverProperties = [regex]::Replace($serverProperties, '(?m)^spawn-monsters=false\s*$', 'spawn-monsters=true')
$serverProperties = [regex]::Replace($serverProperties, '(?m)^gamemode=creative\s*$', 'gamemode=survival')
[System.IO.File]::WriteAllText((Join-Path $server 'server.properties'), $serverProperties,
    [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText((Join-Path $server 'eula.txt'), "eula=true`n",
    [System.Text.UTF8Encoding]::new($false))
$plugins = Join-Path $server 'plugins'
$data = Join-Path $plugins 'helsteraMobs'
[void][System.IO.Directory]::CreateDirectory($data)
Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $plugins 'helsteraMobs.jar')
Copy-Item -LiteralPath $probeJar -Destination (Join-Path $plugins 'HelsteraRandomSpawnPaperFixture.jar')
Copy-Item -LiteralPath $mythicJar -Destination (Join-Path $plugins 'MythicMobs.jar')
if ($MobRadiusAudit) {
    $probeData = Join-Path $plugins 'HelsteraRandomSpawnPaperFixture'
    [void][System.IO.Directory]::CreateDirectory($probeData)
    [System.IO.File]::WriteAllText((Join-Path $probeData 'config.yml'), "mob-radius-audit: true`n",
        [System.Text.UTF8Encoding]::new($false))
    $nativeMobs = Join-Path $plugins 'MythicMobs/Mobs'
    [void][System.IO.Directory]::CreateDirectory($nativeMobs)
    Copy-Item -LiteralPath (Join-Path $fixtureDir 'native-mobs/radius-audit.yml') -Destination $nativeMobs
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.yml') -Destination (Join-Path $data 'config.yml')
$pluginConfig = Join-Path $data 'config.yml'
$configText = [System.IO.File]::ReadAllText($pluginConfig)
$configText = [regex]::Replace($configText,
    '(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', '  $1: false')
$configText = [regex]::Replace($configText, '(?m)^  trace-triggers:\s*true\s*$', '  trace-triggers: false')
if ($MobRadiusAudit) {
    if ($configText -notmatch '(?m)^  mythicmobs: false\s*$') {
        throw 'Expected the isolated fixture MythicMobs integration toggle.'
    }
    $configText = [regex]::Replace($configText, '(?m)^  mythicmobs: false\s*$', '  mythicmobs: true')
}
if ($configText -match '(?m)^web:\s*\r?\n[ \t]+enabled:\s*true\b') {
    throw 'The Paper fixture must keep the web server disabled.'
}
[System.IO.File]::WriteAllText($pluginConfig, $configText, [System.Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'loot.yml') -Destination (Join-Path $data 'loot.yml')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'items.yml') -Destination (Join-Path $data 'items.yml')
[System.IO.File]::WriteAllText((Join-Path $data 'spawners.yml'), "spawners: {}`n",
    [System.Text.UTF8Encoding]::new($false))
# Keep source RandomSpawns out of MythicMobs' live configuration so only
# Helstera handles the spawn probes; native MM is used for condition comparison.
$mythicRoot = Join-Path $plugins 'mythic-import-source'
New-Item -ItemType Directory -Path (Join-Path $mythicRoot 'Mobs') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $mythicRoot 'RandomSpawns') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $fixtureDir 'Mobs/fixture.yml') -Destination (Join-Path $mythicRoot 'Mobs/fixture.yml')
Copy-Item -LiteralPath (Join-Path $fixtureDir 'RandomSpawns/replace.yml') -Destination (Join-Path $mythicRoot 'RandomSpawns/replace.yml')
$mobs = Join-Path $data 'mobs'
[void][System.IO.Directory]::CreateDirectory($mobs)
Copy-Item -Path (Join-Path $fixtureDir 'mobs/*') -Destination $mobs

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$psi.Arguments = '-Xms1G -Xmx2G -jar paper.jar nogui'
$psi.WorkingDirectory = $server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
if (-not $process.Start()) { throw 'Failed to start isolated Paper server.' }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$clientProcess = $null
$clientStdoutTask = $null
$clientStderrTask = $null
$latestLog = Join-Path $server 'logs/latest.log'
try {
    $startupDeadline = [DateTime]::UtcNow.AddMinutes(4)
    while ([DateTime]::UtcNow -lt $startupDeadline -and -not $process.HasExited) {
        if ((Test-Path -LiteralPath $latestLog) -and
            (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($process.HasExited -or -not (Test-Path -LiteralPath $latestLog) -or
        -not (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) {
        throw 'Paper did not finish startup; inspect the retained fixture logs.'
    }
    $clientInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $clientInfo.FileName = 'node'
    $clientInfo.WorkingDirectory = $PSScriptRoot
    $clientInfo.UseShellExecute = $false
    $clientInfo.RedirectStandardOutput = $true
    $clientInfo.RedirectStandardError = $true
    $clientInfo.ArgumentList.Add((Join-Path $fixtureDir 'natural-spawn-client.js'))
    $clientInfo.ArgumentList.Add('127.0.0.1')
    $clientInfo.ArgumentList.Add('25731')
    $clientProcess = [System.Diagnostics.Process]::new()
    $clientProcess.StartInfo = $clientInfo
    if (-not $clientProcess.Start()) { throw 'Failed to start the natural-spawn Paper client.' }
    $clientStdoutTask = $clientProcess.StandardOutput.ReadToEndAsync()
    $clientStderrTask = $clientProcess.StandardError.ReadToEndAsync()
    $joinDeadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $joinDeadline -and -not $process.HasExited -and
        -not (Select-String -LiteralPath $latestLog -Pattern 'RandomSpawnProbe joined the game' -Quiet)) {
        if ($clientProcess.HasExited) { throw 'Natural-spawn Paper client exited before joining.' }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Select-String -LiteralPath $latestLog -Pattern 'RandomSpawnProbe joined the game' -Quiet)) {
        throw 'Natural-spawn Paper client did not join the isolated server.'
    }
    $probePattern = 'RANDOM_SPAWN_PAPER_FIXTURE\|passed=(?:true|false)\|paper=.*1\.21\.1-133.*\|api=1\.21\.1.*\|importer=(?:true|false).*\|synthetic=(?:true|false).*\|natural=(?:true|false)'
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited -and
        -not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
        Start-Sleep -Milliseconds 250
    }
    $summary = [regex]::Match([System.IO.File]::ReadAllText($latestLog),
        '(?m)^.*RANDOM_SPAWN_PAPER_FIXTURE\|passed=(true|false)\|[^\r\n]*$')
    if (-not $summary.Success -or $summary.Groups[1].Value -ne 'true') {
        throw 'RandomSpawn Paper fixture did not pass every synthetic and native-natural probe; inspect the retained fixture logs.'
    }
    if ($MobRadiusAudit) {
        while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited -and
            -not (Select-String -LiteralPath $latestLog -Pattern 'VARIABLE_EXPIRY_SUMMARY\|' -Quiet)) {
            Start-Sleep -Milliseconds 250
        }
        if (-not (Select-String -LiteralPath $latestLog -Pattern 'VARIABLE_EXPIRY_SUMMARY\|observations=420.*\|cleanup=true\|passed=true' -Quiet)) {
            throw 'Variable expiry lifecycle did not complete successfully.'
        }
    }
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) { throw 'Paper did not stop after the focused RandomSpawn probe.' }
} finally {
    if (-not $process.HasExited) {
        try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
        if (-not $process.WaitForExit(15000)) { $process.Kill() }
    }
    if ($null -ne $clientProcess) {
        if (-not $clientProcess.HasExited -and -not $clientProcess.WaitForExit(5000)) { $clientProcess.Kill() }
        $clientProcess.WaitForExit()
        if ($null -ne $clientStdoutTask -and $null -ne $clientStderrTask) {
            $clientText = $clientStdoutTask.GetAwaiter().GetResult() + $clientStderrTask.GetAwaiter().GetResult()
            [System.IO.File]::WriteAllText((Join-Path $server 'natural-spawn-client.log'), $clientText,
                [System.Text.UTF8Encoding]::new($false))
        }
        $clientProcess.Dispose()
    }
    if (Test-Path -LiteralPath $latestLog) {
        $partialLog = [System.IO.File]::ReadAllText($latestLog)
        $partialLog = [regex]::Replace($partialLog, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
        $partialLog = [regex]::Replace($partialLog, '(令牌[ \t]*[:：][ \t]*)\S+', '$1[REDACTED]')
        [System.IO.File]::WriteAllText($latestLog, $partialLog, [System.Text.UTF8Encoding]::new($false))
    }
}
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)." }

$log = [System.IO.File]::ReadAllText($latestLog)
$safeLog = [regex]::Replace($log, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
$safeLog = [regex]::Replace($safeLog, '(令牌[ \t]*[:：][ \t]*)\S+', '$1[REDACTED]')
$safeLog = [regex]::Replace($safeLog, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
$safeLog = [regex]::Replace($safeLog, '(?i)(\bauthorization\s*:\s*bearer\s+)[^\s"''<>]+', '$1[REDACTED]')
[System.IO.File]::WriteAllText($latestLog, $safeLog, [System.Text.UTF8Encoding]::new($false))
if ($safeLog -match '(?i)[?&]token=(?!\[REDACTED\])[^&\s"''<>]+' -or
    $safeLog -match '令牌[ \t]*[:：][ \t]*(?!\[REDACTED\])\S+' -or
    $safeLog -match '(?i)__cf_bm=(?!\[REDACTED\])[^;\s"''<>]+' -or
    $safeLog -match '(?i)\bauthorization\s*:\s*bearer\s+(?!\[REDACTED\])[^\s"''<>]+') {
    throw 'Credential text remains after Paper-log redaction.'
}
$importMatch = [regex]::Match($log, '(?m)^.*RANDOM_SPAWN_IMPORT\|passed=(true|false)\|[^\r\n]*$')
if (-not $importMatch.Success -or $importMatch.Groups[1].Value -ne 'true') {
    throw 'Mythic RandomSpawns import-to-runtime Paper probe did not pass.'
}
$importProbe = [ordered]@{
    id = 'randomSpawn.importer'
    status = 'passed'
    observations = @($importMatch.Value)
}
$typesMatch = [regex]::Match($log,
    '(?m)^.*RANDOM_SPAWN_TYPES\|passed=(true|false)\|alternatives=(\d+):(\d+)\|validEvents=16/16\s*$')
if (-not $typesMatch.Success -or $typesMatch.Groups[1].Value -ne 'true' -or
        [int]$typesMatch.Groups[2].Value -le 0 -or [int]$typesMatch.Groups[3].Value -le 0) {
    throw 'Imported Mythic Types alternatives did not produce both mob entries on Paper.'
}
$typesProbe = [ordered]@{
    id = 'randomSpawn.types-alternatives'
    status = 'passed'
    observations = @($typesMatch.Value)
}
$caseIds = @('replace', 'condition-control', 'reason-control', 'deny', 'deny-condition-control',
    'cooldown-immediate', 'cooldown-expired')
$observations = @()
$probes = @($importProbe, $typesProbe)
$addMatch = [regex]::Match($log,
    '(?m)^.*RANDOM_SPAWN_ADD\|passed=(true|false)\|[^\r\n]*$')
if (-not $addMatch.Success -or $addMatch.Groups[1].Value -ne 'true' -or
        $addMatch.Value -notmatch '\|generator=CLUSTER\|position=LAND\|spawned=1\|') {
    throw 'Imported Mythic ADD did not produce exactly one managed mob through the opt-in CLUSTER generator.'
}
$probes += [ordered]@{
    id = 'randomSpawn.add-cluster'
    status = 'passed'
    observations = @($addMatch.Value)
}
$addConditionsMatch = [regex]::Match($log,
    '(?m)^.*RANDOM_SPAWN_ADD_CONDITIONS\|passed=(true|false)\|[^\r\n]*$')
if (-not $addConditionsMatch.Success -or $addConditionsMatch.Groups[1].Value -ne 'true' -or
        $addConditionsMatch.Value -notmatch '\|predicates=world,time,weather\|day-spawned=0\|rainy-night-spawned=0\|clear-night-spawned=1\|wrong-world-spawned=0\|') {
    throw 'Imported candidate-point world/time/weather ADD conditions failed their Paper pass/reject controls.'
}
$probes += [ordered]@{
    id = 'randomSpawn.add-location-conditions'
    status = 'passed'
    observations = @($addConditionsMatch.Value)
}
$addBorderMatch = [regex]::Match($log,
    '(?m)^.*RANDOM_SPAWN_ADD_BORDER\|passed=(true|false)\|[^\r\n]*$')
if (-not $addBorderMatch.Success -or $addBorderMatch.Groups[1].Value -ne 'true' -or
        $addBorderMatch.Value -notmatch '\|spawned=0\|before=(\d+)\|after=\1(?:\||$)') {
    throw 'Imported ADD failed to reject candidates outside the live Paper world border.'
}
$probes += [ordered]@{
    id = 'randomSpawn.add-world-border'
    status = 'passed'
    observations = @($addBorderMatch.Value)
}
$playerCases = [ordered]@{
    playercountadd = 1
    playercountrejectedadd = 0
    playerwithinadd = 1
    playerdistanceconditionaladd = 0
    playernotwithinadd = 1
    playernotwithinrejectedadd = 0
}
$playerObservations = @()
foreach ($entry in $playerCases.GetEnumerator()) {
    $match = [regex]::Match($log, "(?m)^.*RANDOM_SPAWN_ADD_PLAYER\|id=$($entry.Key)\|passed=true\|condition=[^|]+\|expected=$($entry.Value)\|spawned=$($entry.Value)\|before=(\d+)\|after=(\d+)\|evaluated=[1-9][0-9]*\s*$")
    if (-not $match.Success -or [int]$match.Groups[2].Value -ne ([int]$match.Groups[1].Value + $entry.Value)) {
        throw "Imported player ADD condition failed its pass/reject control: $($entry.Key)"
    }
    $playerObservations += $match.Value
}
$probes += [ordered]@{
    id = 'randomSpawn.add-player-conditions'
    status = 'passed'
    observations = $playerObservations
}
$modeObservations = @()
foreach ($mode in @('SURVIVAL', 'ADVENTURE', 'CREATIVE', 'SPECTATOR')) {
    $expected = if ($mode -in @('SURVIVAL', 'ADVENTURE')) { 'true,true,true,false,false,true' }
        else { 'false,false,false,false,true,false' }
    $match = [regex]::Match($log, "(?m)^.*RANDOM_SPAWN_PLAYER_MODE\|mode=$mode\|passed=true\|count-default,count-include-spectator,within,not-within,count-zero,count-one=$expected\s*$")
    if (-not $match.Success) { throw "Player radius semantics failed for mode $mode" }
    $modeObservations += $match.Value
}
foreach ($pattern in @(
    '(?m)^.*RANDOM_SPAWN_PLAYER_SPHERE\|passed=true\|radius=1\|offset=0\.9,0,0\.9\s*$',
    '(?m)^.*RANDOM_SPAWN_PLAYER_SEMANTICS\|passed=true\|mode-restored=true\s*$')) {
    $match = [regex]::Match($log, $pattern)
    if (-not $match.Success) { throw 'Player radius sphere or mode restoration failed.' }
    $modeObservations += $match.Value
}
$probes += [ordered]@{
    id = 'randomSpawn.player-radius-semantics'
    status = 'passed'
    observations = $modeObservations
}
$syntaxMatches = [regex]::Matches($log,
    '(?m)^.*PLAYER_SYNTAX_CASE\|source=[^|]+\|runtime=[^|]+\|comparisons=12\|mismatches=0\s*$')
$syntaxSummary = [regex]::Match($log,
    '(?m)^.*PLAYER_SYNTAX_NATIVE\|passed=true\|cases=11\|comparisons=132\|mismatches=0\|mode-restored=true\s*$')
if ($syntaxMatches.Count -ne 11 -or -not $syntaxSummary.Success) {
    throw 'Native/imported player condition syntax comparison did not pass all 132 checks.'
}
$probes += [ordered]@{
    id = 'randomSpawn.player-syntax-native-comparison'
    status = 'passed'
    observations = @($syntaxMatches | ForEach-Object { $_.Value }) + @($syntaxSummary.Value)
}
$extensionObservations = @()
foreach ($source in @('playersInRadius >=2', 'playersInRadius{r=4} <=1')) {
    $match = [regex]::Match($log, "(?m)^.*PLAYER_SYNTAX_EXTENSION\|source=$([regex]::Escape($source))\|nativeRejected=true\|importedBound=true\s*$")
    if (-not $match.Success) { throw "Missing inclusive comparator boundary: $source" }
    $extensionObservations += $match.Value
}
$probes += [ordered]@{
    id = 'randomSpawn.player-syntax-extension-boundary'
    status = 'passed'
    observations = $extensionObservations
}
foreach ($id in $caseIds) {
    $match = [regex]::Match($log, "(?m)^.*RANDOM_SPAWN_CASE\|id=$([regex]::Escape($id))\|passed=(true|false)[^\r\n]*$")
    if (-not $match.Success) { throw "Missing RandomSpawn Paper observation: $id" }
    $line = $match.Value
    $observations += $line
    $status = if ($match.Groups[1].Value -eq 'true') { 'passed' } else { 'failed' }
    $probes += [ordered]@{ id = "randomSpawn.$id"; status = $status; observations = @($line) }
}
$naturalMatch = [regex]::Match($log,
    '(?m)^.*RANDOM_SPAWN_NATURAL_CASE\|passed=(true|false)\|[^\r\n]*$')
if (-not $naturalMatch.Success -or $naturalMatch.Groups[1].Value -ne 'true' -or
        $naturalMatch.Value -notmatch '\|observed=[1-9][0-9]*\|cancelled=[1-9][0-9]*\|' -or
        $naturalMatch.Value -notmatch '\|blockLight=0(?:\||$)') {
    throw 'Missing or failed server-native NATURAL spawn probe.'
}
$naturalWindowMatch = [regex]::Match($log,
    '(?m)^.*RANDOM_SPAWN_NATURAL_WINDOW\|enabled=true\|playerReady=true\|[^\r\n]*$')
if (-not $naturalWindowMatch.Success -or
        $naturalWindowMatch.Value -notmatch '\|doMobSpawning=true\|spawnMonsters=true\|blockLight=0') {
    throw 'Native-spawn preconditions were not observed in Paper.'
}
$probes += [ordered]@{
    id = 'randomSpawn.natural-engine'
    status = 'passed'
    observations = @($naturalWindowMatch.Value, $naturalMatch.Value)
}
$paperMatch = [regex]::Match($log,
    'This server is running Paper version (1\.21\.1-133[^\s]*) \(.*?Implementing API version (1\.21\.1[^)]*)\)')
if (-not $paperMatch.Success -or -not $log.Contains('Enabling helsteraMobs v1.0.0')) {
    throw 'Focused report checks found incomplete Paper build/plugin startup evidence.'
}
if ($MobRadiusAudit) {
    $auditLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_AUDIT\|source=[^\r\n]+$')
    $auditSummary = [regex]::Match($log,
        '(?m)^.*MOB_RADIUS_AUDIT_SUMMARY\|observations=120\|mismatches=(\d+)\|cleanup=true\s*$')
    if ($auditLines.Count -ne 120 -or -not $auditSummary.Success) { throw 'Incomplete MobsInRadius native audit.' }
    if ($MobRadiusStrict -and ($auditSummary.Groups[1].Value -ne '0' -or
            @($auditLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0)) {
        throw 'MobsInRadius native comparison differs; inspect the retained fixture logs.'
    }
    $probes += [ordered]@{
        id = 'mobRadius.native-audit'
        status = if ($MobRadiusStrict) { 'passed' } else { 'observed' }
        observations = @($auditLines | ForEach-Object { $_.Value }) + @($auditSummary.Value)
    }
    $registryLine = [regex]::Match($log,
        '(?m)^.*MOB_RADIUS_REGISTRY\|nearOriginCondition=false\|radiusCondition=true\|nearOriginTargeter=true\|targeterClass=io\.lumine\.mythic\.core\.skills\.targeters\.MobsNearOriginTargeter\s*$')
    if (-not $registryLine.Success) { throw 'Missing native MobsNearOrigin registration evidence.' }
    $dynamicLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_DYNAMIC\|radius=[^\r\n]+$')
    $oracleLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_PLACEHOLDER\|token=[^\r\n]+$')
    $dynamicSummary = [regex]::Match($log,
        '(?m)^.*MOB_RADIUS_DYNAMIC_SUMMARY\|observations=96\|placeholderOracles=6\|publicEdition=true\|passed=true\s*$')
    if ($dynamicLines.Count -ne 96 -or $oracleLines.Count -ne 6 -or -not $dynamicSummary.Success -or
            @(@($dynamicLines) + @($oracleLines) | Where-Object { $_.Value -notmatch '\|passed=true$' }).Count -ne 0) {
        throw 'Incomplete dynamic radius extension/edition-boundary evidence.'
    }
    $probes += [ordered]@{
        id = 'mobRadius.native-registration'
        status = 'passed'
        observations = @($registryLine.Value)
    }
    $probes += [ordered]@{
        id = 'mobRadius.dynamic-extension-boundary'
        status = 'passed'
        observations = @($dynamicLines | ForEach-Object { $_.Value }) +
            @($oracleLines | ForEach-Object { $_.Value }) + @($dynamicSummary.Value)
    }
    $randomLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_RANDOM\|radius=[^\r\n]+$')
    $sampleLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_RANDOM_SAMPLES\|radius=[^\r\n]+$')
    $randomSummary = [regex]::Match($log,
        '(?m)^.*MOB_RADIUS_RANDOM_SUMMARY\|comparisons=48\|drawPairs=256\|passed=true\s*$')
    if ($randomLines.Count -ne 48 -or $sampleLines.Count -ne 8 -or -not $randomSummary.Success -or
            @($randomLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0 -or
            @($sampleLines | Where-Object { $_.Value -notmatch '\|passed=true$' }).Count -ne 0) {
        throw 'Incomplete random radius native comparison evidence.'
    }
    $probes += [ordered]@{
        id = 'mobRadius.random-native-comparison'
        status = 'passed'
        observations = @($randomLines | ForEach-Object { $_.Value }) +
            @($sampleLines | ForEach-Object { $_.Value }) + @($randomSummary.Value)
    }
    $dispatchLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_DISPATCH\|field=[^\r\n]+$')
    $dispatchSummary = [regex]::Match($log,
        '(?m)^.*MOB_RADIUS_DISPATCH_SUMMARY\|comparisons=576\|scopes=3\|scenes=8\|passed=true\s*$')
    if ($dispatchLines.Count -ne 576 -or -not $dispatchSummary.Success -or
            @($dispatchLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete native radius dispatch evidence.'
    }
    $probes += [ordered]@{
        id = 'mobRadius.condition-dispatch'
        status = 'passed'
        observations = @($dispatchLines | ForEach-Object { $_.Value }) + @($dispatchSummary.Value)
    }
    $triggerLines = [regex]::Matches($log, '(?m)^.*NONLIVING_TRIGGER\|scene=[^\r\n]+$')
    $triggerSummary = [regex]::Match($log,
        '(?m)^.*NONLIVING_TRIGGER_SUMMARY\|comparisons=864\|scenes=9\|conditions=12\|passed=true\s*$')
    if ($triggerLines.Count -ne 864 -or -not $triggerSummary.Success -or
            @($triggerLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete non-living trigger native comparison evidence.'
    }
    $probes += [ordered]@{
        id = 'condition.nonliving-trigger-dispatch'
        status = 'passed'
        observations = @($triggerLines | ForEach-Object { $_.Value }) + @($triggerSummary.Value)
    }
    $comparisonLines = [regex]::Matches($log, '(?m)^.*COMPARISON_DISPATCH\|condition=[^\r\n]+$')
    $comparisonSummary = [regex]::Match($log,
        '(?m)^.*COMPARISON_DISPATCH_SUMMARY\|comparisons=14280\|conditions=34\|scenes=21\|modes=5\|passed=true\s*$')
    if ($comparisonLines.Count -ne 14280 -or -not $comparisonSummary.Success -or
            @($comparisonLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete native comparison-condition dispatch evidence.'
    }
    $probes += [ordered]@{
        id = 'condition.comparison-dispatch'
        status = 'passed'
        observations = @($comparisonLines | ForEach-Object { $_.Value }) + @($comparisonSummary.Value)
    }
    $relationLines = [regex]::Matches($log, '(?m)^.*RELATIONAL_DISPATCH\|condition=[^\r\n]+$')
    $relationSummary = [regex]::Match($log,
        '(?m)^.*RELATIONAL_DISPATCH_SUMMARY\|comparisons=6820\|conditions=11\|scenes=31\|modes=5\|passed=true\s*$')
    if ($relationLines.Count -ne 6820 -or -not $relationSummary.Success -or
            @($relationLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete native relation/cuboid comparison evidence.'
    }
    $probes += [ordered]@{
        id = 'condition.relational-dispatch'
        status = 'passed'
        observations = @($relationLines | ForEach-Object { $_.Value }) + @($relationSummary.Value)
    }
    $expiryLines = [regex]::Matches($log, '(?m)^.*VARIABLE_EXPIRY\|scope=[^\r\n]+$')
    $expirySnapshotLines = [regex]::Matches($log, '(?m)^.*VARIABLE_EXPIRY_SNAPSHOT\|phase=[^\r\n]+$')
    $expirySummary = [regex]::Match($log,
        '(?m)^.*VARIABLE_EXPIRY_SUMMARY\|observations=420\|scopes=5\|cases=14\|phases=3\|paths=2\|snapshotChecks=6\|elapsedMillis=\d+\|cleanup=true\|passed=true\s*$')
    if ($expiryLines.Count -ne 420 -or $expirySnapshotLines.Count -ne 6 -or -not $expirySummary.Success -or
            @(@($expiryLines) + @($expirySnapshotLines) | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete native variable expiry lifecycle evidence.'
    }
    $probes += [ordered]@{
        id = 'variable.expiry-lifecycle'
        status = 'passed'
        observations = @($expiryLines | ForEach-Object { $_.Value }) + @($expirySnapshotLines | ForEach-Object { $_.Value }) + @($expirySummary.Value)
    }
    $variablePlaceholderLines = [regex]::Matches($log, '(?m)^.*VARIABLE_PLACEHOLDER\|token=[^\r\n]+$')
    $variablePlaceholderSummary = [regex]::Match($log,
        '(?m)^.*VARIABLE_PLACEHOLDER_SUMMARY\|comparisons=6720\|tokens=84\|scenes=10\|modes=4\|paths=2\|boundaries=\d+\|schedulerTicks=\d+\|passed=true\s*$')
    if ($variablePlaceholderLines.Count -ne 6720 -or -not $variablePlaceholderSummary.Success -or
            @($variablePlaceholderLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete scoped variable placeholder native comparison evidence.'
    }
    $probes += [ordered]@{
        id = 'placeholder.scoped-variables'
        status = 'passed'
        observations = @($variablePlaceholderLines | ForEach-Object { $_.Value }) + @($variablePlaceholderSummary.Value)
    }
    $triggerPlaceholderLines = [regex]::Matches($log, '(?m)^.*TRIGGER_PLACEHOLDER\|token=[^\r\n]+$')
    $triggerPlaceholderSummary = [regex]::Match($log,
        '(?m)^.*TRIGGER_PLACEHOLDER_SUMMARY\|comparisons=1020\|tokens=34\|scenes=10\|modes=3\|boundaries=86\|schedulerTicks=\d+\|passed=true\s*$')
    if ($triggerPlaceholderLines.Count -ne 1020 -or -not $triggerPlaceholderSummary.Success -or
            @($triggerPlaceholderLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete trigger placeholder native comparison evidence.'
    }
    $probes += [ordered]@{
        id = 'placeholder.trigger-context'
        status = 'passed'
        observations = @($triggerPlaceholderLines | ForEach-Object { $_.Value }) + @($triggerPlaceholderSummary.Value)
    }
    $distanceLines = [regex]::Matches($log, '(?m)^.*DYNAMIC_DISTANCE_DISPATCH\|condition=[^\r\n]+$')
    $distanceSummary = [regex]::Match($log,
        '(?m)^.*DYNAMIC_DISTANCE_DISPATCH_SUMMARY\|comparisons=17680\|conditions=34\|scenes=26\|modes=5\|passed=true\s*$')
    if ($distanceLines.Count -ne 17680 -or -not $distanceSummary.Success -or
            @($distanceLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete dynamic distance native comparison evidence.'
    }
    $probes += [ordered]@{
        id = 'condition.dynamic-distance-dispatch'
        status = 'passed'
        observations = @($distanceLines | ForEach-Object { $_.Value }) + @($distanceSummary.Value)
    }
    $locationLines = [regex]::Matches($log, '(?m)^.*LOCATION_TARGET_GUARD\|scene=[^\r\n]+$')
    $locationSummary = [regex]::Match($log,
        '(?m)^.*LOCATION_TARGET_GUARD_SUMMARY\|comparisons=672\|selectors=7\|aliases=4\|publicNativeGuardApplied=false\|reference=condition-library\|passed=true\s*$')
    if ($locationLines.Count -ne 672 -or -not $locationSummary.Success -or
            @($locationLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0) {
        throw 'Incomplete location target guard extension-boundary evidence.'
    }
    $probes += [ordered]@{
        id = 'targeter.location-condition-extension-boundary'
        status = 'passed'
        observations = @($locationLines | ForEach-Object { $_.Value }) + @($locationSummary.Value)
    }
    $lifecycleLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_LIFECYCLE\|phase=[^\r\n]+$')
    $aliasLines = [regex]::Matches($log, '(?m)^.*MOB_RADIUS_ALIAS\|type=[^\r\n]+$')
    $lifecycleSummary = [regex]::Match($log,
        '(?m)^.*MOB_RADIUS_LIFECYCLE_SUMMARY\|comparisons=216\|aliases=5\|persistentMarker=true\|restored=true\|passed=true\s*$')
    if ($lifecycleLines.Count -ne 216 -or $aliasLines.Count -ne 5 -or -not $lifecycleSummary.Success -or
            @($lifecycleLines | Where-Object { $_.Value -notmatch '\|matched=true$' }).Count -ne 0 -or
            @($aliasLines | Where-Object { $_.Value -notmatch '\|nativeKnown=true$' }).Count -ne 0) {
        throw 'Incomplete radius alias/lifecycle evidence.'
    }
    $probes += [ordered]@{
        id = 'mobRadius.alias-active-lifecycle'
        status = 'passed'
        observations = @($lifecycleLines | ForEach-Object { $_.Value }) +
            @($aliasLines | ForEach-Object { $_.Value }) + @($lifecycleSummary.Value)
    }
}
$fixtureSource = Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/RandomSpawnPaperFixture.java'
$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = $fixtureId
    evidenceMode = if ($MobRadiusAudit -and -not $MobRadiusStrict) { 'exploratory' } else { 'release' }
    paperVersion = $paperMatch.Groups[1].Value
    paperApiVersion = $paperMatch.Groups[2].Value
    pluginEnabled = $true
    externalPlugin = 'MythicMobs 5.7.2'
    externalPluginSha256 = $mythicSha256
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    fixtureProbeSourceSha256 = (Get-FileHash -LiteralPath $fixtureSource -Algorithm SHA256).Hash.ToLowerInvariant()
    fixtureProbeJarSha256 = (Get-FileHash -LiteralPath $probeJar -Algorithm SHA256).Hash.ToLowerInvariant()
    conditionProbeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/MobRadiusAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    dispatchProbeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/MobRadiusDispatchAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    variableExpiryProbeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/VariableExpiryAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    variablePlaceholderProbeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    triggerPlaceholderProbeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/TriggerPlaceholderAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    comparisonProbeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'src/main/java/dev/helstera/test/randomspawn/ComparisonDispatchAudit.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    naturalSpawnClientSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'natural-spawn-client.js') -Algorithm SHA256).Hash.ToLowerInvariant()
    randomSpawnConfigSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'RandomSpawns/replace.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    mythicMobCatalogSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'Mobs/fixture.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    nativeMobConfigSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'native-mobs/radius-audit.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    replacementMobConfigSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'mobs/mythic_random_spawn_probe.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    conditionGuardMobConfigSha256 = (Get-FileHash -LiteralPath (Join-Path $fixtureDir 'mobs/mythic_random_spawn_guard.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    pluginSha256 = (Get-FileHash -LiteralPath (Join-Path $plugins 'helsteraMobs.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    paperJarSha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    log = 'logs/latest.log'
    expectedProbeIds = @($probes | ForEach-Object { $_.id })
    probes = $probes
}
$json = $report | ConvertTo-Json -Depth 8
$reportInFixture = Join-Path $server 'integration-report.json'
[System.IO.File]::WriteAllText($reportInFixture, $json + "`n", [System.Text.UTF8Encoding]::new($false))
$python = & (Join-Path $PSScriptRoot 'resolve-python.ps1')
& $python.Source @($python.Prefix) (Join-Path $PSScriptRoot 'validate-report.py') `
    (Join-Path $PSScriptRoot 'integration-report.schema.json') $reportInFixture
if ($LASTEXITCODE -ne 0) { throw 'RandomSpawn Paper report does not match the integration schema.' }

$stream = [System.IO.File]::Open($logArchivePath, [System.IO.FileMode]::CreateNew)
try {
    $gzip = [System.IO.Compression.GzipStream]::new($stream, [System.IO.Compression.CompressionLevel]::Optimal)
    try {
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($safeLog)
        $gzip.Write($bytes, 0, $bytes.Length)
    } finally { $gzip.Dispose() }
} finally { $stream.Dispose() }
[System.IO.File]::WriteAllText($reportPath, $json + "`n", [System.Text.UTF8Encoding]::new($false))
if ($MobRadiusAudit -and -not $MobRadiusStrict) { Write-Output "Exploratory MobsInRadius audit completed: $fixtureId" }
else { Write-Output "RandomSpawn Paper replacement fixture passed: $fixtureId" }
Write-Output "Report: $reportPath"
Write-Output "Compressed log: $logArchivePath"
