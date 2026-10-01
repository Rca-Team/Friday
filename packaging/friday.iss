[Setup]
AppId={{813E7C71-B3CB-46B2-A55B-7A384E75B111}
AppName=Friday AI
AppVersion=1.0.1
AppPublisher=Gaurav
DefaultDirName={localappdata}\Programs\FridayAI
DefaultGroupName=Friday AI
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\dist
OutputBaseFilename=Friday-Setup-1.0.1-x64
SetupIconFile=..\assets\friday.ico
UninstallDisplayIcon={app}\FRIDAY.exe
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
AppMutex=Local\FridayAI-Desktop
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes
LicenseFile=..\LICENSE

[Files]
Source: "..\dist\FRIDAY\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: checkedonce

[Icons]
Name: "{group}\Friday AI"; Filename: "{app}\FRIDAY.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\FRIDAY"; Filename: "{app}\FRIDAY.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\FRIDAY.exe"; Description: "Launch Friday AI"; Flags: nowait postinstall skipifsilent

; User data deliberately has no UninstallDelete entry.
