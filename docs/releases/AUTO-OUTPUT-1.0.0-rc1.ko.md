# Selzy Studio — MicBridge Auto Output 1.0.0-rc1

원격 연결 때 Windows 기본 출력을 **Steam Streaming Speakers**로 바꾸고, 최종 연결 종료 후 2초 유예를 거쳐 연결 전 출력으로 복원하는 선택적 **PC용 추가 기능**입니다. 출력 역할별로 원래 장치를 보관하며 짧은 재연결에서는 Steam 출력을 유지합니다.

## 다운로드·설치

- **MicBridge-AutoOutput-1.0.0-rc1-windows-x64.zip**: 실행 파일·Install.ps1·Disable.ps1·전체 소스·한국어/영어 안내·GPL-3.0 고지. ZIP 전체를 풀고 README.ko.md를 확인한 뒤, 스트리밍을 종료하고 일반 사용자 PowerShell에서 Install.ps1을 실행하세요.
- **MicBridge-AutoOutput-1.0.0-rc1-Source.zip**: 대응 소스와 자체 빌드·가상 검사 스크립트.
- **PACKAGE.json·VALIDATION.json·SHA256SUMS.txt**: 파일 식별·검증 범위·체크섬.

**APK·Apollo 호스트·마이크 추가 기능은 이번에 변경하지 않았습니다.** 처음 설치하는 경우 [기본 APK·호스트](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1)와 공식 Steam 오디오 드라이버가 필요합니다. 입력 마이크 자동 전환은 별도의 [Auto Input 릴리스](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-input-v1.0.0-rc1)를 사용합니다.

## 이번 검토의 보완

Apollo가 출력 역할을 순서대로 복원할 때 일부 변화를 수동 선택으로 오인하던 경우와, 새 기준 저장 실패 시 복원 기록이 먼저 삭제되던 경우를 재현해 수정했습니다. 가상 출력 회귀 검사 **39개**를 통과했습니다.

이전 구현은 실제 Windows 출력 전환·복원·입력 보존·짧은 재연결 검사를 통과했고 사용자가 원격 연결 정상 동작을 확인했습니다. 공개 후보에서는 위 예외를 수정하고 읽기 전용 Windows 장치 조회와 Defender 검사를 수행했습니다. **현재 정상 설치는 교체하지 않았으며 수정 후 후보의 실제 휴대폰·전체 설치/제거 실기는 반복하지 않았습니다.** 다른 PC·폰의 실기도 미검증이므로 시험판으로 배포합니다.

Windows x64·.NET Framework 4.x와 지원하는 Apollo 로그가 필요합니다. **실행 파일·스크립트는 Authenticode 미서명**입니다. Windows 경고 가능성이 있으며 검사 결과는 모든 위협의 부재를 보장하지 않습니다.

출력 역할이 서로 다른 경우 Apollo 지연 정리를 위한 35초 감시 구간이 있습니다. 이 동안 원래 Console 장치로 직접 바꾸는 동작은 자동 정리와 구분할 수 없으므로 저장된 출력 역할로 되돌려질 수 있습니다. 복원 완료 후 수동 변경하세요. [자세한 설치·복원 안내](https://github.com/SelzyStudio/micbridge-stream/blob/auto-output-v1.0.0-rc1/sources/auto-output/README.ko.md).

배포 브랜드: **Selzy Studio** · GitHub 게시자: **selzystudio-01**.
