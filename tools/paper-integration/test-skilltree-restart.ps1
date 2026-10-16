param(
    [string]$FixtureName = ''
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
$previousMenu = $env:PAPER_MENU_WORKFLOW
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $repo $fixture))
$repoPath = [System.IO.Path]::GetFullPath($repo + [System.IO.Path]::DirectorySeparatorChar)
if (-not $fixturePath.StartsWith($repoPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Fixture must be inside workspace: $fixturePath"
}

try {
    $env:PAPER_INTEGRATION_DIR = $fixture
    $env:PAPER_SKILLTREE_REUSE = 'false'
    $env:PAPER_SKILLTREE_STAGE = 'write'
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
    & (Join-Path $PSScriptRoot 'run-server.ps1') -RunSeconds 45

    $writeLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $writeLog -Pattern 'skilltree-restart stage=write result=passed mob-id=skilltree_probe' -Quiet)) {
        throw 'First boot did not persist the SkillTree probe state'
    }
    $snapshot = Join-Path $fixturePath 'plugins/helsteraMobs/skill-trees/mob-skilltree_probe.yml'
    if (-not (Test-Path -LiteralPath $snapshot -PathType Leaf)) {
        throw 'Stable mob SkillTree snapshot file is missing after first shutdown'
    }

    $env:PAPER_SKILLTREE_REUSE = 'true'
    $env:PAPER_SKILLTREE_STAGE = 'verify'
    & (Join-Path $PSScriptRoot 'run-server.ps1') -RunSeconds 45

    $verifyLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=verify result=passed mob-id=skilltree_probe .*marker=saved-on-first-boot' -Quiet)) {
        throw 'Second boot did not restore the persisted SkillTree marker'
    }
    Write-Host "SkillTree restart integration passed: $fixturePath"
} finally {
    $env:PAPER_INTEGRATION_DIR = $previousFixture
    $env:PAPER_SKILLTREE_REUSE = $previousReuse
    $env:PAPER_SKILLTREE_STAGE = $previousStage
    $env:PAPER_MENU_WORKFLOW = $previousMenu
}
