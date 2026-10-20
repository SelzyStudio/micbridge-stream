MicBridge Host 1.0.0-rc1 — 공개 배포 후보 / Windows 10·11 x64

Apollo/Sunshine 기반의 비공식 수정판입니다. 현재 사용 중인 Apollo를 교체하려는 설치 파일이 아닙니다.
기본 설치: %LOCALAPPDATA%\Programs\MicBridge-Host
설치 파일은 사용자 앱 파일·시작 메뉴만 만듭니다. 서비스·드라이버·방화벽·기본 오디오 장치를 변경하지 않습니다.
현재 Apollo/Sunshine과 동시에 기본 포트로 실행할 수 없습니다. 기존 PC에서는 재설치하지 마세요.

새 PC에서 사용
1. setup.exe를 실행하거나 portable.zip 전체를 풉니다. Check-Requirements.cmd로 준비 상태를 확인합니다.
2. 공식 Steam(https://store.steampowered.com/about/)에 포함된 서명된 Steam Streaming Microphone 오디오 드라이버가 필요합니다.
   Steam 설치 폴더의 drivers\audio\streaming_microphone.inf 및 streaming_speakers.inf가 있는 경우,
   Windows의 관리자 ‘터미널’에서 해당 INF에 pnputil /add-driver "전체 경로.inf" /install 을 실행해 설치할 수 있습니다.
   공식 Steam 배포의 Valve 서명 파일만 사용하세요. 이 패키지에는 해당 드라이버를 포함하지 않습니다.
   장치가 없으면 마이크 전달은 사용할 수 없습니다. 설치 후 Check-Requirements.cmd로 다시 확인합니다.
3. Start-MicBridge.cmd 또는 시작 메뉴 MicBridge Host를 실행합니다. https://localhost:47990에서 새 계정을 만듭니다.
   자체 서명 인증서를 사용합니다. 로컬 주소인지 확인하고 브라우저에서 직접 진행합니다.
4. Android에 MicBridge Stream APK를 설치합니다. 범용 universal APK를 권장합니다.
   PC 추가 → PIN 페어링 → PC 관리 페이지에서 필요한 화면 보기·실행·입력 권한 부여 → Desktop 연결.
   Android 설정에서 마이크 전송을 켜고 녹음 권한을 허용합니다.
5. Windows 기본 입력: Microphone (Steam Streaming Microphone).
   Windows 기본 출력: 실제 스피커/헤드폰. Speakers (Steam Streaming Microphone)를 출력으로 선택하면 안 됩니다.
   앱 백그라운드에서는 영상·PC 소리·마이크 전송을 쉬고 복귀 시 재개합니다.
6. Windows 방화벽은 새 호스트 경로에 필요한 통신만 직접 허용합니다.
   기본 TCP 47984/47989/48010, UDP 47998/47999/48000/48001. 관리용 TCP 47990은 인터넷에 공개하지 않습니다.
   LTE는 도달 가능한 주소와 적절한 라우팅/VPN 또는 공유기 설정이 별도로 필요합니다. 타인의 PC 주소나 포워딩 규칙은 포함하지 않습니다.

기본 설정
stream_mic = enabled
mic_backend = steam_streaming_microphone
install_steam_audio_drivers = disabled
virtual_sink = Steam Streaming Speakers
기존 config는 설치·업데이트·제거 과정에서 보존합니다. 덮어쓸 앱 파일은 package-backups에 백업합니다.
제거 후 config와 package-backups는 개인정보가 있으므로 소유자가 필요에 따라 삭제해야 합니다.
실행 중인 이 패키지는 종료한 뒤 제거하세요. 다른 경로의 Apollo 때문에 제거를 막지 않습니다.

배포와 출처
대응 소스: MicBridge-Stream-1.0.0-rc1-Corresponding-Source.zip. 바이너리와 동일한 배포 페이지에서 함께 제공해야 합니다.
라이선스 전문은 licenses, 변경 정보는 SOURCE.txt, 파일 해시는 payload-manifest.json에 있습니다.
Windows Authenticode 서명은 없습니다. SmartScreen 평판 경고가 나타날 수 있습니다.
백신 탐지를 우회하거나 보호 기능을 끄지 마세요. SHA256SUMS와 최종 검사 보고서로 배포본을 확인하세요.
후보판의 가상 기기·파일 설치 시험은 새 물리 PC의 GPU·오디오·전체 원격 연결 검증을 대신하지 않습니다.
