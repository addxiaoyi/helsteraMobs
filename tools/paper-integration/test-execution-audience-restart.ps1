param(
    [string]$FixtureName = '',
    [switch]$Exploratory,
    [string]$PaperJarPath = ''
)

$ErrorActionPreference = 'Stop'
$fixture = if ([string]::IsNullOrWhiteSpace($FixtureName)) {
    'srv-paper-execution-audience-restart-' + [guid]::NewGuid().ToString('N')
} else { $FixtureName }
$previousFixture = $env:PAPER_INTEGRATION_DIR
$previousReuse = $env:PAPER_SKILLTREE_REUSE
$previousAudienceStage = $env:PAPER_EXECUTION_AUDIENCE_STAGE
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
    $env:PAPER_EXECUTION_AUDIENCE_STAGE = 'write'
    $env:PAPER_MENU_WORKFLOW = 'false'
    if (-not [string]::IsNullOrWhiteSpace($PaperJarPath)) {
        $env:PAPER_JAR_PATH = [System.IO.Path]::GetFullPath($PaperJarPath)
    }
    New-Item -ItemType Directory -Path $fixturePath -Force | Out-Null
    $runParams = @{ RunSeconds = 12 }
    if ($Exploratory) { $runParams.Exploratory = $true }
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams

    $writeLog = Join-Path $fixturePath 'logs/latest.log'
    $writeLine = Get-Content -LiteralPath $writeLog -Encoding utf8 |
        Where-Object { $_ -match 'execution-audience-restart stage=write ' } | Select-Object -Last 1
    if ($writeLine -notmatch 'execution-audience-restart stage=write result=passed mob-id=execution_audience_restart_probe entity=(?<entity>[0-9a-f-]{36}) audience=00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000002 pdc-written=true persistent=true') {
        throw 'First boot did not persist the expected execution audience on a persistent managed mob'
    }
    $entityId = $Matches.entity
    Copy-Item -LiteralPath $writeLog -Destination (Join-Path $fixturePath 'execution-audience-first-boot.log') -Force

    $env:PAPER_SKILLTREE_REUSE = 'true'
    $env:PAPER_EXECUTION_AUDIENCE_STAGE = 'verify'
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams

    $verifyLog = Join-Path $fixturePath 'logs/latest.log'
    $verifyLine = Get-Content -LiteralPath $verifyLog -Encoding utf8 |
        Where-Object { $_ -match 'execution-audience-restart stage=verify ' } | Select-Object -Last 1
    if ($verifyLine -notmatch 'execution-audience-restart stage=verify result=passed mob-id=execution_audience_restart_probe entity=(?<restoredEntity>[0-9a-f-]{36}) audience=00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000002 audience-restored=true pdc-restored=true persistent=true') {
        throw 'Second boot did not restore the persistent mob execution audience'
    }
    if ($Matches.restoredEntity -ne $entityId) {
        throw "Second boot restored entity $($Matches.restoredEntity), expected original entity $entityId"
    }
    Copy-Item -LiteralPath $verifyLog -Destination (Join-Path $fixturePath 'execution-audience-second-boot.log') -Force
    Write-Host "Execution audience restart integration passed: fixture=$fixturePath entity=$entityId"
} finally {
    $env:PAPER_INTEGRATION_DIR = $previousFixture
    $env:PAPER_SKILLTREE_REUSE = $previousReuse
    $env:PAPER_EXECUTION_AUDIENCE_STAGE = $previousAudienceStage
    $env:PAPER_MENU_WORKFLOW = $previousMenu
    $env:PAPER_JAR_PATH = $previousPaperJar
}
