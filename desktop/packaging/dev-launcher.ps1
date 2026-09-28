<#
Creates a Start-menu shortcut, "PhotoHost (dev)", that runs the packaged build through its bundled
Java runtime instead of through PhotoHost.exe.

Why: on a PC with Smart App Control on, every freshly built PhotoHost.exe is an unsigned file with no
reputation, and Windows refuses to start it. The bundled runtime's javaw.exe is signed (by the
Eclipse Foundation), so starting the same jars through it works. This is for development builds
only; a release is code-signed instead and needs none of this.

Differences from running PhotoHost.exe: Task Manager shows "OpenJDK Platform binary"; Windows asks
once for firewall permission for javaw.exe; "Start at sign-in" and the window's firewall line do not
apply, because they look for PhotoHost.exe.

Run after `gradlew packageExe` (it reads that build's settings), and again only if the package moves:
    powershell -ExecutionPolicy Bypass -File desktop\packaging\dev-launcher.ps1
#>
param(
    [string]$Package = (Join-Path $PSScriptRoot '..\build\package\PhotoHost')
)
$ErrorActionPreference = 'Stop'

$pkg = (Resolve-Path $Package).Path
$javaw = Join-Path $pkg 'runtime\bin\javaw.exe'
$app = Join-Path $pkg 'app'
$cfg = Join-Path $app 'PhotoHost.cfg'
foreach ($p in $javaw, $cfg) {
    if (-not (Test-Path $p)) { throw "Not found: $p. Build first: gradlew -p desktop packageExe" }
}

# Main class and JVM options come from the launcher settings jpackage wrote, so the shortcut starts
# exactly what PhotoHost.exe would.
$lines = Get-Content $cfg
$main = ($lines | Where-Object { $_ -like 'app.mainclass=*' } | Select-Object -First 1).Substring('app.mainclass='.Length)
$options = $lines | Where-Object { $_ -like 'java-options=*' } | ForEach-Object { $_.Substring('java-options='.Length) }
$arguments = (@($options) + @('-cp', "`"$app\*`"", $main)) -join ' '

$programs = [Environment]::GetFolderPath('Programs')
$link = Join-Path $programs 'PhotoHost (dev).lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($link)
$shortcut.TargetPath = $javaw
$shortcut.Arguments = $arguments
$shortcut.WorkingDirectory = $app
# Only the icon is read from PhotoHost.exe; nothing runs it.
$shortcut.IconLocation = (Join-Path $pkg 'PhotoHost.exe') + ',0'
$shortcut.Description = 'PhotoHost development build, started through its signed Java runtime'
$shortcut.Save()

Write-Output "Created: $link"
Write-Output "Runs:    $javaw $arguments"
