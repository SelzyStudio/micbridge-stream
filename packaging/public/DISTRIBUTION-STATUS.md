# 공개 후보 준비 결과 — 1.0.0-rc1

현재 정상 설치를 교체하지 않고 별도 작업 폴더에서 만든 **공개 후보판**입니다. GitHub 업로드는 하지 않았습니다.

완료:

- MicBridge Stream 이름, 별도 앱 ID `app.micbridge.stream`, 디버그와 구분되는 release 서명. 향후 업데이트용 비공개 키는 배포 파일에 포함하지 않았습니다.
- Android 4개 ABI용 OpenSSL 3.5.9·Opus 1.6.1 소스 재빌드. Windows OpenSSL와 FFmpeg/x264/x265/SVT-AV1 묶음 소스 재빌드. 코덱 원본 빌드 레시피와 정확한 커밋 복구.
- 기존 설치와 분리한 MicBridge Host 설치 파일·포터블 ZIP. 설정 보존, 앱 파일 백업, 경로 보호, 다른 Apollo 프로세스에 영향을 주지 않는 제거 검사 보완.
- 소스 스냅샷·재귀 하위 모듈, 빌드 스크립트, 소스 아카이브·체크섬, MSYS2 라이브러리 입력, Android 전이 의존성 86개 목록과 공개 소스/고지 수집. API 호환성 코드의 GPL+Classpath 소스도 포함.
- Android 설정에서 소스·라이선스·개인정보 안내 표시. 호스트 웹 UI의 소스 안내 및 원본 프로젝트 업데이트 안내 분리.
- 선택한 Android 회귀 422개·PC 회귀 22개 통과. 전체 Android 테스트를 모두 통과했다고 주장하지 않습니다(원본에 알려진 별도 실패가 있습니다).
- Android 6(4KB)·Android 15(16KB) 가상 기기에서 설치, 같은 서명 재설치, 실행, 라이선스 화면 확인. 실제 PlatformCrypto의 AES-GCM/CBC, 변조 거부, RNG/TLS, Opus 인코딩·디코딩 실행. 64비트 ELF와 APK ZIP 정렬 검사 통과.
- Windows 현재 OS의 **격리 폴더 /PORTABLE 모드**에서 설치·해시·재설치·설정 보존·백업·제거 시험 통과. 현재 Apollo 실행 파일 해시와 MicBridge 등록 상태 유지 확인.

공개 전에 남은 사항:

1. 사용자가 정할 GitHub 계정·저장소와 실제 소스 배포 URL. `PUBLICATION.json`은 비어 있습니다. 호스트의 `assets/web/assets/micbridge-source.json`에도 정확한 소스 ZIP 링크를 넣어야 합니다. 공개판을 사용한다고 원본 프로젝트 링크가 수정 소스 제공을 대신하지 않습니다.
2. Windows Authenticode 인증서 서명은 없습니다. 공인 코드 서명에는 실제 배포자 인증서가 필요합니다. Android APK 서명은 완료됐습니다. 자체 서명 인증서를 만들어 Windows 신뢰 서명을 완료한 것처럼 표시하지 않습니다.
3. 다른 물리 Windows PC와 실제 Android폰의 최초 페어링·마이크·음성 입력·소리·네트워크 전환 최종 시험. 가상 기기의 마이크/오디오는 꺼 두었으므로 실제 음향·GPU 검증은 대체하지 않습니다. 이 후보판을 현재 폴드/PC에 적용하지 않았습니다.

Steam 오디오 드라이버는 Valve의 별도 배포물이므로 포함하지 않았습니다. 공식 Steam에서 설치하는 안내와 읽기 전용 준비 상태 검사 도구를 제공했습니다. 다른 PC의 첫 설정에는 드라이버·권한·방화벽·페어링이 필요합니다.

최종 백신 검사 결과는 같은 폴더의 `VALIDATION.json` 및 SHA256SUMS를 확인하세요. 탐지 0은 그 검사 시점의 결과이며 모든 악성 동작·취약점 부재를 보장하지 않습니다. SmartScreen 평판 경고는 별개입니다.
