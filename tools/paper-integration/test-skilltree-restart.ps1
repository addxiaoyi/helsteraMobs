param(
    [string]$FixtureName = '',
    [switch]$Exploratory,
    [string]$PaperJarPath = ''
)

$ErrorActionPreference = 'Stop'
$fixture = if ([string]::IsNullOrWhiteSpace($FixtureName)) {
    'srv-paper-skilltree-restart-' + [guid]::NewGuid().ToString('N')
} else {
    $FixtureName
}
$previousFixture = $env:PAPER_INTEGRATION_DIR
$previousReuse = $env:PAPER_SKILLTREE_REUSE
$previousStage = $env:PAPER_SKILLTREE_STAGE
$previousSkillTreeOnly = $env:PAPER_SKILLTREE_ONLY
$previousMenu = $env:PAPER_MENU_WORKFLOW
$previousPaperJar = $env:PAPER_JAR_PATH
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $repo $fixture))
$repoPath = [System.IO.Path]::GetFullPath($repo + [System.IO.Path]::DirectorySeparatorChar)
if (-not $fixturePath.StartsWith($repoPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Fixture must be inside workspace: $fixturePath"
}

try {
    $env:PAPER_INTEGRATION_DIR = $fixture
    if (-not [string]::IsNullOrWhiteSpace($PaperJarPath)) {
        $env:PAPER_JAR_PATH = [System.IO.Path]::GetFullPath($PaperJarPath)
    }
    $env:PAPER_SKILLTREE_REUSE = 'false'
    $env:PAPER_SKILLTREE_STAGE = 'write'
    $env:PAPER_SKILLTREE_ONLY = 'true'
    $env:PAPER_MENU_WORKFLOW = 'false'
    New-Item -ItemType Directory -Path $fixturePath -Force | Out-Null
    $serverProperties = Join-Path $fixturePath 'server.properties'
    if (-not (Test-Path -LiteralPath $serverProperties -PathType Leaf)) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination $serverProperties
    }
    $properties = Get-Content -LiteralPath $serverProperties -Raw
    $properties = [regex]::Replace($properties, '(?m)^difficulty=.*$', 'difficulty=normal')
    $properties = [regex]::Replace($properties, '(?m)^spawn-monsters=.*$', 'spawn-monsters=false')
    Set-Content -LiteralPath $serverProperties -Value $properties -Encoding ascii
    $runParams = @{ RunSeconds = 45 }
    if ($Exploratory) { $runParams.Exploratory = $true }
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams

    $writeLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $writeLog -Pattern 'skilltree-restart stage=write result=passed mob-id=skilltree_probe entities=2 .*foundation-unlocked=true' -Quiet)) {
        throw 'First boot did not persist the SkillTree probe state'
    }
    $snapshotDirectory = Join-Path $fixturePath 'plugins/helsteraMobs/skill-trees'
    $snapshots = @(Get-ChildItem -LiteralPath $snapshotDirectory -Filter 'entity-*.yml' -File)
    if ($snapshots.Count -ne 2) {
        throw "Expected two UUID SkillTree snapshots after first shutdown; found $($snapshots.Count)"
    }
    if (Test-Path -LiteralPath (Join-Path $snapshotDirectory 'mob-skilltree_probe.yml')) {
        throw 'New SkillTree snapshots must use entity UUIDs, not the legacy mob ID filename'
    }

    $env:PAPER_SKILLTREE_REUSE = 'true'
    $env:PAPER_SKILLTREE_STAGE = 'verify'
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams

    $verifyLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=verify result=passed mob-id=skilltree_probe entities=2 uuid-markers=true foundation-restored=true prerequisite-restored=true advanced-unlocked=true' -Quiet)) {
        throw 'Second boot did not independently restore both UUID SkillTree snapshots'
    }
    if (-not (Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=death result=passed mob-id=skilltree_probe .*entity-dead=true removed-snapshot=true survivor-snapshot=true' -Quiet)) {
        throw 'Confirmed death did not delete only the dead entity SkillTree snapshot'
    }
    $deathLine = Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=death result=passed mob-id=skilltree_probe .*removed-entity=([0-9a-f-]{36})' | Select-Object -First 1
    $removedEntity = [regex]::Match($deathLine.Line, 'removed-entity=([0-9a-f-]{36})').Groups[1].Value
    $removedSnapshot = Join-Path $snapshotDirectory "entity-$removedEntity.yml"
    if (Test-Path -LiteralPath $removedSnapshot) {
        throw 'Dead entity SkillTree snapshot was recreated during shutdown'
    }
    $remainingSnapshots = @(Get-ChildItem -LiteralPath $snapshotDirectory -Filter 'entity-*.yml' -File)
    if ($remainingSnapshots.Count -ne 1) {
        throw "Expected only the surviving entity SkillTree snapshot after second shutdown; found $($remainingSnapshots.Count)"
    }
    Write-Host "SkillTree restart integration passed: $fixturePath"
} finally {
    $env:PAPER_INTEGRATION_DIR = $previousFixture
    $env:PAPER_SKILLTREE_REUSE = $previousReuse
    $env:PAPER_SKILLTREE_STAGE = $previousStage
    $env:PAPER_SKILLTREE_ONLY = $previousSkillTreeOnly
    $env:PAPER_MENU_WORKFLOW = $previousMenu
    $env:PAPER_JAR_PATH = $previousPaperJar
}
