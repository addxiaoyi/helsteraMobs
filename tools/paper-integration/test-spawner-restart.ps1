param(
    [string]$FixtureName = '',
    [switch]$Exploratory,
    [switch]$CorruptPrimary,
    [switch]$MissingPrimary,
    [switch]$CorruptBoth,
    [switch]$RepairCorrupt,
    [switch]$NaturalRepair,
    [switch]$NaturalScheduling,
    [string]$PaperJarPath = ''
)

$ErrorActionPreference = 'Stop'
if (@($CorruptPrimary, $MissingPrimary, $CorruptBoth, $RepairCorrupt, $NaturalRepair, $NaturalScheduling).Where({ $_ }).Count -gt 1) { throw 'Choose only one Spawner restart mode.' }
$fixturePrefix = if ($NaturalScheduling) { 'srv-paper-spawner-natural-restart-' } else { 'srv-paper-spawner-restart-' }
$fixture = if ([string]::IsNullOrWhiteSpace($FixtureName)) {
    $fixturePrefix + [guid]::NewGuid().ToString('N')
} else { $FixtureName }
$previousFixture = $env:PAPER_INTEGRATION_DIR
$previousReuse = $env:PAPER_SKILLTREE_REUSE
$previousStage = $env:PAPER_SPAWNER_PERSISTENCE_STAGE
$previousMenu = $env:PAPER_MENU_WORKFLOW
$previousPaperJar = $env:PAPER_JAR_PATH
$previousGuardProbe = $env:PAPER_SPAWNER_STATE_GUARD_PROBE
$previousRepairProbe = $env:PAPER_SPAWNER_STATE_REPAIR_PROBE
$previousNaturalProbe = $env:PAPER_SPAWNER_NATURAL_REPAIR_PROBE
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $repo $fixture))
$repoPath = [System.IO.Path]::GetFullPath($repo + [System.IO.Path]::DirectorySeparatorChar)
if (-not $fixturePath.StartsWith($repoPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Fixture must be inside workspace: $fixturePath"
}
if ($NaturalScheduling) {
    $artifactStem = 'paper-1.21.1-' + [System.IO.Path]::GetFileName(($fixture -replace '^srv-paper-', ''))
    $reportPath = Join-Path $PSScriptRoot "reports/$artifactStem.json"
    $firstArchivePath = Join-Path $PSScriptRoot "reports/$artifactStem-first-boot.log.gz"
    $secondArchivePath = Join-Path $PSScriptRoot "reports/$artifactStem-second-boot.log.gz"
    if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $firstArchivePath) -or
            (Test-Path -LiteralPath $secondArchivePath)) {
        throw "Spawner natural restart evidence already exists for fixture $fixture"
    }
}

try {
    $env:PAPER_INTEGRATION_DIR = $fixture
    $env:PAPER_SKILLTREE_REUSE = 'false'
    $env:PAPER_MENU_WORKFLOW = 'false'
    $env:PAPER_SPAWNER_STATE_GUARD_PROBE = 'false'
    $env:PAPER_SPAWNER_STATE_REPAIR_PROBE = 'false'
    $env:PAPER_SPAWNER_NATURAL_REPAIR_PROBE = 'false'
    if (-not [string]::IsNullOrWhiteSpace($PaperJarPath)) {
        $env:PAPER_JAR_PATH = [System.IO.Path]::GetFullPath($PaperJarPath)
    }
    New-Item -ItemType Directory -Path $fixturePath -Force | Out-Null

    $firstStage = if ($NaturalScheduling) { 'natural-write' } else { 'write' }
    $secondStage = if ($NaturalScheduling) { 'natural-verify' } else { 'verify' }
    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = $firstStage
    $firstRunSeconds = if ($NaturalScheduling) { 0 } else { 25 }
    $runParams = @{ RunSeconds = $firstRunSeconds }
    if ($Exploratory) { $runParams.Exploratory = $true }
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams
    $firstLog = Join-Path $fixturePath 'logs/latest.log'
    if ($NaturalScheduling) {
        $writeLine = Get-Content -LiteralPath $firstLog -Encoding utf8 |
            Where-Object { $_ -match 'spawner-persistence stage=natural-write ' } | Select-Object -Last 1
        if ($writeLine -notmatch 'spawner-persistence stage=natural-write before=0 after=1 source=live-scheduler remaining=(?<cooldown>\d+) result=true') {
            throw 'First boot did not observe a natural Spawner generation through the live scheduler'
        }
        $naturalCooldownAtSpawn = [int]$Matches.cooldown
    } elseif (-not (Select-String -LiteralPath $firstLog -Pattern 'spawner-persistence stage=write .*generated=true result=true' -Quiet)) {
        throw 'First boot did not persist a generated Spawner counter'
    }
    $state = Join-Path $fixturePath 'plugins/helsteraMobs/spawner-state.yml'
    if (-not (Test-Path -LiteralPath $state -PathType Leaf)) {
        throw 'Spawner state file is missing after first shutdown'
    }
    if ($NaturalScheduling) {
        $stateText = [System.IO.File]::ReadAllText($state)
        if ($stateText -notmatch '(?m)^  persistence_probe:\s*\r?\n    total-spawned: 1\s*\r?\n    next-spawn-in: (?<remaining>\d+)\s*$') {
            throw 'First shutdown did not persist the natural spawn counter and cooldown'
        }
        $persistedCooldown = [int]$Matches.remaining
        if ($stateText -notmatch '(?m)^  chance_denied:\s*\r?\n    total-spawned: 0\s*\r?\n    next-spawn-in: 0\s*$') {
            throw 'Chance: 0 spawner generated or did not persist its zero count on first boot'
        }
        if ($stateText -notmatch '(?m)^  chance_invalid:\s*\r?\n    total-spawned: 0\s*\r?\n    next-spawn-in: 0\s*$') {
            throw 'Invalid Chance spawner generated or did not persist its zero count on first boot'
        }
        if ($persistedCooldown -le 0 -or $persistedCooldown -gt 600 -or $naturalCooldownAtSpawn -le 0) {
            throw "First shutdown saved an invalid cooldown: spawn=$naturalCooldownAtSpawn saved=$persistedCooldown"
        }
    }
    Copy-Item -LiteralPath $firstLog -Destination (Join-Path $fixturePath 'spawner-first-boot.log') -Force
    if ($CorruptPrimary -or $MissingPrimary) {
        Copy-Item -LiteralPath $state -Destination ($state + '.bak') -Force
        $backupHash = (Get-FileHash -LiteralPath ($state + '.bak') -Algorithm SHA256).Hash
        if ($CorruptPrimary) {
            [System.IO.File]::WriteAllText($state, 'spawners: [broken')
        } else {
            Remove-Item -LiteralPath $state
        }
    }
    if ($CorruptBoth -or $RepairCorrupt -or $NaturalRepair) {
        if ($RepairCorrupt -or $NaturalRepair) { Copy-Item -LiteralPath $state -Destination (Join-Path $fixturePath 'valid-spawner-state.yml') -Force }
        [System.IO.File]::WriteAllText($state, 'spawners: [broken-primary')
        [System.IO.File]::WriteAllText(($state + '.bak'), 'spawners: [broken-backup')
        $primaryHash = (Get-FileHash -LiteralPath $state -Algorithm SHA256).Hash
        $backupHash = (Get-FileHash -LiteralPath ($state + '.bak') -Algorithm SHA256).Hash
        if (-not $NaturalRepair) { $env:PAPER_SPAWNER_STATE_GUARD_PROBE = 'true' }
        if ($RepairCorrupt) { $env:PAPER_SPAWNER_STATE_REPAIR_PROBE = 'true' }
        if ($NaturalRepair) { $env:PAPER_SPAWNER_NATURAL_REPAIR_PROBE = 'true' }
    }

    $env:PAPER_SKILLTREE_REUSE = 'true'
    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = $secondStage
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams
    $secondLog = Join-Path $fixturePath 'logs/latest.log'
    if ($NaturalScheduling) {
        $verifyLine = Get-Content -LiteralPath $secondLog -Encoding utf8 |
            Where-Object { $_ -match 'spawner-persistence stage=natural-verify ' } | Select-Object -Last 1
        if ($verifyLine -notmatch 'spawner-persistence stage=natural-verify before=1 after=2 source=live-scheduler remaining-at-start=(?<start>\d+) elapsed-ticks=(?<elapsed>\d+) remaining-at-spawn=(?<next>\d+) result=true') {
            throw 'Second boot did not naturally spawn after restoring the saved cooldown'
        }
        $restoredCooldown = [int]$Matches.start
        $elapsedTicks = [int]$Matches.elapsed
        $cooldownAfterSpawn = [int]$Matches.next
        if ($restoredCooldown -ne $persistedCooldown -or
                $elapsedTicks + 1 -lt $restoredCooldown -or
                $elapsedTicks -gt $restoredCooldown + 20 -or
                $cooldownAfterSpawn -le 0) {
            throw "Restored cooldown timing is inconsistent: saved=$persistedCooldown restored=$restoredCooldown elapsed=$elapsedTicks next=$cooldownAfterSpawn"
        }
        $secondStateText = [System.IO.File]::ReadAllText($state)
        if ($secondStateText -notmatch '(?m)^  persistence_probe:\s*\r?\n    total-spawned: 2\s*$') {
            throw 'Second shutdown did not persist the naturally spawned lifetime count'
        }
        if ($secondStateText -notmatch '(?m)^  chance_denied:\s*\r?\n    total-spawned: 0\s*\r?\n    next-spawn-in: 0\s*$') {
            throw 'Chance: 0 spawner generated or did not persist its zero count on second boot'
        }
        if ($secondStateText -notmatch '(?m)^  chance_invalid:\s*\r?\n    total-spawned: 0\s*\r?\n    next-spawn-in: 0\s*$') {
            throw 'Invalid Chance spawner generated or did not persist its zero count on second boot'
        }
        $forcedOnFirstBoot = Select-String -LiteralPath $firstLog `
            -Pattern 'helstera spawner force persistence_probe' -Quiet -ErrorAction SilentlyContinue
        $forcedOnSecondBoot = Select-String -LiteralPath $secondLog `
            -Pattern 'helstera spawner force persistence_probe' -Quiet -ErrorAction SilentlyContinue
        if ($forcedOnFirstBoot -or $forcedOnSecondBoot) {
            throw 'Natural restart fixture issued a manual force command'
        }
        Copy-Item -LiteralPath $secondLog -Destination (Join-Path $fixturePath 'spawner-second-boot.log') -Force

        function Compress-Log([string]$SourcePath, [string]$DestinationPath) {
            $sourceStream = [System.IO.File]::OpenRead($SourcePath)
            $destinationStream = [System.IO.File]::Create($DestinationPath)
            $gzipStream = [System.IO.Compression.GZipStream]::new(
                $destinationStream, [System.IO.Compression.CompressionMode]::Compress)
            try {
                $sourceStream.CopyTo($gzipStream)
            } finally {
                $gzipStream.Dispose()
                $sourceStream.Dispose()
            }
        }

        Compress-Log (Join-Path $fixturePath 'spawner-first-boot.log') $firstArchivePath
        Compress-Log (Join-Path $fixturePath 'spawner-second-boot.log') $secondArchivePath
        $serverReport = Get-Content -LiteralPath (Join-Path $fixturePath 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        $firstArchiveHash = (Get-FileHash -LiteralPath $firstArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        $secondArchiveHash = (Get-FileHash -LiteralPath $secondArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        $report = [ordered]@{
            schemaVersion = 1
            fixtureId = $fixture
            testType = 'two-boot-natural-spawner-cooldown-and-chance'
            paperVersion = $serverReport.paperVersion
            result = 'passed'
            firstBoot = [ordered]@{
                result = 'passed'
                source = 'live-scheduler'
                totalSpawnedAtShutdown = 1
                chanceOneSpawnObserved = $true
                chanceZeroSpawned = 0
                invalidChanceSpawned = 0
                invalidChanceFailClosed = $true
                cooldownAtSpawnTicks = $naturalCooldownAtSpawn
                persistedCooldownTicks = $persistedCooldown
                log = [System.IO.Path]::GetFileName($firstArchivePath)
                logSha256 = $firstArchiveHash
            }
            secondBoot = [ordered]@{
                result = 'passed'
                restoredCooldownTicks = $restoredCooldown
                elapsedTicksUntilNaturalSpawn = $elapsedTicks
                totalSpawnedAtShutdown = 2
                chanceOneSpawnsObserved = 2
                chanceZeroSpawned = 0
                invalidChanceSpawned = 0
                invalidChanceFailClosed = $true
                cooldownAfterNaturalSpawnTicks = $cooldownAfterSpawn
                source = 'live-scheduler'
                manualForceCommand = $false
                log = [System.IO.Path]::GetFileName($secondArchivePath)
                logSha256 = $secondArchiveHash
            }
            pluginSha256 = $serverReport.pluginSha256
        }
        $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
        $reportHash = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Host "Spawner natural restart passed: savedCooldown=$persistedCooldown restoredCooldown=$restoredCooldown elapsedTicks=$elapsedTicks"
        Write-Host "Evidence report: $reportPath sha256=$reportHash"
        Write-Host "First-boot log sha256=$firstArchiveHash"
        Write-Host "Second-boot log sha256=$secondArchiveHash"
    } elseif (-not $CorruptBoth -and -not $RepairCorrupt -and -not $NaturalRepair -and -not (Select-String -LiteralPath $secondLog -Pattern 'spawner-persistence stage=verify .*before=1 after=1 restored=true result=true' -Quiet)) {
        throw 'Second boot did not restore the Spawner counter exactly once'
    }
    if ($NaturalRepair -and [System.IO.File]::ReadAllText($state) -notmatch '(?m)^  guard_natural_probe:\s*\r?\n    total-spawned: 1\s*$') {
        throw 'Natural spawn after repair was not saved'
    }
    if ($RepairCorrupt) {
        if (-not (Select-String -LiteralPath $secondLog -Pattern 'spawner-persistence stage=verify .*before=1 after=1 restored=true result=true' -Quiet) -or
                -not (Select-String -LiteralPath $secondLog -Pattern '强制生成 1 只（persistence_probe）' -Quiet) -or
                [System.IO.File]::ReadAllText($state) -notmatch '(?m)^  persistence_probe:\s*\r?\n    total-spawned: 2\s*$') {
            throw 'Second boot did not restore the counter and persist a resumed forced spawn'
        }
    }
    if ($CorruptBoth) {
        if (-not (Select-String -LiteralPath $secondLog -Pattern 'spawning paused until a valid state is loaded' -Quiet) -or
                -not (Select-String -LiteralPath $secondLog -Pattern '强制生成 0 只（persistence_probe）' -Quiet) -or
                -not (Select-String -LiteralPath $secondLog -Pattern 'state save skipped after state load failure' -Quiet)) {
            throw 'Second boot did not report the expected paused, rejected, and unsaved state'
        }
        if ((Get-FileHash -LiteralPath $state -Algorithm SHA256).Hash -ne $primaryHash -or
                (Get-FileHash -LiteralPath ($state + '.bak') -Algorithm SHA256).Hash -ne $backupHash) {
            throw 'Shutdown changed an unrecoverable state file'
        }
        Write-Host 'Spawner unrecoverable-state guard passed: force=0 files-preserved=true'
    }
    if ($CorruptPrimary -and -not (Select-String -LiteralPath $secondLog -Pattern 'state load failed; trying backup' -Quiet)) {
        throw 'Second boot did not report fallback to the Spawner state backup'
    }
    if ($MissingPrimary -and -not (Select-String -LiteralPath $secondLog -Pattern 'state file missing; trying backup' -Quiet)) {
        throw 'Second boot did not report fallback from missing primary state'
    }
    if ($CorruptPrimary -or $MissingPrimary) {
        $savedBackupHash = (Get-FileHash -LiteralPath ($state + '.bak') -Algorithm SHA256).Hash
        if ($savedBackupHash -ne $backupHash) {
            throw 'Shutdown after backup recovery replaced the valid backup'
        }
        $savedState = [System.IO.File]::ReadAllText($state)
        if ($savedState -notmatch '(?m)^  persistence_probe:\s*\r?\n    total-spawned: 1\s*$') {
            throw 'Shutdown after backup recovery did not write the restored counter to the primary state'
        }
        Write-Host "Spawner recovery save passed: backup-sha256=$($backupHash.ToLowerInvariant()) primary-counter=1"
    }
    Write-Host "Spawner restart integration passed: $fixturePath"
} finally {
    $env:PAPER_INTEGRATION_DIR = $previousFixture
    $env:PAPER_SKILLTREE_REUSE = $previousReuse
    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = $previousStage
    $env:PAPER_MENU_WORKFLOW = $previousMenu
    $env:PAPER_JAR_PATH = $previousPaperJar
    $env:PAPER_SPAWNER_STATE_GUARD_PROBE = $previousGuardProbe
    $env:PAPER_SPAWNER_STATE_REPAIR_PROBE = $previousRepairProbe
    $env:PAPER_SPAWNER_NATURAL_REPAIR_PROBE = $previousNaturalProbe
}
