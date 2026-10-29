param([Parameter(Mandatory)][string]$Layout, [Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
$Layout = (Resolve-Path $Layout).Path
$desktop = (Resolve-Path './multiprompt').Path
$layoutData = Get-Content $Layout -Raw | ConvertFrom-Json
if (@($layoutData.tabs | Where-Object { $_.tmux_session -eq 'work-main' -and $_.name -ceq 'Renamed session' }).Count -ne 1) { throw 'Android did not persist the expected shared session name' }
$env:RENAME_LAYOUT = $Layout
$serverPath = "$desktop/service/journey/server.mjs"
$server = Get-Content $serverPath -Raw
$server = [regex]::Replace($server, '(?s)const layout = \{.*?\n\};', "const layout = JSON.parse(readFileSync(process.env.RENAME_LAYOUT, 'utf8'));")
Set-Content $serverPath $server
$driver = Get-Content "$desktop/tools/account-journey/drive.ps1" -Raw
$proof = @'
    [void](Wait-For 'saved desktop session name' {
        if (!(Test-Path "$settings/session.json")) { return $false }
        $saved = Get-Content "$settings/session.json" -Raw | ConvertFrom-Json
        @($saved.tabs | Where-Object { $_.name -ceq 'Renamed session' }).Count -eq 1
    } 30)
    Copy-Item "$settings/session.json" "$Output/desktop-session.json"
    Shot 'desktop-rename'
    $results.rename = 'Renamed session from the Android shared layout'
'@
$driver = [regex]::Replace($driver, '(?s)    \$results.before = .*?(?=    \$results.verdict = ''pass'')', [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $proof + "`n" })
$path = "$desktop/tools/account-journey/rename-proof.ps1"
Set-Content $path $driver
New-Item -ItemType Directory -Force "$desktop/bin", $Output | Out-Null
$release = gh release view --repo valentinyeo/multiprompt --json tagName,assets | ConvertFrom-Json
$asset = $release.assets | Where-Object { $_.name -eq 'multiprompt-x64.exe' }
if (!$asset) { throw 'Missing desktop release executable' }
Invoke-WebRequest $asset.url -OutFile "$desktop/bin/multiprompt.exe"
$release.tagName | Set-Content "$Output/desktop-version.txt"
& $path -Exe "$desktop/bin/multiprompt.exe" -Output $Output
if ($LASTEXITCODE -ne 0) { throw 'Desktop rename proof failed' }
Write-Host DESKTOP_RENAME_JOURNEY_OK
