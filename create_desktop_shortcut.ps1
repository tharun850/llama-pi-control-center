# Script to create a Desktop shortcut for Llama Server & Pi Control Center
$desktopPath = [System.Environment]::GetFolderPath('Desktop')
$shortcutPath = Join-Path -Path $desktopPath -ChildPath "Llama & Pi Control Center.lnk"
$targetBat = Join-Path -Path $PSScriptRoot -ChildPath "start.bat"
$workingDir = $PSScriptRoot

$wshShell = New-Object -ComObject WScript.Shell
$shortcut = $wshShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $targetBat
$shortcut.WorkingDirectory = $workingDir
$shortcut.Description = "Start Llama Server & Pi Control Center"
$shortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll, 220"
$shortcut.Save()

# Clean up any obsolete shortcuts
$oldShortcut1 = Join-Path -Path $desktopPath -ChildPath "Llama & OpenCode Control Center.lnk"
if (Test-Path $oldShortcut1) {
    Remove-Item $oldShortcut1 -Force
}

Write-Host "Desktop shortcut created successfully at: $shortcutPath" -ForegroundColor Green
