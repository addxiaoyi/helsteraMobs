param(
    [string]$FixtureName = '',
    [switch]$Exploratory,
    [switch]$CorruptPrimary,
    [switch]$MissingPrimary,
    [switch]$CorruptBoth,
    [switch]$RepairCorrupt,
    [switch]$NaturalRepair,
    [string]$PaperJarPath = ''
)

$ErrorActionPreference = 'Stop'
if (@($CorruptPrimary, $MissingPrimary, $CorruptBoth, $RepairCorrupt, $NaturalRepair).Where({ $_ }).Count -gt 1) { throw 'Choose only one primary-state failure mode.' }
$fixture = if ([string]::IsNullOrWhiteSpace($FixtureName)) {
    'srv-paper-spawner-restart-' + [guid]::NewGuid().ToString('N')
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

    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = 'write'
    $runParams = @{ RunSeconds = 25 }
    if ($Exploratory) { $runParams.Exploratory = $true }
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams
    $firstLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $firstLog -Pattern 'spawner-persistence stage=write .*generated=true result=true' -Quiet)) {
        throw 'First boot did not persist a generated Spawner counter'
    }
    $state = Join-Path $fixturePath 'plugins/helsteraMobs/spawner-state.yml'
    if (-not (Test-Path -LiteralPath $state -PathType Leaf)) {
        throw 'Spawner state file is missing after first shutdown'
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
    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = 'verify'
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams
    $secondLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not $CorruptBoth -and -not $RepairCorrupt -and -not $NaturalRepair -and -not (Select-String -LiteralPath $secondLog -Pattern 'spawner-persistence stage=verify .*before=1 after=1 restored=true result=true' -Quiet)) {
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
