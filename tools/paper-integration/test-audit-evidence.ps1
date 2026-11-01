$ErrorActionPreference = 'Stop'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("helstera-evidence-audit-{0}" -f [guid]::NewGuid().ToString('N'))
$documents = @(
    'README.md',
    'MYTHICMOBS_PARITY_MATRIX.md',
    'tools/paper-integration/README.md',
    'tools/paper-integration/STARTUP_EVIDENCE.md'
)

try {
    foreach ($document in $documents) {
        $path = Join-Path $temporaryRoot $document
        [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($path))
        [System.IO.File]::WriteAllText($path, "")
    }

    $evidencePath = Join-Path $temporaryRoot 'tools/paper-integration/STARTUP_EVIDENCE.md'
    [System.IO.File]::WriteAllText($evidencePath, "Paper jar: ``srv-1.21.1/paper.jar``.`nMissing: ``srv-paper-old``.`n")
    [void][System.IO.Directory]::CreateDirectory((Join-Path $temporaryRoot 'srv-1.21.1'))

    $auditScript = Join-Path $PSScriptRoot 'audit-evidence.ps1'
    $output = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Default audit failed unexpectedly: $output"
    }
    if ($output -notmatch 'Present: 1' -or $output -notmatch 'Missing: 1') {
        throw "Versioned fixture was not recognized as one present reference: $output"
    }
    if ($output -notmatch 'srv-paper-old \(tools/paper-integration/STARTUP_EVIDENCE.md:2\)') {
        throw "Missing fixture source location was not reported: $output"
    }
    if ($output -match 'srv-1(?:\s|$)') {
        throw "Versioned fixture was truncated to srv-1: $output"
    }

    $requiredOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0) {
        throw "-RequirePresent did not fail for the missing fixture: $requiredOutput"
    }
    Write-Output 'Evidence audit contract passed.'
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
