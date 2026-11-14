param(
    [ValidatePattern('^r[1-9]\d*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$ServerDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reportStem = "paper-1.21.1-vault-essentials-provider-20261114-$EvidenceRevision"
$ServerDirectory = if ([string]::IsNullOrWhiteSpace($ServerDirectory)) {
    "srv-paper-vault-essentials-provider-20261114-$EvidenceRevision"
} else { $ServerDirectory }
$server = if ([System.IO.Path]::IsPathRooted($ServerDirectory)) {
    [System.IO.Path]::GetFullPath($ServerDirectory)
} else { [System.IO.Path]::GetFullPath((Join-Path $repo $ServerDirectory)) }
$paper = if ([string]::IsNullOrWhiteSpace($env:PAPER_JAR_PATH)) {
    Join-Path $repo 'srv-paper-inline-condition-strict-20261112-r1/paper.jar'
} else { [System.IO.Path]::GetFullPath($env:PAPER_JAR_PATH) }
$plugin = Join-Path $repo 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$fixtureJar = Join-Path $PSScriptRoot 'vault-provider-fixture/target/vault-essentials-provider-paper-fixture.jar'
$clientScript = Join-Path $PSScriptRoot 'vault-provider-fixture/player-client.js'
$cache = Join-Path $PSScriptRoot '.external-cache'
$vaultJar = Join-Path $cache 'Vault-1.7.3.jar'
$essentialsJar = Join-Path $cache 'EssentialsX-2.21.0.jar'
$reportPath = Join-Path $PSScriptRoot "reports/$reportStem.json"
$compressedLogPath = Join-Path $PSScriptRoot "reports/$reportStem.log.gz"
$clientLogPath = Join-Path $server 'logs/client.log'

function Get-PinnedArtifact([string]$Path, [string]$Url, [string]$Expected, [string]$Name) {
    $downloaded = $false
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
        $curlCommand = Get-Command curl -CommandType Application -ErrorAction Stop | Select-Object -First 1
        & $curlCommand.Source --fail --location --silent --show-error --retry 3 --output $Path $Url
        if ($LASTEXITCODE -ne 0) {
            Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
            throw "Could not download pinned artifact $Name (curl exit $LASTEXITCODE)."
        }
        $downloaded = $true
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $Expected) {
        if ($downloaded) { Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue }
        throw "$Name SHA-256 mismatch: $actual (expected $Expected)"
    }
    return $Path
}

function Get-PaperImplementationVersion([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) { return $null }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $match = [regex]::Match($manifest, '(?m)^Implementation-Version:\s*(.+?)\s*$')
        if ($match.Success) { return $match.Groups[1].Value.Trim() }
        return $null
    } finally { $archive.Dispose() }
}

function Stop-FixtureProcess($Process, [string]$Name) {
    if ($null -eq $Process -or $Process.HasExited) { return }
    try {
        $Process.Kill()
        $Process.WaitForExit()
    } catch {
        if (-not $Process.HasExited) { $Process.Kill(); $Process.WaitForExit() }
    }
}

$vaultJar = Get-PinnedArtifact $vaultJar `
    'https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar' `
    'a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d' 'Vault 1.7.3'
$essentialsJar = Get-PinnedArtifact $essentialsJar `
    'https://repo.essentialsx.net/releases/net/essentialsx/EssentialsX/2.21.0/EssentialsX-2.21.0.jar' `
    '5704322a54836b984b110f7e2208baed1886c2efed4446b497e67e512fec90c5' 'EssentialsX 2.21.0'
if (-not (Test-Path -LiteralPath $paper -PathType Leaf)) { throw "Paper 1.21.1-133 artifact not found: $paper" }
$paperSha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
if ($paperSha256 -cne '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9') {
    throw "Paper 1.21.1-133 SHA-256 mismatch: $paperSha256"
}
foreach ($required in @($plugin, $fixtureJar, $clientScript)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required fixture artifact not found: $required" }
}
$paperVersion = Get-PaperImplementationVersion $paper
if ($paperVersion -notmatch '^1\.21\.1-133(?:-|$)') {
    $paperVersion = '1.21.1-133 (official Paperclip SHA-256 pinned)'
}
foreach ($path in @($server, $reportPath, $compressedLogPath)) {
    if (Test-Path -LiteralPath $path) { throw "Refusing to reuse an existing fixture output: $path" }
}

New-Item -ItemType Directory -Path $server -Force | Out-Null
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
New-Item -ItemType Directory -Path $plugins -Force | Out-Null
Copy-Item -LiteralPath $vaultJar -Destination (Join-Path $plugins 'Vault.jar')
Copy-Item -LiteralPath $essentialsJar -Destination (Join-Path $plugins 'EssentialsX.jar')
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'helsteraMobs.jar')
Copy-Item -LiteralPath $fixtureJar -Destination (Join-Path $plugins 'VaultEssentialsParityFixture.jar')
$pluginData = Join-Path $plugins 'helsteraMobs'
New-Item -ItemType Directory -Path $pluginData -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.yml') -Destination (Join-Path $pluginData 'config.yml')
$configPath = Join-Path $pluginData 'config.yml'
$config = [System.IO.File]::ReadAllText($configPath)
$config = [regex]::Replace($config, '(?m)^(  self-test(?:-[A-Za-z0-9-]+)?):\s*(?:true|false)\s*$', '$1: false')
[System.IO.File]::WriteAllText($configPath, $config, [System.Text.UTF8Encoding]::new($false))
$propertiesPath = Join-Path $server 'server.properties'
$properties = [System.IO.File]::ReadAllText($propertiesPath)
$properties = [regex]::Replace($properties, '(?m)^gamemode=.*$', 'gamemode=survival')
[System.IO.File]::WriteAllText($propertiesPath, $properties, [System.Text.UTF8Encoding]::new($false))
New-Item -ItemType Directory -Path (Join-Path $server 'logs') -Force | Out-Null

$serverProcess = Start-Process -FilePath 'java' -ArgumentList '-Xms1G -Xmx2G -jar paper.jar nogui' `
    -WorkingDirectory $server -PassThru `
    -RedirectStandardOutput (Join-Path $server 'logs/console.log') `
    -RedirectStandardError (Join-Path $server 'logs/console-error.log')
$clientProcess = $null
try {
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    $clientStarted = $false
    while ([DateTime]::UtcNow -lt $deadline -and -not $serverProcess.HasExited) {
        $consoleLog = Join-Path $server 'logs/latest.log'
        $joined = (Test-Path -LiteralPath $consoleLog) -and
            (Select-String -LiteralPath $consoleLog -Pattern 'Done \(' -Quiet)
        if ($joined -and -not $clientStarted) {
            $clientStarted = $true
            $nodeCommand = Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1
            $clientProcess = Start-Process -FilePath $nodeCommand.Source `
                -ArgumentList ('"' + $clientScript + '" 127.0.0.1 25731') `
                -WorkingDirectory $PSScriptRoot -PassThru `
                -RedirectStandardOutput $clientLogPath `
                -RedirectStandardError (Join-Path $server 'logs/client-error.log')
        }
        if ((Test-Path -LiteralPath $consoleLog) -and
                (Select-String -LiteralPath $consoleLog -Pattern 'VAULT_ESSENTIALS_PROVIDER_PROBE ' -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    $consoleLog = Join-Path $server 'logs/latest.log'
    $capturedOutput = if (Test-Path -LiteralPath $consoleLog) { Get-Content -LiteralPath $consoleLog -Encoding utf8 } else { @() }
    $probeLine = $capturedOutput | Where-Object { $_ -match 'VAULT_ESSENTIALS_PROVIDER_PROBE ' } | Select-Object -Last 1
    if (-not $probeLine) {
        $tail = $capturedOutput | Select-Object -Last 80 | Join-String -Separator "`n"
        throw "Real-provider probe did not complete. Recent server output:`n$tail"
    }
    if (-not $serverProcess.WaitForExit(30000)) {
        throw 'Paper did not complete its requested shutdown after the provider probe.'
    }
    if ($clientProcess -and -not $clientProcess.HasExited) {
        [void]$clientProcess.WaitForExit(10000)
    }
} finally {
    Stop-FixtureProcess $clientProcess 'client'
    Stop-FixtureProcess $serverProcess 'Paper'
}
if ($serverProcess.ExitCode -ne 0) { throw "Paper exited with code $($serverProcess.ExitCode)." }

$payload = [regex]::Match($probeLine, 'VAULT_ESSENTIALS_PROVIDER_PROBE\s+(.+)$').Groups[1].Value
$observed = @{}
foreach ($token in ($payload -split '\s+')) {
    $pair = $token -split '=', 2
    if ($pair.Count -eq 2) { $observed[$pair[0]] = $pair[1] }
}
$requiredChecks = @(
    'vault_enabled', 'essentials_enabled', 'host_enabled', 'real_player_online',
    'provider_registered', 'essentials_provider', 'adapter_connected', 'economy_capability',
    'deposit_success', 'real_balance_updated', 'condition_positive_pass', 'condition_negative_reject'
)
if ($observed.result -cne 'passed' -or $observed.fixture -cne 'installed-provider' -or
        $observed.'vault-version' -notlike '1.7.3*' -or
        $observed.'essentials-version' -cne '2.21.0' -or
        $observed.'provider-class' -cne 'com.earth2me.essentials.economy.vault.VaultEconomyProvider') {
    throw "Installed Vault/EssentialsX provider fixture failed or observed unexpected metadata: $payload"
}
foreach ($check in $requiredChecks) {
    if ($observed[$check] -cne 'true') { throw "Real provider check '$check' failed: $payload" }
}

$rawLog = [System.IO.File]::ReadAllText((Join-Path $server 'logs/latest.log'))
$rawLog += "`nPaper console output:`n" + [System.IO.File]::ReadAllText((Join-Path $server 'logs/console.log'))
if (Test-Path -LiteralPath $clientLogPath) {
    $rawLog += "`nClient output:`n" + [System.IO.File]::ReadAllText($clientLogPath)
}
$redactedLog = [regex]::Replace($rawLog, '(?i)((?:令牌|token)\s*[:=]\s*)[a-f0-9]{8,}', '$1[REDACTED]')
$compressed = [System.IO.File]::Create($compressedLogPath)
$gzip = [System.IO.Compression.GZipStream]::new($compressed, [System.IO.Compression.CompressionLevel]::Optimal)
$writer = [System.IO.StreamWriter]::new($gzip, [System.Text.UTF8Encoding]::new($false))
try { $writer.Write($redactedLog) } finally { $writer.Dispose(); $gzip.Dispose() }

$assertions = foreach ($check in $requiredChecks) {
    [ordered]@{ id = $check; status = 'passed'; observed = $observed[$check] }
}
$report = [ordered]@{
    schemaVersion = 1
    fixture = $reportStem
    evidenceBoundary = 'Installed Vault 1.7.3-b131 with EssentialsX 2.21.0 provider and one real Paper player session; compatibility evidence is limited to these exact builds and this runtime path.'
    generatedAtUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    paper = [ordered]@{
        implementationVersion = $paperVersion
        sha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    helsteraMobsSha256 = (Get-FileHash -LiteralPath $plugin -Algorithm SHA256).Hash.ToLowerInvariant()
    fixtureSha256 = (Get-FileHash -LiteralPath $fixtureJar -Algorithm SHA256).Hash.ToLowerInvariant()
    externalPlugins = @(
        [ordered]@{
            name = 'Vault'
            version = $observed.'vault-version'
            sha256 = (Get-FileHash -LiteralPath $vaultJar -Algorithm SHA256).Hash.ToLowerInvariant()
            source = 'https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar'
        },
        [ordered]@{
            name = 'EssentialsX'
            version = $observed.'essentials-version'
            sha256 = (Get-FileHash -LiteralPath $essentialsJar -Algorithm SHA256).Hash.ToLowerInvariant()
            providerClass = $observed.'provider-class'
            source = 'https://repo.essentialsx.net/releases/net/essentialsx/EssentialsX/2.21.0/EssentialsX-2.21.0.jar'
        }
    )
    probes = @($assertions)
    compressedLog = [ordered]@{
        path = "$reportStem.log.gz"
        sha256 = (Get-FileHash -LiteralPath $compressedLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
[System.IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 8) + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Vault 1.7.3 + EssentialsX 2.21.0 real-provider fixture passed: $reportPath"
