# Selzy Studio — MicBridge Auto Input 1.0.0-rc1

Apollo에 원격 연결할 때 Windows 기본 **입력 마이크**를 Steam Streaming Microphone으로 바꾸고, 마지막 세션 종료 후 2초 동안 재연결되지 않으면 연결 전 장치로 복원하는 PC용 선택적 보조 프로그램입니다. Console·Multimedia·Communications의 원래 장치를 각각 보관합니다. APK 업데이트는 필요하지 않습니다.

## 다운로드와 조건

[추가 기능 ZIP과 대응 소스](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-input-v1.0.0-rc1). 기본 [Android APK·Windows 호스트](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1)는 기존 배포를 사용합니다. 이 ZIP은 호스트 설치 파일을 대체하지 않습니다.

- Windows x64, .NET Framework 4.x, 현재 사용자로 실행되는 Apollo와 읽을 수 있는 sunshine.log가 필요합니다. 검증 환경은 Windows 11 x64이며 다른 PC의 실기 검증은 남아 있습니다.
- 공식 Steam 오디오 드라이버를 별도로 설치하고, 활성 입력 장치 이름에 `Steam Streaming Microphone`이 포함되어 있어야 합니다. 이름이 일치하는 활성 입력이 정확히 하나여야 합니다.
- Apollo 로그 형식은 이 저장소의 마이크 지원판에 맞췄습니다. Apollo 업데이트 후 호환 확인이 필요합니다. 동시에 여러 sunshine 프로세스가 실행되거나 로그가 64MiB를 넘으면 정상 감지를 하지 못하며 복원 경로로 들어갑니다.
- **실행 파일과 PowerShell 스크립트는 Authenticode 미서명입니다.** Windows 경고가 나올 수 있습니다. Defender 탐지 없음은 서명이나 안전 보장을 대신하지 않습니다. 보호 기능을 끄거나 예외를 추가할 필요는 없습니다.

## 설치

1. 원격 스트리밍 연결을 종료하고 ZIP 전체를 풉니다. Windows 사용자 계정으로 PowerShell을 엽니다. 관리자 권한은 필요하지 않습니다.
2. 해당 폴더에서 `Get-FileHash .\MicBridge-AutoInput.exe -Algorithm SHA256` 결과를 릴리스 체크섬과 비교합니다.
3. 설치 전 점검: `& .\Install.ps1 -ValidateOnly`
4. 설치: `& .\Install.ps1`

사용자 지정 로그 경로는 `& .\Install.ps1 -ApolloLog 'D:\Apollo\config\sunshine.log'`처럼 지정합니다. 스크립트 정책이나 다운로드 차단으로 실행이 거부되면 정책을 자동 변경하지 않습니다. 파일 출처와 내용을 확인한 뒤 해당 파일의 차단 해제 또는 조직의 허용 절차를 따르세요.

`%LOCALAPPDATA%\MicBridge\AutoInput`에 설치하고 HKCU 시작프로그램 `MicBridge-AutoInput`을 등록합니다. 이미 설치되어 있거나 같은 이름의 시작 항목이 있으면 덮어쓰지 않고 중단합니다. 기존 사용자는 이 공개 파일을 다시 설치할 필요가 없습니다. 버전 교체는 기존 폴더의 Disable.ps1로 해제한 뒤, 복원이 끝나고 `original-input.json`이 없는 것을 확인하여 그 폴더를 별도로 보관한 후 새로 설치하세요. 복원이 대기 중인 폴더는 옮기거나 삭제하지 마세요.

## 동작·복구·개인정보

연결 상태는 250ms 간격으로 검사하고, 장치 가용성과 수동 선택은 1초 간격으로 관측합니다. 잠깐의 네트워크 전환은 2초 유예로 원래 입력을 잃지 않게 합니다. 앱의 백그라운드 미디어 정지만으로는 복원하지 않습니다. 앱 안에서 특정 마이크를 고정한 경우 해당 앱의 설정은 따로 바꿔야 합니다.

장치 변경 전에 설치 폴더의 `original-input.json`에 복원 정보를 저장합니다. 원래 마이크가 분리되면 기록을 보관하고 재연결을 기다립니다. 강제 종료 직후 즉시 복원을 보장하지 않으며 다음 실행에서 남은 기록으로 복구합니다. 1초 사이에 끝나는 짧은 드라이버 소실·재등장은 사용자 변경과 완전히 구분하지 못할 수 있습니다.

`status.json`, `switch.log`, 복원 파일에는 로컬 장치 이름·ID·경로·프로세스 정보가 포함될 수 있습니다. 프로그램은 음성을 녹음하거나 네트워크로 전송하지 않습니다. 진단 파일을 공개할 때는 개인 정보를 먼저 지우세요. 스피커 출력·볼륨·음소거·Apollo 설정·APK는 변경하지 않습니다.

자동 전환 해제와 복원: **설치 폴더**의 `Disable.ps1`을 실행하세요. 파일은 복구를 위해 보존합니다. 원래 장치가 없으면 다시 연결한 뒤 재실행하세요. 정상 복원이 끝나고 복원 파일이 없어진 뒤 폴더를 직접 삭제할 수 있습니다.

## 소스·빌드·검증

`Build.ps1`은 포함된 CoreAudio.cs·MicrophoneAutoSwitch.cs·Tests.cs를 컴파일하여 `build`에 새 실행 파일을 만들고 회귀 검사 39개를 수행합니다. `tests\Run.ps1`은 별도의 경계 조건 검사를 실행합니다. 두 명령은 가상 입력 어댑터만 사용하므로 실제 오디오 설정을 바꾸지 않습니다. .NET Framework 컴파일러의 PE 식별자 때문에 재빌드 해시는 배포 바이너리와 다를 수 있습니다.

최초 구현은 실제 휴대폰 연결·종료에서 확인했습니다. 최종 보완판은 회귀 39개·경계 조건 검사·실제 Windows 입력 3역할 전환과 복원·출력 3역할 보존을 확인했으며 Defender 검사에서 탐지가 없었습니다. 최종 보완 후 휴대폰 연결 시험은 반복하지 않았습니다. 장치 분리·재연결과 빠른 호스트 재시작 예외는 가상 어댑터·로그로 검증했습니다. 새 배포 스크립트는 구문·사전 점검을 별도로 검사하며, 현재 설치를 다시 설치하지 않았습니다. 자세한 범위는 VALIDATION.json을 확인하세요.

[English](README.en.md) · [GPL-3.0](LICENSE) · [출처·고지](NOTICE.md) · [문제 제보](https://github.com/SelzyStudio/micbridge-stream/issues)
