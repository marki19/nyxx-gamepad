[Setup]
AppName=Nyxx Gamepad Server
AppVersion=0.1.0
DefaultDirName={autopf}\NyxxGamepad
DefaultGroupName=Nyxx Gamepad
OutputDir=..\builds
OutputBaseFilename=NyxxServer_Setup
Compression=lzma
SolidCompression=yes
SetupIconFile=compiler:SetupClassicIcon.ico

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
Source: "..\builds\NyxxPadServer.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "ViGEmBus_Setup.exe"; DestDir: "{tmp}"; Flags: ignoreversion

[Icons]
Name: "{group}\Nyxx Gamepad Server"; Filename: "{app}\NyxxPadServer.exe"
Name: "{autodesktop}\Nyxx Gamepad Server"; Filename: "{app}\NyxxPadServer.exe"; Tasks: desktopicon

[Run]
Filename: "{tmp}\ViGEmBus_Setup.exe"; Parameters: "/passive /norestart"; StatusMsg: "Installing ViGEmBus Driver (Xbox 360 Controller Emulation)..."; Flags: waituntilterminated
Filename: "{app}\NyxxPadServer.exe"; Description: "Launch Nyxx Server"; Flags: nowait postinstall skipifsilent
