Unicode true
!include "MUI2.nsh"
!ifndef VER
  !define VER "20"
!endif
Name "AETHER Navigation"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\AETHER"
InstallDirRegKey HKCU "Software\AETHER" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 64
BrandingText "AETHER v${VER}"
VIProductVersion "${VER}.0.0.0"
VIAddVersionKey "ProductName" "AETHER Navigation"
VIAddVersionKey "FileDescription" "AETHER Navigation Setup"
VIAddVersionKey "FileVersion" "${VER}.0.0"
VIAddVersionKey "ProductVersion" "${VER}.0.0"
VIAddVersionKey "LegalCopyright" ""
!define MUI_ICON "icon.ico"
!define MUI_UNICON "icon.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\AETHER.exe"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "English"

Section "AETHER"
  nsExec::Exec 'taskkill /IM AETHER.exe /F'
  RMDir /r "$INSTDIR\resources\app"
  SetOutPath "$INSTDIR"
  File /r "out\AETHER\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\AETHER"
  CreateShortcut "$SMPROGRAMS\AETHER\AETHER.lnk" "$INSTDIR\AETHER.exe"
  CreateShortcut "$SMPROGRAMS\AETHER\AETHER deinstallieren.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\AETHER.lnk" "$INSTDIR\AETHER.exe"
  WriteRegStr HKCU "Software\AETHER" "InstallDir" "$INSTDIR"
  !define UK "Software\Microsoft\Windows\CurrentVersion\Uninstall\AETHER"
  WriteRegStr HKCU "${UK}" "DisplayName" "AETHER Navigation"
  WriteRegStr HKCU "${UK}" "DisplayVersion" "${VER}"
  WriteRegStr HKCU "${UK}" "Publisher" "AETHER"
  WriteRegStr HKCU "${UK}" "DisplayIcon" "$INSTDIR\AETHER.exe"
  WriteRegStr HKCU "${UK}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${UK}" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKCU "${UK}" "NoModify" 1
  WriteRegDWORD HKCU "${UK}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /IM AETHER.exe /F'
  Delete "$DESKTOP\AETHER.lnk"
  RMDir /r "$SMPROGRAMS\AETHER"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AETHER"
  DeleteRegKey HKCU "Software\AETHER"
SectionEnd
