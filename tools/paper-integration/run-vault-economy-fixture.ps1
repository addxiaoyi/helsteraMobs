param(
    [ValidatePattern('^r[1-9]\d*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$ServerDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reportStem = "paper-1.21.1-vault-economy-contract-20261114-$EvidenceRevision"
$ServerDirectory = if ([string]::IsNullOrWhiteSpace($ServerDirectory)) {
    "srv-paper-vault-economy-contract-20261114-$EvidenceRevision"
} else { $ServerDirectory }
$server = if ([System.IO.Path]::IsPathRooted($ServerDirectory)) {
    [System.IO.Path]::GetFullPath($ServerDirectory)
} else { [System.IO.Path]::GetFullPath((Join-Path $repo $ServerDirectory)) }
$paper = if ([string]::IsNullOrWhiteSpace($env:PAPER_JAR_PATH)) {
    Join-Path $repo 'srv-paper-inline-condition-strict-20261112-r1/paper.jar'
} else { [System.IO.Path]::GetFullPath($env:PAPER_JAR_PATH) }
$plugin = Join-Path $repo 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$fixtureJar = Join-Path $PSScriptRoot 'vault-fixture/target/vault-economy-paper-fixture.jar'
$reportPath = Join-Path $PSScriptRoot "reports/$reportStem.json"
$compressedLogPath = Join-Path $PSScriptRoot "reports/$reportStem.log.gz"

if (-not (Test-Path -LiteralPath $paper -PathType Leaf)) { throw "Paper jar not found: $paper" }
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) { throw "Build the full plugin first: $plugin" }
if (-not (Test-Path -LiteralPath $fixtureJar -PathType Leaf)) { throw "Build the Vault fixture first: $fixtureJar" }
if (Test-Path -LiteralPath $server) { throw "Refusing to reuse or replace an existing Paper fixture directory: $server" }
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $compressedLogPath)) {
    throw 'Vault fixture evidence output already exists; choose a new report revision before rerunning.'
}

function Get-PaperImplementationVersion([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -ne $entry) {
            $reader = [System.IO.StreamReader]::new($entry.Open())
            try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $match = [regex]::Match($manifest, '(?m)^Implementation-Version:\s*(.+?)\s*$')
            if ($match.Success) { return $match.Groups[1].Value.Trim() }
        }
        $versionEntry = $archive.GetEntry('version.json')
        if ($null -ne $versionEntry) {
            $reader = [System.IO.StreamReader]::new($versionEntry.Open())
            try {
                $version = $reader.ReadToEnd() | ConvertFrom-Json
                if ($version.id) { return [string]$version.id }
            } finally { $reader.Dispose() }
        }
        return $null
    } finally { $archive.Dispose() }
}

$paperVersion = Get-PaperImplementationVersion $paper
if ($paperVersion -notmatch '^1\.21\.1-133(?:-|$)') {
    $paperSha = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($paperVersion -eq '1.21.1' -and
            $paperSha -ceq '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9') {
        $paperVersion = '1.21.1-133 (official Paperclip SHA-256 pinned)'
    } else {
        throw "This fixture requires release-validated Paper 1.21.1-133; found '$paperVersion' (SHA-256 $paperSha)."
    }
}

New-Item -ItemType Directory -Path $server -Force | Out-Null
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
New-Item -ItemType Directory -Path $plugins -Force | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'helsteraMobs.jar')
Copy-Item -LiteralPath $fixtureJar -Destination (Join-Path $plugins 'VaultContractFixture.jar')
$pluginData = Join-Path $plugins 'helsteraMobs'
New-Item -ItemType Directory -Path $pluginData -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.yml') -Destination (Join-Path $pluginData 'config.yml')
$configPath = Join-Path $pluginData 'config.yml'
$config = [System.IO.File]::ReadAllText($configPath)
$config = [regex]::Replace($config, '(?m)^(  self-test(?:-[A-Za-z0-9-]+)?):\s*(?:true|false)\s*$', '$1: false')
[System.IO.File]::WriteAllText($configPath, $config, [System.Text.UTF8Encoding]::new($false))

$logDirectory = Join-Path $server 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$logPath = Join-Path $logDirectory 'latest.log'
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$psi.Arguments = '-Xms1G -Xmx2G -jar paper.jar nogui'
$psi.WorkingDirectory = $server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
if (-not $process.Start()) { throw 'Failed to start the isolated Paper server.' }

try {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    $probeLine = $null
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        if (Test-Path -LiteralPath $logPath) {
            $probeLine = Get-Content -LiteralPath $logPath -Encoding utf8 |
                Where-Object { $_ -match 'VAULT_ECONOMY_PARITY_PROBE ' } | Select-Object -Last 1
            if ($probeLine) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $probeLine) {
        $tail = if (Test-Path -LiteralPath $logPath) {
            (Get-Content -LiteralPath $logPath -Tail 60 -Encoding utf8) -join "`n"
        } else { 'Paper did not create logs/latest.log.' }
        throw "Vault fixture did not report within three minutes. Recent log:`n$tail"
    }
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Paper did not stop after the Vault fixture completed.' }
} finally {
    if (-not $process.HasExited) {
        try {
            $process.StandardInput.WriteLine('stop')
            $process.StandardInput.Flush()
            if (-not $process.WaitForExit(15000)) { $process.Kill() }
        } catch { if (-not $process.HasExited) { $process.Kill() } }
    }
}
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)." }

$probeLine = Get-Content -LiteralPath $logPath -Encoding utf8 |
    Where-Object { $_ -match 'VAULT_ECONOMY_PARITY_PROBE ' } | Select-Object -Last 1
$payload = [regex]::Match($probeLine, 'VAULT_ECONOMY_PARITY_PROBE\s+(.+)$').Groups[1].Value
$observed = @{}
foreach ($token in ($payload -split '\s+')) {
    $pair = $token -split '=', 2
    if ($pair.Count -eq 2) { $observed[$pair[0]] = $pair[1] }
}
$requiredChecks = @(
    'vault_enabled', 'host_enabled', 'softdepend_order', 'adapter_connected', 'economy_capability',
    'rich_balance', 'poor_balance', 'provider_exception_empty', 'non_finite_empty',
    'null_player_empty', 'condition_rich_pass', 'condition_poor_reject', 'condition_below_reject'
)
if ($observed.result -cne 'passed' -or $observed.fixture -cne 'synthetic-contract' -or
        $observed.'vault-version' -cne '1.0-contract-fixture') {
    throw "Vault Economy contract fixture failed or used unexpected fixture metadata: $payload"
}
foreach ($check in $requiredChecks) {
    if ($observed[$check] -cne 'true') { throw "Vault fixture check '$check' failed: $payload" }
}

$rawLog = [System.IO.File]::ReadAllText($logPath)
$redactedLog = [regex]::Replace($rawLog, '(?i)((?:令牌|token)\s*[:=]\s*)[a-f0-9]{8,}', '$1[REDACTED]')
$compressedStream = [System.IO.File]::Create($compressedLogPath)
$gzipStream = [System.IO.Compression.GZipStream]::new($compressedStream, [System.IO.Compression.CompressionLevel]::Optimal)
$writer = [System.IO.StreamWriter]::new($gzipStream, [System.Text.UTF8Encoding]::new($false))
try { $writer.Write($redactedLog) } finally { $writer.Dispose(); $gzipStream.Dispose() }

$assertions = foreach ($check in $requiredChecks) {
    [ordered]@{ id = $check; status = 'passed'; observed = $observed[$check] }
}
$report = [ordered]@{
    schemaVersion = 1
    fixture = "paper-1.21.1-vault-economy-contract-20261114-$EvidenceRevision"
    evidenceBoundary = 'Synthetic Vault API/service contract only; this is not an installed Vault build or economy-provider compatibility claim.'
    generatedAtUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    paper = [ordered]@{
        implementationVersion = $paperVersion
        sha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    helsteraMobsSha256 = (Get-FileHash -LiteralPath $plugin -Algorithm SHA256).Hash.ToLowerInvariant()
    externalPlugins = @(
        [ordered]@{
            name = 'Vault contract fixture'
            version = $observed.'vault-version'
            sha256 = (Get-FileHash -LiteralPath $fixtureJar -Algorithm SHA256).Hash.ToLowerInvariant()
            apiShape = 'net.milkbowl.vault.economy.Economy#getBalance(OfflinePlayer)'
            synthetic = $true
        }
    )
    probes = @($assertions)
    compressedLog = [ordered]@{
        path = "$reportStem.log.gz"
        sha256 = (Get-FileHash -LiteralPath $compressedLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$reportJson = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($reportPath, $reportJson + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Vault Economy contract fixture passed: $reportPath (synthetic API contract; no real Vault compatibility claim)"
