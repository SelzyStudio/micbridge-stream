"""Fetch pinned public source releases into the isolated candidate workspace."""
import configparser
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'distribution-work/2026-10-20-r1'
DOWNLOADS=WORK/'downloads'
SOURCES=WORK/'dependency-sources'
MANIFEST=WORK/'SOURCE-ARCHIVES.json'

def fetch(url,path,expected=None):
    path.parent.mkdir(parents=True,exist_ok=True)
    if not path.exists():
        temporary=path.with_suffix(path.suffix+'.partial')
        request=urllib.request.Request(url,headers={'User-Agent':'MicBridge-Source-Build'})
        with urllib.request.urlopen(request,timeout=60) as response, temporary.open('wb') as stream:
            while True:
                chunk=response.read(1024*1024)
                if not chunk: break
                stream.write(chunk)
        temporary.replace(path)
    digest=hashlib.sha256(path.read_bytes()).hexdigest().upper()
    if expected and digest!=expected.upper(): raise ValueError('Source archive hash mismatch: '+path.name)
    return dict(file=path.relative_to(WORK).as_posix(),url=url,sha256=digest,bytes=path.stat().st_size,
                officialExpectedSha256=expected.upper() if expected else None)

def extract(archive,destination):
    destination.mkdir(parents=True,exist_ok=True)
    with tarfile.open(archive,'r:*') as bundle:
        members=bundle.getmembers()
        top=members[0].name.split('/')[0]
        for member in members:
            parts=Path(member.name).parts
            if not parts or parts[0]!=top or '..' in parts or Path(member.name).is_absolute():
                raise ValueError('Unsafe source archive path')
            relative=Path(*parts[1:])
            if not relative.parts: continue
            target=destination/relative
            if not target.resolve().is_relative_to(destination.resolve()): raise ValueError('Source path escapes workspace')
            if member.isdir(): target.mkdir(parents=True,exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True,exist_ok=True)
                data=bundle.extractfile(member).read()
                # GitHub archives create empty submodule directories. Existence is not extraction proof.
                # Avoid rewriting matching source files that another isolated build may be reading.
                if not target.exists() or target.read_bytes()!=data:
                    target.write_bytes(data)
            elif member.issym() or member.islnk():
                # Keep archive originals as corresponding source. These links are not needed for native library builds.
                continue
            else: raise ValueError('Unexpected archive special file')

def gh(endpoint):
    return json.loads(subprocess.check_output(['gh','api',endpoint],text=True))

def main():
    DOWNLOADS.mkdir(parents=True,exist_ok=True)
    SOURCES.mkdir(parents=True,exist_ok=True)
    records=json.loads(MANIFEST.read_text()) if MANIFEST.exists() else []
    def save(record):
        if not any(r['file']==record['file'] for r in records): records.append(record)
        MANIFEST.write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8')
    openssl_url='https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz'
    save(fetch(openssl_url,DOWNLOADS/'openssl-3.5.9.tar.gz','603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'))
    extract(DOWNLOADS/'openssl-3.5.9.tar.gz',SOURCES/'openssl-3.5.9')
    save(fetch('https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz',DOWNLOADS/'opus-1.6.1.tar.gz',
               '6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1'))
    extract(DOWNLOADS/'opus-1.6.1.tar.gz',SOURCES/'opus-1.6.1')
    commit='c0a89a80c399dcdd25fa7357d5799c0c7988d547'
    recipe=DOWNLOADS/'build-deps-c0a89a8.tar.gz'
    record=fetch('https://codeload.github.com/LizardByte/build-deps/tar.gz/'+commit,recipe)
    record['gitCommit']=commit
    save(record)
    extract(recipe,SOURCES/'build-deps-recipe')
    tree=gh('repos/LizardByte/build-deps/git/trees/'+commit+'?recursive=1')['tree']
    modules=configparser.ConfigParser()
    modules.read(SOURCES/'build-deps-recipe/.gitmodules',encoding='utf-8')
    urls={modules[s]['path']:modules[s]['url'] for s in modules.sections()}
    for item in tree:
        if item['mode']!='160000': continue
        path=item['path']; url=urls[path].removesuffix('.git')
        if not url.startswith('https://github.com/'): raise ValueError('Unexpected source host')
        repository=url.removeprefix('https://github.com/')
        target=DOWNLOADS/(path.split('/')[-1]+'-'+item['sha'][:12]+'.tar.gz')
        source_record=fetch('https://codeload.github.com/'+repository+'/tar.gz/'+item['sha'],target)
        source_record.update(gitCommit=item['sha'],repository=url,recipePath=path)
        save(source_record)
        extract(target,SOURCES/'build-deps-recipe'/path)
    print(json.dumps(dict(status='pinned-sources-fetched',archives=len(records),sources=str(SOURCES)),indent=2))

if __name__=='__main__': main()
