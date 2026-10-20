"""Use freshly built per-ABI native libraries only in the isolated Android checkout."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'distribution-work/2026-10-20-r1'
JNI=WORK/'checkouts/artemis/app/src/main/jni/moonlight-core'

def main():
    records=[]
    for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64'):
        prefix=WORK/'native/android'/abi
        ssl=prefix/'openssl'
        opus=prefix/'opus'
        for name,source_root,component in [('libcrypto.a',ssl,'openssl'),('libssl.a',ssl,'openssl'),('libopus.a',opus,'libopus')]:
            sources=list(source_root.rglob(name))
            if len(sources)!=1: raise RuntimeError('Native build incomplete: '+abi+'/'+name)
            source=sources[0]
            destination=JNI/component/abi/name
            destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(source,destination)
            records.append(dict(abi=abi,component=component,file=name,bytes=source.stat().st_size,
                sha256=hashlib.sha256(source.read_bytes()).hexdigest().upper()))
        ssl_include=JNI/'openssl'/abi/'include'
        opus_include=JNI/'libopus'/abi/'include'
        shutil.copytree(ssl/'include',ssl_include,dirs_exist_ok=True)
        shutil.copytree(opus/'include/opus',opus_include,dirs_exist_ok=True)
    for component in ('openssl','libopus'):
        path=JNI/component/'Android.mk'
        text=path.read_text().replace('$(LOCAL_PATH)/include','$(LOCAL_PATH)/$(TARGET_ARCH_ABI)/include')
        path.write_text(text,encoding='utf-8',newline='\n')
    (WORK/'ANDROID-NATIVE-BUILDS.json').write_text(json.dumps(dict(openssl='3.5.9',opus='1.6.1',
        api=21,perAbiHeaders=True,libraries=records,existingAppChanged=False),indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(status='new-source-built-native-libraries-selected',abis=4,libraries=len(records),existingAppChanged=False),indent=2))

if __name__=='__main__': main()
