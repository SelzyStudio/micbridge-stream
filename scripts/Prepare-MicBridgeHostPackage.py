"""Build a separate user-mode package from the new isolated build, never the live host."""
import hashlib,json,os,re,shutil,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'distribution-work/2026-10-20-r1'
PACK=WORK/'packaging/windows'
PAY=WORK/'candidate/windows/payload'
REL=ROOT/'release/2026-10-20-public-rc1'
MSYS=ROOT/'toolchains/pc/msys64/ucrt64'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def copy(a,b):b.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(a,b)
def main():
    PACK.mkdir(parents=True,exist_ok=True);PAY.mkdir(parents=True,exist_ok=True);REL.mkdir(exist_ok=True)
    src=WORK/'checkouts/apollo';build=WORK/'build/pc'
    copy(build/'sunshine.exe',PAY/'sunshine.exe')
    # No driver installers, current configuration, service registration or credentials.
    for p in (build/'assets').rglob('*'):
        if p.is_file() and 'shaders' not in p.relative_to(build/'assets').parts:copy(p,PAY/'assets'/p.relative_to(build/'assets'))
    for p in (src/'src_assets/windows/assets/shaders').rglob('*'):
        if p.is_file():copy(p,PAY/'assets/shaders'/p.relative_to(src/'src_assets/windows/assets/shaders'))
    for name in ('LICENSE','NOTICE'):copy(src/name,PAY/(name+'.txt'))
    write(PAY/'MicBridge-Host.package','MicBridge Host 1.0.0-rc1\n')
    write(PAY/'config-template/sunshine.conf','stream_mic = enabled\nmic_backend = steam_streaming_microphone\ninstall_steam_audio_drivers = disabled\nvirtual_sink = Steam Streaming Speakers\n')
    launch=(ROOT/'packaging/windows/Launch-Apollo.ps1').read_text(encoding='utf-8-sig').replace('Apollo Mic Local','MicBridge Host')
    write(PAY/'tools/Launch-MicBridge.ps1',launch)
    write(PAY/'Start-MicBridge.cmd','@echo off\r\ncd /d "%~dp0"\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\\Launch-MicBridge.ps1"\r\n')
    copy(ROOT/'scripts/MicBridge-Readiness.ps1',PAY/'tools/MicBridge-Readiness.ps1')
    write(PAY/'Check-Requirements.cmd','@echo off\r\npowershell.exe -NoProfile -File "%~dp0tools\\MicBridge-Readiness.ps1"\r\npause\r\n')
    copy(ROOT/'packaging/public/README-Windows.ko.txt',PAY/'README.ko.txt')
    write(PAY/'SOURCE.txt','MicBridge Stream / Host 1.0.0-rc1. Modified 2026-10-20.\n'
      'This is an unofficial fork of Apollo/Sunshine and Artemis/Moonlight, not an upstream release.\n'
      'Matching source: MicBridge-Stream-1.0.0-rc1-Corresponding-Source.zip, supplied alongside this installer.\n'
      'Source includes modified applications, recursively pinned submodules, codec/crypto sources, MSYS2 recipes/inputs and build scripts.\n'
      'Keep that source asset available from the same release page and equivalent access as binary assets.\n'
      'There is no published GitHub repository URL yet. No upstream auto-update channel is used.\n'
      'AGPL-covered libdisplaydevice source is included in that companion asset. For network deployment, give all remote users this exact source asset; retain the upstream web About/source links and license notices.\n')
    notices=[]
    # Full license texts from each actual source tree, rather than only short identifiers.
    for label,base in [('apollo',src),('artemis',WORK/'checkouts/artemis')]:
        for raw in subprocess.check_output(['git','-C',str(base),'ls-files','--recurse-submodules'],text=True).splitlines():
            p=base/raw
            if p.is_file() and re.search(r'(^|/)(LICENSE[^/]*|COPYING[^/]*|NOTICE[^/]*)$',raw,re.I):
                copy(p,PAY/'licenses'/label/raw);notices.append(label+'/'+raw)
    for base in sorted((WORK/'dependency-sources').iterdir()):
        if not base.is_dir():continue
        for p in base.rglob('*'):
            if p.is_file() and re.fullmatch(r'(LICENSE.*|COPYING.*|NOTICE.*|PATENTS)',p.name,re.I):
                copy(p,PAY/'licenses/native-sources'/base.name/p.relative_to(base));notices.append('native-sources/'+base.name+'/'+p.relative_to(base).as_posix())
    for name in ('boost','brotli','curl','libgcc','libstdc++','libquadmath','libiconv','libpsl','libssh2','libunistring','libvpl','miniupnpc','opus','winpthreads','zlib','zstd','nsis','crt','headers'):
        base=MSYS/'share/licenses'/name
        if not base.exists():raise FileNotFoundError(base)
        for p in base.rglob('*'):
            if p.is_file():copy(p,PAY/'licenses/msys2'/name/p.relative_to(base))
    copy(ROOT/'packaging/public/qrcodejs-LICENSE.txt',PAY/'licenses/qrcodejs-LICENSE.txt')
    # Bundled web dependencies can be devDependencies; collect all package root notices.
    npm=[]
    for p in (src/'node_modules').rglob('package.json'):
        if p.parent.name.startswith('.'):continue
        try:d=json.loads(p.read_text(encoding='utf-8'))
        except (UnicodeError,json.JSONDecodeError):continue
        if not d.get('name') or not d.get('version'):continue
        component=dict(name=d['name'],version=d['version'],license=d.get('license'),repository=d.get('repository'))
        npm.append(component)
        for f in p.parent.iterdir():
            if f.is_file() and re.fullmatch(r'(LICENSE.*|LICENCE.*|COPYING.*|NOTICE.*)',f.name,re.I):copy(f,PAY/'licenses/npm'/d['name'].replace('/','--')/d['version']/f.name)
    write(PAY/'licenses/NPM-PACKAGE-INVENTORY.json',json.dumps(npm,indent=2,ensure_ascii=False)+'\n')
    write(PAY/'THIRD-PARTY-NOTICES.txt','MicBridge Host 1.0.0-rc1, an unofficial modified Apollo/Sunshine fork.\n'
      'Application GPL-3.0; retain notices and provide the matching source asset.\n'
      'libdisplaydevice: AGPL-3.0. FFmpeg/x264/x265: GPL components; source and build recipes accompany release.\n'
      'OpenSSL 3.5.9: Apache-2.0. Opus 1.6.1: BSD and upstream patent notices. QRCode.js: MIT.\n'
      'GCC libraries and MinGW runtime: see individual licenses, including GCC Runtime Library Exception where applicable and libquadmath LGPL.\n'
      'All collected license texts: licenses/. Runtime/recipe inventories and source URLs are in the corresponding source asset.\n'
      'No Valve Steam drivers, ViGEm installer, or virtual display driver binaries are redistributed.\n')
    pe={}
    for p in PAY.glob('*.exe'):
        s=subprocess.check_output([str(MSYS/'bin/objdump.exe'),'-p',str(p)],text=True)
        names=re.findall(r'DLL Name:\s*(\S+)',s)
        missing=[n for n in names if not n.lower().startswith(('api-ms-win-','ext-ms-win-')) and not (Path(os.environ['SystemRoot'])/'System32'/n).is_file()]
        if missing:raise RuntimeError('Missing PE imports '+str(missing))
        pe[p.name]=names
    write(PAY/'BUILD-INFO.json',json.dumps(dict(version='1.0.0-rc1',hostVersion='2026.1020.1-micbridge-rc1',openssl='3.5.9',codecRecipe='c0a89a80c399dcdd25fa7357d5799c0c7988d547',privateFilesIncluded=False,steamDriversIncluded=False,authenticodeSigned=False,peImports=pe),indent=2)+'\n')
    files=[p for p in sorted(PAY.rglob('*')) if p.is_file() and p.name!='payload-manifest.json']
    write(PAY/'payload-manifest.json',json.dumps([dict(path=p.relative_to(PAY).as_posix(),bytes=p.stat().st_size,sha256=sha(p)) for p in files],indent=2)+'\n')
    files.append(PAY/'payload-manifest.json')
    nsis=(ROOT/'packaging/windows/ApolloMicLocal.nsi').read_text()
    nsis=nsis.replace('Apollo Mic Local','MicBridge Host').replace('Apollo-Mic-Local','MicBridge-Host').replace('ApolloMicLocal','MicBridgeHost').replace('Start-Apollo.cmd','Start-MicBridge.cmd')
    nsis=nsis.replace('GetFullPathName $INSTDIR "$INSTDIR"', '''StrCpy $0 $INSTDIR
  System::Call 'kernel32::GetFullPathNameW(w r0, i ${NSIS_MAX_STRLEN}, w .r1, p 0) i.r2'
  ${If} $2 == 0
    Abort "Cannot normalize install path."
  ${EndIf}
  ${If} $2 >= ${NSIS_MAX_STRLEN}
    Abort "Install path too long."
  ${EndIf}
  StrCpy $INSTDIR $1''')
    nsis=nsis.replace('GetFullPathName $0 "$0"','StrCpy $2 $0\n  GetFullPathName $0 "$2"')
    nsis=nsis.replace('GetFullPathName $ServiceRoot "$ServiceRoot"','StrCpy $0 $ServiceRoot\n    GetFullPathName $ServiceRoot "$0"')
    nsis=nsis.replace('Var ServiceRoot','Var Portable\nVar ServiceRoot')
    nsis=nsis.replace('Function .onInit\n','Function .onInit\n  StrCpy $Portable "0"\n  ${GetParameters} $0\n  ClearErrors\n  ${GetOptions} $0 "/PORTABLE" $1\n  ${IfNot} ${Errors}\n    StrCpy $Portable "1"\n  ${EndIf}\n')
    nsis=nsis.replace('  ${If} $ServiceRoot != ""\n    MessageBox MB_OK|MB_ICONINFORMATION','  ${If} $ServiceRoot != ""\n    IfSilent +2\n    MessageBox MB_OK|MB_ICONINFORMATION',1)
    nsis=nsis.replace('  !insertmacro PreflightPayload','  ${If} ${FileExists} "$INSTDIR\\sunshine.exe"\n    ${IfNot} ${FileExists} "$INSTDIR\\MicBridge-Host.package"\n      Abort "An unrelated host already uses this folder."\n    ${EndIf}\n  ${EndIf}\n  !insertmacro PreflightPayload')
    nsis=nsis.replace('  CreateDirectory "$SMPROGRAMS\\MicBridge Host"','  ${If} $Portable == "0"\n  CreateDirectory "$SMPROGRAMS\\MicBridge Host"')
    nsis=nsis.replace('  WriteUninstaller "$INSTDIR\\Uninstall-MicBridge-Host.exe"','  ${EndIf}\n  WriteUninstaller "$INSTDIR\\Uninstall-MicBridge-Host.exe"\n  ${If} $Portable == "1"\n    FileOpen $0 "$INSTDIR\\.portable-install" w\n    FileWrite $0 "MicBridge Host portable install"\n    FileClose $0\n  ${Else}')
    nsis=nsis.replace('  WriteRegDWORD HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MicBridgeHost" "NoRepair" 1','  WriteRegDWORD HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MicBridgeHost" "NoRepair" 1\n  WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MicBridgeHost" "InstallLocation" "$INSTDIR"\n  ${EndIf}')
    start=nsis.index('  nsExec::ExecToStack',nsis.index('Function un.onInit'))
    end=nsis.index('FunctionEnd',start)
    nsis=nsis[:start]+'''  !insertmacro UninstallPreflight
  ; Lock only our executable; a different installed Apollo may keep running.
  System::Call 'kernel32::CreateFileW(w "$INSTDIR\\sunshine.exe", i 0xC0000000, i 0, p 0, i 3, i 0, p 0) p.r0'
  ${If} $0 == -1
    Abort "This package executable is in use or cannot be checked. Close MicBridge Host first."
  ${Else}
    System::Call 'kernel32::CloseHandle(p r0)'
  ${EndIf}
'''+nsis[end:]
    nsis=nsis.replace('  Delete "$SMPROGRAMS\\MicBridge Host\\MicBridge Host.lnk"','  ${IfNot} ${FileExists} "$INSTDIR\\.portable-install"\n  ReadRegStr $0 HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MicBridgeHost" "InstallLocation"\n  ${If} $0 == $INSTDIR\n  Delete "$SMPROGRAMS\\MicBridge Host\\MicBridge Host.lnk"')
    nsis=nsis.replace('  DeleteRegKey HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MicBridgeHost"','  DeleteRegKey HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MicBridgeHost"\n  ${EndIf}\n  ${EndIf}\n  Delete "$INSTDIR\\.portable-install"')
    # Duplicate the pure path guard into uninstaller code before any deletion.
    guard=nsis[nsis.index('Function AssertNoReparsePath'):nsis.index('FunctionEnd',nsis.index('Function AssertNoReparsePath'))+len('FunctionEnd')]
    nsis=nsis.replace('Function un.onInit',guard.replace('Function AssertNoReparsePath','Function un.AssertNoReparsePath')+'\n\nFunction un.onInit')
    # Fail closed before any write if a path helper unexpectedly changed INSTDIR.
    nsis=nsis.replace('Var Portable','Var VerifiedInstallRoot\nVar Portable')
    nsis=nsis.replace('  Call .onVerifyInstDir','  StrCpy $VerifiedInstallRoot $INSTDIR\n  Call .onVerifyInstDir')
    for needle in ('  !insertmacro PreflightPayload','  CreateDirectory "$INSTDIR"','  !insertmacro BackupPayload','  !insertmacro InstallPayload'):
        nsis=nsis.replace(needle,'  ${If} $INSTDIR == ""\n    Abort "Empty install destination refused."\n  ${EndIf}\n  ${If} $INSTDIR != $VerifiedInstallRoot\n    Abort "Install destination changed unexpectedly."\n  ${EndIf}\n'+needle)
    write(PACK/'MicBridgeHost.nsi',nsis)
    lines=[]
    for macro,call in [('PreflightPayload','AssertNoReparsePath'),('UninstallPreflight','un.AssertNoReparsePath')]:
        lines+=['!macro '+macro]
        for p in files:lines +=[f'  Push "$INSTDIR\\{p.relative_to(PAY)}"',f'  Call {call}']
        lines+=['!macroend']
    lines+=['!macro BackupPayload']
    for p in files:lines+=[f'  !insertmacro BackupOwnedFile "{p.relative_to(PAY)}" "{p.relative_to(PAY).parent}"']
    lines+=['!macroend','!macro InstallPayload']
    for p in files:lines +=[f'  SetOutPath "$INSTDIR\\{p.relative_to(PAY).parent}"',f'  File /oname={p.name} "{p}"']
    lines+=['!macroend','!macro RemovePayload']
    for p in files:lines +=[f'  Delete "$INSTDIR\\{p.relative_to(PAY)}"']
    for p in sorted([p for p in PAY.rglob('*') if p.is_dir()],key=lambda p:-len(str(p))):lines +=[f'  RMDir "$INSTDIR\\{p.relative_to(PAY)}"']
    lines+=['!macroend'];write(PACK/'payload.nsh','\n'.join(lines)+'\n')
    exe=REL/'MicBridge-Host-1.0.0-rc1-windows-x64-setup.exe'
    args=[MSYS/'bin/makensis.exe','/V2','/DAPP_VERSION=1.0.0-rc1','/DPRODUCT_VERSION=1.0.0.1','/DPAYLOAD_ROOT='+str(PAY),'/DOUTPUT_EXE='+str(exe),'/DGENERATED_INCLUDE='+str(PACK/'payload.nsh'),PACK/'MicBridgeHost.nsi']
    result=subprocess.run([str(x) for x in args],capture_output=True,text=True)
    write(WORK/'logs/micbridge-nsis.log',result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('NSIS failed; see isolated log')
    portable=REL/'MicBridge-Host-1.0.0-rc1-windows-x64-portable.zip'
    with zipfile.ZipFile(portable,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in files:z.write(p,'MicBridge-Host/'+p.relative_to(PAY).as_posix())
    record=dict(installer=exe.name,installerSha256=sha(exe),portable=portable.name,portableSha256=sha(portable),payloadFiles=len(files),authenticodeSigned=False,installedOnCurrentPc=False)
    write(WORK/'WINDOWS-PACKAGE.json',json.dumps(record,indent=2)+'\n');print(json.dumps(record,indent=2))
if __name__=='__main__':main()
