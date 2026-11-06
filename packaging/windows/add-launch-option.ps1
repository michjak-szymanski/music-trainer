# Post-processes the MSI built by jpackage:
#  1. Closes a running Music Trainer before anything is replaced. The app doesn't answer the Restart Manager's
#     close request, which left updates stuck on "Files in use". jpackage schedules RemoveExistingProducts very
#     early (the old version is uninstalled first, with its own files-in-use check), so taskkill must run before
#     that. The Finish page can start the app again.
#  2. Adds a "Launch Music Trainer" checkbox (checked by default) to the final page of the installer and
#     starts the installed app when Finish is clicked with the box ticked.
#
# jpackage has no option for this, but its WiX UI already contains the standard ExitDialog
# "OptionalCheckBox", which appears once WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT is set. We only add
# that property, a custom action that runs the installed exe, and a DoAction event on Finish.
# Safe to run more than once.
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$ExeName = "MusicTrainer.exe",
    [string]$AppName = "Music Trainer"
)

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

function Test-Action([string]$name) {
    return (Invoke-Sql "SELECT Action FROM CustomAction WHERE Action='$name'").Count -gt 0
}

if (-not (Test-Action "CloseRunningApp")) {
    # Earliest point where the old version or our files could be touched.
    $remove = (Invoke-Sql "SELECT Sequence FROM InstallExecuteSequence WHERE Action='RemoveExistingProducts'")
    $validate = (Invoke-Sql "SELECT Sequence FROM InstallExecuteSequence WHERE Action='InstallValidate'")
    $seq = [Math]::Min($(if ($remove.Count) { [int]$remove[0] } else { 1400 }), $(if ($validate.Count) { [int]$validate[0] } else { 1400 })) - 1
    # Runs before CostFinalize, so it can't depend on directories: first a type-51 action stores the path of
    # taskkill.exe (SystemFolder is known from the start), then a type-50 action runs that exe.
    # Type 114 = 50 (exe named by a property) + 64 (ignore the exit code: nothing to close is fine).
    Invoke-Sql "INSERT INTO CustomAction (Action, Type, Source, Target) VALUES ('SetTaskkillPath', 51, 'MT_TASKKILL', '[SystemFolder]taskkill.exe')" | Out-Null
    Invoke-Sql "INSERT INTO CustomAction (Action, Type, Source, Target) VALUES ('CloseRunningApp', 114, 'MT_TASKKILL', '/F /T /IM $ExeName')" | Out-Null
    Invoke-Sql "INSERT INTO InstallExecuteSequence (Action, Condition, Sequence) VALUES ('SetTaskkillPath', '', $($seq - 1))" | Out-Null
    Invoke-Sql "INSERT INTO InstallExecuteSequence (Action, Condition, Sequence) VALUES ('CloseRunningApp', '', $seq)" | Out-Null
    Write-Host "Added 'close running app' step (sequence $seq) to $Msi"
}

if (Test-Action "LaunchApplication") {
    [void]$db.GetType().InvokeMember("Commit", "InvokeMethod", $null, $db, $null)
    Write-Host "Launch option already present in $Msi"
    exit 0
}

# File-table key of the installed launcher (the key is a hash that changes between builds).
$exeKey = $null
$view = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @("SELECT File, FileName FROM File"))
[void]$view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null)
while ($rec = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)) {
    $name = $rec.GetType().InvokeMember("StringData", "GetProperty", $null, $rec, 2)
    if ($name -like "*|$ExeName" -or $name -eq $ExeName) {
        $exeKey = $rec.GetType().InvokeMember("StringData", "GetProperty", $null, $rec, 1)
    }
}
[void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)
if (-not $exeKey) { throw "MusicTrainer.exe not found in the MSI File table" }

Invoke-Sql "INSERT INTO Property (Property, Value) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT', 'Launch $AppName')" | Out-Null
Invoke-Sql "INSERT INTO Property (Property, Value) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOX', '1')" | Out-Null
# Type 210 = 18 (run an exe from the File table) + 192 (asynchronous, don't wait for it).
Invoke-Sql "INSERT INTO CustomAction (Action, Type, Source, Target) VALUES ('LaunchApplication', 210, '$exeKey', '')" | Out-Null
# Runs before the existing EndDialog event (Ordering 999) on the Finish button.
Invoke-Sql ("INSERT INTO ControlEvent (Dialog_, Control_, Event, Argument, Condition, Ordering) " +
    "VALUES ('ExitDialog', 'Finish', 'DoAction', 'LaunchApplication', 'WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND NOT Installed', 1)") | Out-Null

[void]$db.GetType().InvokeMember("Commit", "InvokeMethod", $null, $db, $null)
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($db) | Out-Null
Write-Host "Added 'Launch Music Trainer' option to $Msi"
