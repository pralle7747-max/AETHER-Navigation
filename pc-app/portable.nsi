Unicode true
!ifndef VER
  !define VER "22"
!endif
Name "AETHER"
OutFile "${OUTFILE}"
Icon "icon.ico"
RequestExecutionLevel user
SilentInstall silent
SetCompressor /SOLID lzma
SetCompressorDictSize 64
VIProductVersion "${VER}.0.0.0"
VIAddVersionKey "ProductName" "AETHER Navigation"
VIAddVersionKey "FileDescription" "AETHER Navigation"
VIAddVersionKey "FileVersion" "${VER}.0.0"
VIAddVersionKey "ProductVersion" "${VER}.0.0"
VIAddVersionKey "LegalCopyright" ""
VIAddVersionKey "OriginalFilename" "AETHER.exe"

Section
  StrCpy $0 "$LOCALAPPDATA\AETHER\app-v${VER}"
  IfFileExists "$0\AETHER.exe" run
  ; first start of this version: unpack once, remove older versions
  Banner::show /NOUNLOAD "AETHER wird vorbereitet …"
  RMDir /r "$LOCALAPPDATA\AETHER\app-tmp"
  SetOutPath "$LOCALAPPDATA\AETHER\app-tmp"
  File /r "out\AETHER\*.*"
  FindFirst $1 $2 "$LOCALAPPDATA\AETHER\app-v*"
  loop:
    StrCmp $2 "" done
    RMDir /r "$LOCALAPPDATA\AETHER\$2"
    FindNext $1 $2
    Goto loop
  done:
  FindClose $1
  SetOutPath "$LOCALAPPDATA"
  Rename "$LOCALAPPDATA\AETHER\app-tmp" "$0"
  Banner::destroy
  run:
  SetOutPath "$0"
  Exec '"$0\AETHER.exe"'
SectionEnd
