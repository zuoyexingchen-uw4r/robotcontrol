# create_hud_shortcut.ps1 — 创建「狼牙视觉 HUD」桌面快捷方式
$ErrorActionPreference = "Stop"

$linkName = "狼牙视觉 HUD.lnk"
$desktop  = [Environment]::GetFolderPath("Desktop")
$linkPath = Join-Path $desktop $linkName

$python = "C:\Users\11603\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
$workDir = "C:\Users\11603\AndroidStudioProjects\RobotControl\pc_hud"
$icon    = Join-Path $workDir "hud_icon.ico"

if (-not (Test-Path $python)) { throw "找不到 Python: $python" }
if (-not (Test-Path $workDir)) { throw "找不到工作目录: $workDir" }

$ws = New-Object -ComObject WScript.Shell
$sc = $ws.CreateShortcut($linkPath)
$sc.TargetPath       = $python
$sc.Arguments       = "hud.py"
$sc.WorkingDirectory = $workDir
$sc.Description     = "狼牙视觉 · 笔记本操控 HUD v0.5.1（USB 手柄 + 图传 + War Thunder HUD）"
$sc.WindowStyle     = 1
if (Test-Path $icon) { $sc.IconLocation = $icon }
$sc.Save()

Write-Output "created: $linkPath"
Write-Output "---readback---"
$rb = $ws.CreateShortcut($linkPath)
Write-Output "Target : $($rb.TargetPath)"
Write-Output "Args   : $($rb.Arguments)"
Write-Output "WorkDir: $($rb.WorkingDirectory)"
Write-Output "Icon   : $($rb.IconLocation)"
Write-Output "Desc   : $($rb.Description)"
