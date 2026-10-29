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
$revision = '.bind(account, 1, JSON.stringify(layout))'
if (!$server.Contains($revision)) { throw 'Account fixture revision seed changed' }
$server = $server.Replace($revision, '.bind(account, layout.revision, JSON.stringify(layout))')
Set-Content $serverPath $server
$driver = Get-Content "$desktop/tools/account-journey/drive.ps1" -Raw
$probe = @'
    $results.service_probe = 'device_required over https with the real hostname'
    $shared = Invoke-RestMethod "https://$hostName/v1/layout" -Headers @{ Authorization = "Bearer $($secrets.admin_key)"; 'X-Layout-Features' = 'favorite,muted,used,visits,input,settings,hosts,host_removal' }
    if ($shared.revision -ne $shared.layout.revision) { throw 'Shared fixture revision does not match its layout' }
    if (@($shared.layout.tabs | Where-Object { $_.tmux_session -eq 'work-main' -and $_.name -ceq 'Renamed session' }).Count -ne 1) { throw 'Shared service lost the Android name' }
    $shared | ConvertTo-Json -Depth 16 | Set-Content "$Output/shared-layout.json"
'@
$driver = $driver.Replace("    `$results.service_probe = 'device_required over https with the real hostname'", $probe)
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
