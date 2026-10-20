"""Unpack pinned build tools locally; no package-manager or system installation."""
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'distribution-work/2026-10-20-r1'
PACKAGES=[
 ('make','https://mirror.msys2.org/msys/x86_64/make-4.4.1-3-x86_64.pkg.tar.zst','af0bdba17f06fe037f0194069adaa31a8fe45f1a11381501896aea1fae37bd5d'),
 ('nasm','https://mirror.msys2.org/mingw/ucrt64/mingw-w64-ucrt-x86_64-nasm-3.02-1-any.pkg.tar.zst','e60cf678bedee3d6c9a9264f8dec5897520e5ff7e3ae80c2b90b2628c5f51d58')]

def main():
    records=[]
    for name,url,expected in PACKAGES:
        archive=WORK/'downloads'/url.rsplit('/',1)[1]
        if not archive.exists():
            subprocess.run([r'C:\Windows\System32\curl.exe','--fail','--location','--silent','--show-error','--proto','=https','--output',str(archive),url],check=True)
        actual=hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual!=expected: raise ValueError('Official tool package hash mismatch')
        unpack=WORK/'tools'/name
        if not unpack.exists():
            temporary=WORK/'tools'/(name+'-archive')
            temporary.mkdir(parents=True)
            subprocess.run([str(ROOT/'toolchains/packaging/7zip-26.04/7z.exe'),'x','-y','-o'+str(temporary),str(archive)],check=True,stdout=subprocess.DEVNULL)
            tar=next(temporary.glob('*.tar'))
            unpack.mkdir()
            with tarfile.open(tar) as package:
                for member in package.getmembers():
                    path=unpack/member.name
                    if not path.resolve().is_relative_to(unpack.resolve()) or '..' in Path(member.name).parts: raise ValueError('Tool package escapes workspace')
                    if member.isdir(): path.mkdir(parents=True,exist_ok=True)
                    elif member.isfile():
                        path.parent.mkdir(parents=True,exist_ok=True)
                        path.write_bytes(package.extractfile(member).read())
        records.append(dict(name=name,url=url,officialSha256=expected,systemInstalled=False))
    (WORK/'BUILD-TOOLS.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(records,indent=2))

if __name__=='__main__': main()
