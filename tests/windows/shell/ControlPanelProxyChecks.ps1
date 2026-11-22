# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
# The control panel's proxy settings (every app, one app) run the production functions on a
# scratch proxy.properties: ProxySetting.parse's validation, the java.util.Properties
# format ProxySetting.save writes and reads, ProxySetting.save's removal rules, and the
# mode/address editor on real WPF controls.
param(
    [string] $PanelPath = (Join-Path $PSScriptRoot '../../../tools/package/gesher-control-panel.ps1')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
. (Join-Path $repo 'tools/package/gesher-ui.ps1')
$script:GesherUiLanguage = 'en'
$source = [IO.File]::ReadAllText([IO.Path]::GetFullPath($PanelPath))
$tokens = $null; $parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
foreach ($name in 'ConvertTo-ProxyValue', 'Get-ProxySettings', 'Set-ProxySetting', 'Get-ProxyDisplayText',
        'Initialize-ProxyEditor', 'Read-ProxyEditor') {
    $definitions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false))
    if ($definitions.Count -ne 1) { throw "Expected one production function: $name" }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}

$script:Checks = 0
function Assert-Proxy([bool] $value, [string] $message) {
    if (-not $value) { throw $message }
    $script:Checks++
}

# ProxySetting.parse's values.
foreach ($case in @(
        @('127.0.0.1:8080', '127.0.0.1:8080'), @(' http://proxy.example.com:3128/ ', 'proxy.example.com:3128'),
        @('[::1]:8888', '[::1]:8888'), @('DIRECT', 'direct'), @('none', 'direct'), @('off', 'direct'),
        @('windows', 'windows'), @('Default', 'windows'),
        @('proxy.example.com', $null), @(':8080', $null), @('host:', $null), @('host:0', $null),
        @('host:65536', $null), @('host:+80', $null), @('ho st:80', $null), @('https://host:80', $null),
        @('', $null))) {
    $actual = ConvertTo-ProxyValue $case[0]
    Assert-Proxy ($actual -ceq $case[1]) "ConvertTo-ProxyValue '$($case[0])' gave '$actual', expected '$($case[1])'"
}

$scratch = Join-Path ([IO.Path]::GetTempPath()) ('gesher-panel-proxy-' + [Guid]::NewGuid().ToString('N'))
$script:ProxyFile = Join-Path $scratch 'data/system/proxy.properties'
try {
    Assert-Proxy ((Get-ProxySettings).Count -eq 0) 'No file must mean no settings'

    # A file as java.util.Properties.store writes it (escaped ':'), with a comment and another key.
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $script:ProxyFile))
    [IO.File]::WriteAllLines($script:ProxyFile, [string[]]@(
        '#Gesher''s proxy: gesher-app proxy [PACKAGE] host:port|direct|windows',
        '#Mon Nov 23 10:00:00 IST 2026',
        'proxy=10.0.0.1\:3128',
        'proxy.com.example.app=direct',
        'proxy.com.example.other=bad value',
        'unrelated=1'), [Text.UTF8Encoding]::new($false))
    $settings = Get-ProxySettings
    Assert-Proxy ($settings['proxy'] -ceq '10.0.0.1:3128') 'Java-escaped value was not read'
    Assert-Proxy ($settings['proxy.com.example.app'] -ceq 'direct') 'Package value was not read'
    Assert-Proxy (-not $settings.ContainsKey('proxy.com.example.other')) 'An invalid value must be ignored, as ProxySetting.parse does'
    Assert-Proxy ($settings.Count -eq 2) 'Only proxy keys are settings'

    # Every app: replace the value; other lines are kept.
    Set-ProxySetting '' '192.168.1.5:8080'
    $text = [IO.File]::ReadAllText($script:ProxyFile)
    Assert-Proxy ($text.Contains('proxy=192.168.1.5\:8080')) 'Every app''s value is not saved in Properties form'
    Assert-Proxy (-not $text.Contains('10.0.0.1')) 'The previous value of every app was kept'
    Assert-Proxy ($text.Contains('proxy.com.example.app=direct') -and $text.Contains('unrelated=1')) 'Other lines were lost'
    Assert-Proxy ((Get-ProxySettings)['proxy'] -ceq '192.168.1.5:8080') 'Every app''s value does not round-trip'

    # One app: its own setting, 'windows' kept for a package, $null removes it.
    Set-ProxySetting 'com.example.app' '[::1]:8888'
    Assert-Proxy ((Get-ProxySettings)['proxy.com.example.app'] -ceq '[::1]:8888') 'Package proxy does not round-trip'
    Assert-Proxy ([IO.File]::ReadAllText($script:ProxyFile).Contains('proxy.com.example.app=[\:\:1]\:8888')) 'IPv6 value is not escaped as Properties does'
    Set-ProxySetting 'com.example.app' 'windows'
    Assert-Proxy ((Get-ProxySettings)['proxy.com.example.app'] -ceq 'windows') 'Windows'' settings for a package must be kept'
    Set-ProxySetting 'com.example.app' $null
    Assert-Proxy (-not (Get-ProxySettings).ContainsKey('proxy.com.example.app')) 'Removing a package setting left it behind'
    Assert-Proxy ((Get-ProxySettings)['proxy'] -ceq '192.168.1.5:8080') 'Removing a package setting changed every app''s'

    # 'windows' for every app removes the key (ProxySetting.save).
    Set-ProxySetting '' 'windows'
    Assert-Proxy (-not (Get-ProxySettings).ContainsKey('proxy')) 'Windows'' settings for every app must remove the key'
    Set-ProxySetting '' 'direct'
    Assert-Proxy ((Get-ProxySettings)['proxy'] -ceq 'direct') 'Direct for every app was not saved'

    $threw = $false
    try { Set-ProxySetting 'com.example.app' 'nonsense' } catch { $threw = $true }
    Assert-Proxy $threw 'An invalid value must not be saved'
    $threw = $false
    try { Set-ProxySetting 'bad package' 'direct' } catch { $threw = $true }
    Assert-Proxy $threw 'An invalid package must not be saved'

    # A new file starts with ProxySetting's comment.
    Remove-Item -LiteralPath $script:ProxyFile
    Set-ProxySetting 'com.example.new' '127.0.0.1:9000'
    Assert-Proxy ((Get-Content -LiteralPath $script:ProxyFile)[0].StartsWith('#')) 'A new file has no comment'
    Assert-Proxy ((Get-ProxySettings)['proxy.com.example.new'] -ceq '127.0.0.1:9000') 'Package proxy in a new file does not round-trip'

    # The editor on real WPF controls.
    $combo = [System.Windows.Controls.ComboBox]::new()
    $address = [System.Windows.Controls.TextBox]::new()
    Initialize-ProxyEditor $combo $address $null
    Assert-Proxy ($combo.Items.Count -eq 3 -and $combo.SelectedItem.Tag -eq 'windows' -and -not $address.IsEnabled) 'Every app''s editor must start at Windows'' settings'
    Initialize-ProxyEditor $combo $address '10.1.1.1:80'
    Assert-Proxy ($combo.Items.Count -eq 3 -and $combo.SelectedItem.Tag -eq 'server' -and $address.Text -eq '10.1.1.1:80' -and $address.IsEnabled) 'A saved server is not shown'
    $address.Text = 'http://10.2.2.2:3128/'
    $choice = Read-ProxyEditor $combo $address
    Assert-Proxy ($choice.Valid -and $choice.Value -ceq '10.2.2.2:3128') 'A server address was not read'
    $address.Text = 'direct'
    Assert-Proxy (-not (Read-ProxyEditor $combo $address).Valid) 'A mode name is not a server address'
    $address.Text = 'nope'
    $choice = Read-ProxyEditor $combo $address
    Assert-Proxy (-not $choice.Valid -and $choice.Text -eq 'nope') 'An invalid address was accepted'
    foreach ($item in $combo.Items) { if ($item.Tag -eq 'direct') { $combo.SelectedItem = $item } }
    Assert-Proxy (-not $address.IsEnabled) 'The address stays editable without a server'
    $choice = Read-ProxyEditor $combo $address
    Assert-Proxy ($choice.Valid -and $choice.Value -eq 'direct') 'Direct was not read'

    $appCombo = [System.Windows.Controls.ComboBox]::new()
    $appAddress = [System.Windows.Controls.TextBox]::new()
    Initialize-ProxyEditor $appCombo $appAddress $null -WithInherit
    Assert-Proxy ($appCombo.Items.Count -eq 4 -and $appCombo.SelectedItem.Tag -eq 'inherit') 'An app without its own setting must use every app''s'
    $choice = Read-ProxyEditor $appCombo $appAddress
    Assert-Proxy ($choice.Valid -and $null -eq $choice.Value) 'Inherit must remove the app''s setting'
    Initialize-ProxyEditor $appCombo $appAddress 'windows' -WithInherit
    Assert-Proxy ($appCombo.SelectedItem.Tag -eq 'windows') 'An app''s Windows'' settings are not shown'
    foreach ($item in $appCombo.Items) { if ($item.Tag -eq 'server') { $appCombo.SelectedItem = $item } }
    Assert-Proxy ($appAddress.IsEnabled) 'The address is not editable for a server'

    Assert-Proxy ((Get-ProxyDisplayText 'direct') -eq (Get-GesherText 'cp_proxy_mode_direct')) 'Direct is not shown by name'
    Assert-Proxy ((Get-ProxyDisplayText '1.2.3.4:5') -eq '1.2.3.4:5') 'A server is not shown as it is'
    Assert-Proxy ((Get-GesherText 'cp_proxy_app_value' @('x')) -eq 'Proxy: x') 'The app value text is missing'
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "PASS: control panel proxy settings ($script:Checks checks)"
