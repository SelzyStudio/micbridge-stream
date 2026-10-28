# Selzy Studio — MicBridge Auto Input 1.0.0-rc1

원격 연결 때 Windows 기본 입력을 **Steam Streaming Microphone**으로 바꾸고, 마지막 세션 종료 후 원래 입력으로 돌려주는 선택적 **PC용 추가 기능**입니다. 역할별 원래 장치를 보관하며, 잠깐의 재연결에는 2초 유예를 적용합니다.

## 설치 파일

- **MicBridge-AutoInput-1.0.0-rc1-windows-x64.zip**: 실행 파일, Install.ps1·Disable.ps1, 전체 소스, GPL-3.0·출처 고지, 한국어·영어 안내 포함. ZIP을 전부 풀고 README.ko.md를 읽은 뒤 일반 사용자 PowerShell에서 Install.ps1을 실행하세요. 기존 설치는 덮어쓰지 않습니다.
- **MicBridge-AutoInput-1.0.0-rc1-Source.zip**: 이 추가 기능의 대응 소스와 빌드·검사 스크립트. .NET Framework x64 컴파일러로 빌드할 수 있습니다.
- **SHA256SUMS.txt**, **VALIDATION.json**: 다운로드 무결성과 검증 범위.

**APK와 Apollo 호스트는 이번에 변경하지 않았습니다.** 처음 설치하는 분은 [기존 Android APK·Windows 호스트 릴리스](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1)도 필요합니다. 이미 이 보조 프로그램으로 정상 동작하는 PC는 다시 설치할 필요가 없습니다.

## 조건·검증 범위

Windows x64, .NET Framework 4.x, 기존 Apollo와 Steam Streaming Microphone 드라이버가 필요합니다. 설치 프로그램은 사용자 계정의 시작프로그램만 등록하며 관리자 권한이 필요하지 않습니다. 스피커 출력이나 볼륨·APK·Apollo 설정은 바꾸지 않습니다.

회귀 39개와 경계 조건 검사, 실제 Windows 입력 전환·복원과 출력 보존을 확인했습니다. 최종 실행 파일은 설치된 검증본과 동일하며 Defender에서 탐지가 없었습니다. 최초 구현의 실제 휴대폰 시험은 통과했지만, 최종 보완 뒤 휴대폰 시험은 반복하지 않았습니다. 새 배포 스크립트의 전체 설치·업그레이드·제거 실기는 미검증이며 다른 PC·폰의 실기도 남아 있습니다.

**실행 파일과 스크립트는 Authenticode 미서명인 시험판**입니다. Windows 경고 가능성이 있습니다. 검사 결과는 모든 위협의 부재를 보장하지 않습니다. 현재 Apollo 로그 형식과 비공개 Windows COM 인터페이스에 의존하며, 장치 분리·강제 종료 관련 한계는 포함된 안내를 확인하세요.

배포 브랜드: **Selzy Studio** · 게시 계정: **selzystudio-01** · [소스·상세 안내](../../sources/auto-input/README.ko.md).
