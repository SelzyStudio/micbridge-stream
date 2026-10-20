"""Prepare an empty rebuild workspace from the extracted companion source asset.
Does not install toolchains, drivers, services, sign APKs or publish anything.
"""
import argparse,hashlib,json,shutil,tarfile
from pathlib import Path
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def extract(archive,destination,strip=True):
    destination.mkdir(parents=True,exist_ok=True)
    with tarfile.open(archive,'r:*') as z:
        for m in z.getmembers():
            parts=Path(m.name).parts
            if not parts or '..' in parts or Path(m.name).is_absolute():raise ValueError('Unsafe tar path')
            if strip:parts=parts[1:]
            if not parts:continue
            p=destination.joinpath(*parts)
            if not p.resolve().is_relative_to(destination.resolve()):raise ValueError('Source path escapes workspace')
            if m.isdir():p.mkdir(parents=True,exist_ok=True)
            elif m.isfile():p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.extractfile(m).read())
            # Archive originals retain links. Buildable native trees do not require these links.
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--destination',type=Path,required=True);a=ap.parse_args()
    kit=Path(__file__).resolve().parents[1];dest=a.destination.resolve()
    if dest.exists():raise ValueError('Destination must be a new directory')
    sources=json.loads((kit/'SOURCE-MANIFEST.json').read_text(encoding='utf-8'))
    for x in sources:
        if sha(kit/x['path'])!=x['sha256']:raise ValueError('Source manifest mismatch: '+x['path'])
    inputs=json.loads((kit/'SOURCE-INPUTS.json').read_text(encoding='utf-8'))
    for x in inputs:
        if sha(kit/'inputs'/x.get('storedPath',x['path']))!=x['sha256']:raise ValueError('Input hash mismatch: '+x['path'])
    work=dest/'distribution-work/2026-10-20-r1';work.mkdir(parents=True)
    for name in ('scripts','packaging'):shutil.copytree(kit/name,dest/name)
    shutil.copytree(kit/'sources',work/'checkouts')
    for x in inputs:
        p=work/x['path'];p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(kit/'inputs'/x.get('storedPath',x['path']),p)
    for p in (kit/'dependency-locks').glob('*.json'):shutil.copy2(p,work/p.name)
    wrapper=work/'checkouts/artemis/gradle/wrapper/gradle-wrapper.jar';wrapper.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(work/'gradle-wrapper.jar',wrapper)
    for x in json.loads((work/'SOURCE-ARCHIVES.json').read_text(encoding='utf-8')):
        if x.get('recipePath'):target=work/'dependency-sources/build-deps-recipe'/x['recipePath']
        elif x['file'].endswith('openssl-3.5.9.tar.gz'):target=work/'dependency-sources/openssl-3.5.9'
        elif x['file'].endswith('opus-1.6.1.tar.gz'):target=work/'dependency-sources/opus-1.6.1'
        else:target=work/'dependency-sources/build-deps-recipe'
        extract(work/x['file'],target)
    (work/'logs').mkdir()
    (dest/'audit/audio-noise-2026-10-20').mkdir(parents=True)
    suites=json.loads((kit/'build-test-suites.json').read_text(encoding='utf-8'))
    (dest/'audit/audio-noise-2026-10-20/regression-summary.json').write_text(json.dumps(dict(suites=[dict(name=x) for x in suites])))
    print('Verified source and input hashes; rebuild tree prepared. Add the documented toolchains, then follow docs/BUILDING.md. No binaries installed.')
if __name__=='__main__':main()
