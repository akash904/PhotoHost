; PhotoHost installer (Inno Setup 6).
;
; Built by `gradlew packageInstaller`, which passes the defines below. Written to meet the Microsoft
; Store's requirements for desktop apps submitted as an .exe installer, so listing later needs no
; rework:
;
;   - Per-user install, no administrator prompt, into %LOCALAPPDATA%\Programs\PhotoHost.
;   - Fully silent install and uninstall: /VERYSILENT /SUPPRESSMSGBOXES /NORESTART. Never reboots.
;   - One fixed AppId forever, so every version upgrades the same app in place.
;   - Nothing started at sign-in by the installer. The app offers that, off by default.
;   - A desktop shortcut only if the user ticks it; never in a silent install.
;   - Uninstall removes the program and its start-at-sign-in entry, and leaves the user's library and
;     data alone: they are the user's photos, not the program's files.
;   - Signed when a certificate is configured: the build passes a SignTool and the setup, the
;     uninstaller and PhotoHost.exe are all signed. Unsigned otherwise, which is fine for personal use
;     but not for the Store.

#ifndef AppVersion
  #error AppVersion must be defined (the build passes it)
#endif
#ifndef SourceDir
  #error SourceDir must be defined: the jpackage app image
#endif
#ifndef IconFile
  #error IconFile must be defined
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

[Setup]
; NEVER CHANGE THIS. It is how Windows and the Store know that every future version is the same
; application. A new id would install a second, separate PhotoHost beside the first.
AppId={{E09B110E-92A5-4880-837E-DA0FE92172F7}
AppName=PhotoHost
AppVersion={#AppVersion}
AppVerName=PhotoHost {#AppVersion}
AppPublisher=PhotoHost
VersionInfoVersion={#AppVersion}
VersionInfoProductName=PhotoHost
VersionInfoDescription=PhotoHost Setup

; Per-user: no UAC prompt, and {autopf} resolves to %LOCALAPPDATA%\Programs.
PrivilegesRequired=lowest
DefaultDirName={autopf}\PhotoHost
DisableDirPage=auto
DefaultGroupName=PhotoHost
DisableProgramGroupPage=yes

ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0

OutputDir={#OutputDir}
OutputBaseFilename=PhotoHost-Setup-{#AppVersion}
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\PhotoHost.exe
UninstallDisplayName=PhotoHost
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes

; An upgrade replaces files the running app holds open. Restart Manager asks PhotoHost to close first
; and starts it again afterwards, instead of failing halfway or asking for a reboot.
CloseApplications=yes
RestartApplications=yes
RestartIfNeededByRun=no

#ifdef Sign
SignTool=photohost
SignedUninstaller=yes
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; The jpackage app image: PhotoHost.exe, its app\ jars and its own Java runtime.
#ifdef Sign
Source: "{#SourceDir}\PhotoHost.exe"; DestDir: "{app}"; Flags: ignoreversion signonce
Source: "{#SourceDir}\*"; Excludes: "\PhotoHost.exe"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
#else
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
#endif

[InstallDelete]
; Files a previous version shipped and this one does not, so an upgrade does not leave stale jars
; beside the new ones on the classpath.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Icons]
Name: "{group}\PhotoHost"; Filename: "{app}\PhotoHost.exe"
Name: "{autodesktop}\PhotoHost"; Filename: "{app}\PhotoHost.exe"; Tasks: desktopicon

[Registry]
; Created by the app, not here (start at sign-in is the user's choice). Listed only so that uninstall
; removes it: an entry left behind would try to start a program that no longer exists.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "PhotoHost"; Flags: uninsdeletevalue dontcreatekey

[Run]
Filename: "{app}\PhotoHost.exe"; Description: "{cm:LaunchProgram,PhotoHost}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Program-side leftovers only. %LOCALAPPDATA%\PhotoHost (database, thumbnails, pairing) and the
; library folder are deliberately kept: they are the user's.
Type: filesandordirs; Name: "{app}"
