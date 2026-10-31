param(
    [string]$FixtureName = '',
    [switch]$Exploratory,
    [string]$PaperJarPath = ''
)

$ErrorActionPreference = 'Stop'
$fixture = if ([string]::IsNullOrWhiteSpace($FixtureName)) {
    'srv-paper-spawner-restart-' + [guid]::NewGuid().ToString('N')
} else { $FixtureName }
$previousFixture = $env:PAPER_INTEGRATION_DIR
$previousReuse = $env:PAPER_SKILLTREE_REUSE
$previousStage = $env:PAPER_SPAWNER_PERSISTENCE_STAGE
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
    $env:PAPER_SKILLTREE_REUSE = 'false'
    $env:PAPER_MENU_WORKFLOW = 'false'
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

    $env:PAPER_SKILLTREE_REUSE = 'true'
    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = 'verify'
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams
    $secondLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $secondLog -Pattern 'spawner-persistence stage=verify .*before=1 after=1 restored=true result=true' -Quiet)) {
        throw 'Second boot did not restore the Spawner counter exactly once'
    }
    Write-Host "Spawner restart integration passed: $fixturePath"
} finally {
    $env:PAPER_INTEGRATION_DIR = $previousFixture
    $env:PAPER_SKILLTREE_REUSE = $previousReuse
    $env:PAPER_SPAWNER_PERSISTENCE_STAGE = $previousStage
    $env:PAPER_MENU_WORKFLOW = $previousMenu
    $env:PAPER_JAR_PATH = $previousPaperJar
}
