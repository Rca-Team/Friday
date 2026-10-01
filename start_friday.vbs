Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
venvPython = root & "\.venv\Scripts\pythonw.exe"
mainPy = root & "\main.py"

' Set environment variable to protect custom Friday build
shell.Environment("Process")("FRIDAY_SKIP_UPDATE") = "1"
shell.Environment("Process")("BRAHMA_SKIP_UPDATE") = "1"

If fso.FileExists(venvPython) Then
  shell.Run Chr(34) & venvPython & Chr(34) & " " & Chr(34) & mainPy & Chr(34) & " --startup", 0, False
ElseIf fso.FileExists(root & "\pythonw.exe") Then
  shell.Run Chr(34) & root & "\pythonw.exe" & Chr(34) & " " & Chr(34) & mainPy & Chr(34) & " --startup", 0, False
Else
  shell.Run "python.exe " & Chr(34) & mainPy & Chr(34) & " --startup", 0, False
End If
