$ErrorActionPreference = 'Stop'

function Add-Candidate {
    param(
        [System.Collections.Generic.List[object]]$Candidates,
        [System.Collections.Generic.HashSet[string]]$Seen,
        [string]$Source,
        [string[]]$Prefix
    )

    if ([string]::IsNullOrWhiteSpace($Source)) { return }
    $key = $Source + '|' + ($Prefix -join ' ')
    if (-not $Seen.Add($key)) { return }
    $Candidates.Add([pscustomobject]@{
        Source = $Source
        Prefix = @($Prefix)
    })
}

function Test-Candidate {
    param([pscustomobject]$Candidate)

    try {
        $null = & $Candidate.Source @($Candidate.Prefix) -c 'import encodings, jsonschema' 2>$null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

$candidates = [System.Collections.Generic.List[object]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

if (-not [string]::IsNullOrWhiteSpace($env:HELSTERA_PAPER_PYTHON)) {
    Add-Candidate $candidates $seen $env:HELSTERA_PAPER_PYTHON @()
}

if (-not [string]::IsNullOrWhiteSpace($env:VIRTUAL_ENV)) {
    Add-Candidate $candidates $seen (Join-Path $env:VIRTUAL_ENV 'Scripts/python.exe') @()
}

$tempRoots = [System.Collections.Generic.List[string]]::new()
$tempRoots.Add([System.IO.Path]::GetTempPath())
if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $tempRoots.Add((Join-Path $env:LOCALAPPDATA 'Temp'))
}
foreach ($tempRoot in $tempRoots) {
    if (-not [string]::IsNullOrWhiteSpace($tempRoot)) {
        Add-Candidate $candidates $seen (Join-Path $tempRoot 'helstera-paper-venv/Scripts/python.exe') @()
    }
}

$py = Get-Command py -ErrorAction SilentlyContinue
if ($null -ne $py) {
    foreach ($version in @('-3.12', '-3.11', '-3.10', '-3')) {
        Add-Candidate $candidates $seen $py.Source @($version)
    }
}

foreach ($name in @('python', 'python3')) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        Add-Candidate $candidates $seen $command.Source @()
    }
}

foreach ($candidate in $candidates) {
    if ((Test-Path -LiteralPath $candidate.Source -PathType Leaf -ErrorAction SilentlyContinue) -or
            $candidate.Source -match '(^|[\\/])py(?:\.exe)?$') {
        if (Test-Candidate $candidate) {
            $candidate
            return
        }
    }
}

throw 'No usable Python 3 interpreter can import both encodings and jsonschema.'
