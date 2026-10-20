Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!ifndef PAYLOAD_ROOT
  !error "PAYLOAD_ROOT is required"
!endif
!ifndef OUTPUT_EXE
  !error "OUTPUT_EXE is required"
!endif
!ifndef GENERATED_INCLUDE
  !error "GENERATED_INCLUDE is required"
!endif
!ifndef APP_VERSION
  !define APP_VERSION "2026.1018.1"
!endif
!ifndef PRODUCT_VERSION
  !define PRODUCT_VERSION "2026.10.18.1"
!endif
Name "MicBridge Host ${APP_VERSION}"
OutFile "${OUTPUT_EXE}"
InstallDir "$LOCALAPPDATA\Programs\MicBridge-Host"
RequestExecutionLevel user
ManifestSupportedOS all
SetCompressor /SOLID lzma
SetCompressorDictSize 32
ShowInstDetails show
ShowUninstDetails show
VIProductVersion "${PRODUCT_VERSION}"
VIAddVersionKey /LANG=1033 "ProductName" "MicBridge Host"
VIAddVersionKey /LANG=1033 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "FileDescription" "Reviewed-source application files and shortcuts installer"
VIAddVersionKey /LANG=1033 "LegalCopyright" "Apollo/Sunshine contributors; MicBridge modifications, SelzyStudio 2026"
!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${PAYLOAD_ROOT}\LICENSE.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_SHOWREADME "$INSTDIR\README.ko.txt"
!define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Korean"
Var VerifiedInstallRoot
Var Portable
Var ServiceRoot
Var BackupRoot

Function AssertNoReparsePath
  Exch $0
  Push $1
  inspect_path_component:
    System::Call 'kernel32::GetFileAttributesW(w r0) i.r1'
    ${If} $1 != -1
      IntOp $1 $1 & 0x400
      ${If} $1 != 0
        Pop $1
        Pop $0
        MessageBox MB_OK|MB_ICONSTOP "An installation destination contains a symbolic link or junction. No package files were changed. Choose a plain local folder."
        Abort
      ${EndIf}
    ${EndIf}
    ${GetParent} "$0" $1
    ${If} $1 != ""
      ${If} $1 != $0
        StrCpy $0 $1
        Goto inspect_path_component
      ${EndIf}
    ${EndIf}
  Pop $1
  Pop $0
FunctionEnd

; Resolve an existing directory through the filesystem, including short-name
; aliases. Reparse points in the chosen destination are rejected separately.
Function CanonicalExistingDirectory
  Exch $0
  Push $1
  Push $2
  Push $3
  StrCpy $2 $0
  GetFullPathName $0 "$2"
  System::Call 'kernel32::CreateFileW(w r0, i 0, i 7, p 0, i 3, i 0x02000000, p 0) p.r1'
  ${If} $1 == -1
    StrCpy $0 ""
  ${Else}
    System::Call 'kernel32::GetFinalPathNameByHandleW(p r1, w .r2, i ${NSIS_MAX_STRLEN}, i 0) i.r3'
    System::Call 'kernel32::CloseHandle(p r1)'
    ${If} $3 == 0
      StrCpy $0 ""
    ${ElseIf} $3 >= ${NSIS_MAX_STRLEN}
      StrCpy $0 ""
    ${Else}
      StrCpy $3 $2 4
      ${If} $3 == "\\?\"
        StrCpy $2 $2 "" 4
      ${EndIf}
      StrCpy $0 $2
    ${EndIf}
  ${EndIf}
  Pop $3
  Pop $2
  Pop $1
  Exch $0
FunctionEnd

Function FindRegisteredServiceRoot
  StrCpy $ServiceRoot ""
  SetRegView 64
  ReadRegStr $0 HKLM "SYSTEM\CurrentControlSet\Services\ApolloService" "ImagePath"
  ${If} $0 != ""
    StrCpy $1 $0 1
    ${If} $1 == '$\"'
      StrCpy $0 $0 -1 1
    ${EndIf}
    ${GetParent} "$0" $1
    ${GetParent} "$1" $ServiceRoot
    StrCpy $0 $ServiceRoot
    GetFullPathName $ServiceRoot "$0"
    Push $ServiceRoot
    Call CanonicalExistingDirectory
    Pop $ServiceRoot
    ${If} $ServiceRoot == ""
      MessageBox MB_OK|MB_ICONSTOP "The existing Apollo service folder could not be resolved safely. Installation stopped."
      Abort
    ${EndIf}
  ${EndIf}
FunctionEnd

Function .onInit
  StrCpy $Portable "0"
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/PORTABLE" $1
  ${IfNot} ${Errors}
    StrCpy $Portable "1"
  ${EndIf}
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "This package requires 64-bit Windows."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "This package requires Windows 10 or Windows 11 x64."
    Abort
  ${EndIf}
  Call FindRegisteredServiceRoot
  ${If} $ServiceRoot != ""
    IfSilent +2
    MessageBox MB_OK|MB_ICONINFORMATION "An existing ApolloService was found. This installer only places separate application files and shortcuts. It will not stop, replace or reconfigure that service."
  ${EndIf}
FunctionEnd

Function .onVerifyInstDir
  StrCpy $0 $INSTDIR
  System::Call 'kernel32::GetFullPathNameW(w r0, i ${NSIS_MAX_STRLEN}, w .r1, p 0) i.r2'
  ${If} $2 == 0
    Abort "Cannot normalize install path."
  ${EndIf}
  ${If} $2 >= ${NSIS_MAX_STRLEN}
    Abort "Install path too long."
  ${EndIf}
  StrCpy $INSTDIR $1
  StrCpy $0 $INSTDIR 2
  ${If} $0 == "\\"
    MessageBox MB_OK|MB_ICONSTOP "Choose a local folder, not a network or device path."
    Abort
  ${EndIf}
  Push $INSTDIR
  Call AssertNoReparsePath
  ${If} ${FileExists} "$INSTDIR\."
    Push $INSTDIR
    Call CanonicalExistingDirectory
    Pop $INSTDIR
    ${If} $INSTDIR == ""
      MessageBox MB_OK|MB_ICONSTOP "The selected folder could not be resolved safely."
      Abort
    ${EndIf}
  ${EndIf}
  Call FindRegisteredServiceRoot
  ${If} $ServiceRoot != ""
    ${If} $INSTDIR == $ServiceRoot
      MessageBox MB_OK|MB_ICONSTOP "Choose a separate folder. The registered ApolloService installation must be preserved."
      Abort
    ${EndIf}
  ${EndIf}
FunctionEnd

!macro BackupOwnedFile Relative Parent
  ${If} ${FileExists} "$INSTDIR\${Relative}"
    CreateDirectory "$BackupRoot\${Parent}"
    ClearErrors
    CopyFiles /SILENT "$INSTDIR\${Relative}" "$BackupRoot\${Relative}"
    ${If} ${Errors}
      MessageBox MB_OK|MB_ICONSTOP "Could not back up an existing package file. Installation stopped before overwriting it."
      Abort
    ${EndIf}
  ${EndIf}
!macroend

!include "${GENERATED_INCLUDE}"

Section "Application files (no services or drivers)" SecFiles
  SetShellVarContext current
  StrCpy $VerifiedInstallRoot $INSTDIR
  Call .onVerifyInstDir
  ; Check every owned destination and ancestor before the first backup/write.
  ${If} ${FileExists} "$INSTDIR\sunshine.exe"
    ${IfNot} ${FileExists} "$INSTDIR\MicBridge-Host.package"
      Abort "An unrelated host already uses this folder."
    ${EndIf}
  ${EndIf}
  ${If} $INSTDIR == ""
    Abort "Empty install destination refused."
  ${EndIf}
  ${If} $INSTDIR != $VerifiedInstallRoot
    Abort "Install destination changed unexpectedly."
  ${EndIf}
  !insertmacro PreflightPayload
  Push "$INSTDIR\config\sunshine.conf"
  Call AssertNoReparsePath
  Push "$INSTDIR\package-backups"
  Call AssertNoReparsePath
  Push "$INSTDIR\Uninstall-MicBridge-Host.exe"
  Call AssertNoReparsePath
  ${If} $INSTDIR == ""
    Abort "Empty install destination refused."
  ${EndIf}
  ${If} $INSTDIR != $VerifiedInstallRoot
    Abort "Install destination changed unexpectedly."
  ${EndIf}
  CreateDirectory "$INSTDIR"
  CreateDirectory "$INSTDIR\package-backups"
  GetTempFileName $BackupRoot "$INSTDIR\package-backups"
  Delete "$BackupRoot"
  CreateDirectory "$BackupRoot"
  ${If} $INSTDIR == ""
    Abort "Empty install destination refused."
  ${EndIf}
  ${If} $INSTDIR != $VerifiedInstallRoot
    Abort "Install destination changed unexpectedly."
  ${EndIf}
  !insertmacro BackupPayload
  ${If} $INSTDIR == ""
    Abort "Empty install destination refused."
  ${EndIf}
  ${If} $INSTDIR != $VerifiedInstallRoot
    Abort "Install destination changed unexpectedly."
  ${EndIf}
  !insertmacro InstallPayload
  IfFileExists "$INSTDIR\config\sunshine.conf" config_ready
    CreateDirectory "$INSTDIR\config"
    CopyFiles /SILENT "$INSTDIR\config-template\sunshine.conf" "$INSTDIR\config\sunshine.conf"
  config_ready:
  SetOutPath "$INSTDIR"
  ${If} $Portable == "0"
  CreateDirectory "$SMPROGRAMS\MicBridge Host"
  CreateShortCut "$SMPROGRAMS\MicBridge Host\MicBridge Host.lnk" "$INSTDIR\Start-MicBridge.cmd" "" "$INSTDIR\sunshine.exe"
  CreateShortCut "$SMPROGRAMS\MicBridge Host\Read me.lnk" "$INSTDIR\README.ko.txt"
  ${EndIf}
  WriteUninstaller "$INSTDIR\Uninstall-MicBridge-Host.exe"
  ${If} $Portable == "1"
    FileOpen $0 "$INSTDIR\.portable-install" w
    FileWrite $0 "MicBridge Host portable install"
    FileClose $0
  ${Else}
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "DisplayName" "MicBridge Host ${APP_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "UninstallString" '$\"$INSTDIR\Uninstall-MicBridge-Host.exe$\"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "DisplayVersion" "${APP_VERSION}"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "NoRepair" 1
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "InstallLocation" "$INSTDIR"
  ${EndIf}
SectionEnd

Function un.AssertNoReparsePath
  Exch $0
  Push $1
  inspect_path_component:
    System::Call 'kernel32::GetFileAttributesW(w r0) i.r1'
    ${If} $1 != -1
      IntOp $1 $1 & 0x400
      ${If} $1 != 0
        Pop $1
        Pop $0
        MessageBox MB_OK|MB_ICONSTOP "An installation destination contains a symbolic link or junction. No package files were changed. Choose a plain local folder."
        Abort
      ${EndIf}
    ${EndIf}
    ${GetParent} "$0" $1
    ${If} $1 != ""
      ${If} $1 != $0
        StrCpy $0 $1
        Goto inspect_path_component
      ${EndIf}
    ${EndIf}
  Pop $1
  Pop $0
FunctionEnd

Function un.onInit
  SetRegView 64
  ReadRegStr $0 HKLM "SYSTEM\CurrentControlSet\Services\ApolloService" "ImagePath"
  ${If} $0 == "$INSTDIR\tools\sunshinesvc.exe"
    MessageBox MB_OK|MB_ICONSTOP "ApolloService uses this folder. Reconfigure that service deliberately before removing these files."
    Abort
  ${EndIf}
  ${If} $0 == '$\"$INSTDIR\tools\sunshinesvc.exe$\"'
    MessageBox MB_OK|MB_ICONSTOP "ApolloService uses this folder. Reconfigure that service deliberately before removing these files."
    Abort
  ${EndIf}
  !insertmacro UninstallPreflight
  ; Lock only our executable; a different installed Apollo may keep running.
  System::Call 'kernel32::CreateFileW(w "$INSTDIR\sunshine.exe", i 0xC0000000, i 0, p 0, i 3, i 0, p 0) p.r0'
  ${If} $0 == -1
    Abort "This package executable is in use or cannot be checked. Close MicBridge Host first."
  ${Else}
    System::Call 'kernel32::CloseHandle(p r0)'
  ${EndIf}
FunctionEnd

Section "Uninstall"
  SetShellVarContext current
  !insertmacro RemovePayload
  ${IfNot} ${FileExists} "$INSTDIR\.portable-install"
  ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost" "InstallLocation"
  ${If} $0 == $INSTDIR
  Delete "$SMPROGRAMS\MicBridge Host\MicBridge Host.lnk"
  Delete "$SMPROGRAMS\MicBridge Host\Read me.lnk"
  RMDir "$SMPROGRAMS\MicBridge Host"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MicBridgeHost"
  ${EndIf}
  ${EndIf}
  Delete "$INSTDIR\.portable-install"
  Delete "$INSTDIR\Uninstall-MicBridge-Host.exe"
  ; Config and package-backups deliberately remain. No recursive removal.
  RMDir "$INSTDIR"
SectionEnd
