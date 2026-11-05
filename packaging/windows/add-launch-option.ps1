# Adds a "Launch Music Trainer" checkbox (checked by default) to the final page of the MSI built by
# jpackage, and starts the installed app when Finish is clicked with the box ticked.
#
# jpackage has no option for this, but its WiX UI already contains the standard ExitDialog
# "OptionalCheckBox", which appears once WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT is set. We only add
# that property, a custom action that runs the installed exe, and a DoAction event on Finish.
# Safe to run more than once.
param([Parameter(Mandatory = $true)][string]$Msi)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Msi)) { throw "MSI not found: $Msi" }

$installer = New-Object -ComObject WindowsInstaller.Installer
$db = $installer.GetType().InvokeMember("OpenDatabase", "InvokeMethod", $null, $installer, @($Msi, 1)) # 1 = transact

function Invoke-Sql([string]$sql) {
    $view = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @($sql))
    [void]$view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null)
    $rows = @()
    if ($sql.TrimStart().StartsWith("SELECT")) { # only queries have rows to fetch
        while ($rec = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)) {
            $rows += $rec.GetType().InvokeMember("StringData", "GetProperty", $null, $rec, 1)
        }
    }
    [void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)
    return , $rows
}

if ((Invoke-Sql "SELECT Action FROM CustomAction WHERE Action='LaunchApplication'").Count -gt 0) {
    Write-Host "Launch option already present in $Msi"
    exit 0
}

# File-table key of the installed launcher (the key is a hash that changes between builds).
$exeKey = $null
$view = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @("SELECT File, FileName FROM File"))
[void]$view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null)
while ($rec = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)) {
    $name = $rec.GetType().InvokeMember("StringData", "GetProperty", $null, $rec, 2)
    if ($name -like "*|MusicTrainer.exe" -or $name -eq "MusicTrainer.exe") {
        $exeKey = $rec.GetType().InvokeMember("StringData", "GetProperty", $null, $rec, 1)
    }
}
[void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)
if (-not $exeKey) { throw "MusicTrainer.exe not found in the MSI File table" }

Invoke-Sql "INSERT INTO Property (Property, Value) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT', 'Launch Music Trainer')" | Out-Null
Invoke-Sql "INSERT INTO Property (Property, Value) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOX', '1')" | Out-Null
# Type 210 = 18 (run an exe from the File table) + 192 (asynchronous, don't wait for it).
Invoke-Sql "INSERT INTO CustomAction (Action, Type, Source, Target) VALUES ('LaunchApplication', 210, '$exeKey', '')" | Out-Null
# Runs before the existing EndDialog event (Ordering 999) on the Finish button.
Invoke-Sql ("INSERT INTO ControlEvent (Dialog_, Control_, Event, Argument, Condition, Ordering) " +
    "VALUES ('ExitDialog', 'Finish', 'DoAction', 'LaunchApplication', 'WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND NOT Installed', 1)") | Out-Null

[void]$db.GetType().InvokeMember("Commit", "InvokeMethod", $null, $db, $null)
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($db) | Out-Null
Write-Host "Added 'Launch Music Trainer' option to $Msi"
