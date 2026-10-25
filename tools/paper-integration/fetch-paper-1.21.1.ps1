param(
    [string]$OutputPath = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path 'srv-1.21.1/paper.jar'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$downloadUrl = 'https://web.archive.org/web/20250604180245id_/https://api.papermc.io/v2/projects/paper/versions/1.21.1/builds/133/downloads/paper-1.21.1-133.jar'
$expectedSha256 = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
$output = [System.IO.Path]::GetFullPath($OutputPath)

if ((Test-Path -LiteralPath $output -PathType Leaf) -and -not $Force) {
    throw "Output already exists: $output (pass -Force to replace it)"
}

$directory = Split-Path -Parent $output
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$temporary = Join-Path $directory ('.paper-1.21.1-133-' + [IO.Path]::GetRandomFileName() + '.download')

try {
    Write-Host "Downloading Paper 1.21.1-133 from the archived official download..."
    Invoke-WebRequest -Uri $downloadUrl -OutFile $temporary -TimeoutSec 300
    $actualSha256 = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Paper SHA-256 mismatch: $actualSha256 (expected $expectedSha256)"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($temporary)
    try {
        $versionEntry = $archive.GetEntry('version.json')
        if ($null -eq $versionEntry) { throw 'Paper jar has no version.json' }
        $reader = [System.IO.StreamReader]::new($versionEntry.Open())
        try {
            $version = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Dispose()
        }
        if ([string]$version.id -ne '1.21.1') {
            throw "Paper version.json mismatch: $($version.id)"
        }
    } finally {
        $archive.Dispose()
    }

    Move-Item -LiteralPath $temporary -Destination $output -Force
    Write-Host "Paper 1.21.1-133 verified: $output"
    Write-Host "SHA-256: $actualSha256"
} finally {
    if (Test-Path -LiteralPath $temporary -PathType Leaf) {
        Remove-Item -LiteralPath $temporary -Force
    }
}
