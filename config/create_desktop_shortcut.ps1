$WshShell = New-Object -ComObject WScript.Shell
$DesktopPath = [System.IO.Path]::Combine($env:USERPROFILE, "Desktop")
$ShortcutPath = [System.IO.Path]::Combine($DesktopPath, "FRIDAY AI.lnk")
$Shortcut = $WshShell.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = "D:\codes\Friday\.venv\Scripts\pythonw.exe"
$Shortcut.Arguments = '"D:\codes\Friday\main.py"'
$Shortcut.WorkingDirectory = "D:\codes\Friday"
$Shortcut.WindowStyle = 7
$Shortcut.Description = "Launch FRIDAY AI -- Created by Gaurav"
$Shortcut.IconLocation = "D:\codes\Friday\assets\friday.ico,0"
$Shortcut.Save()