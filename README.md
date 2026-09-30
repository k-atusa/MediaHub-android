# MediaHub-android v1.5.3

project WHY(Web Hub Yard) MediaHub-android

> MediaHub is encrypted media streaming service for users who want to share data with others

## Usage

- 미디어허브 서버에 편리하고 안전하게 접속해 파일을 감상할 수 있는 안드로이드 앱입니다. Android client optimized for convenient and secure media streaming.
- 서버 측의 최신 내용 변동이 표시되지 않는다면 캐시를 지우십시오. `Clear Cache` if recent server contents or changes are not reflecting.
- 사설 인증서로 서명된 서버에 접속할 때는 TLS 무시 옵션을 사용하십시오. Enable `No TLS Check` option when connecting to servers with self-signed certificates.

## Auto Login

- 수동 로그인을 하며 로그인 정보를 저장하면 자동로그인을 할 수 있습니다. Enable `Save Login` during manual login to register and save credentials.
- 비밀번호 창을 비우고 로그인 버튼을 누르면 자동로그인 됩니다. Leave the password field empty and login to execute auto-login.
- 자동로그인 정보는 안드로이드 하드웨어와 생체인증으로 보호됩니다. Secured via hardware-backed Keystore (TEE) and biometric authentication.

## Caching

- 뷰에 필요한 정보와 썸네일을 암호화된 상태로 캐싱하며 자동으로 관리합니다. Caches encrypted metadata and thumbnails on disk with automatic management.
- 이미지의 경우 다음 사진을 자동으로 미리 불러옵니다. Prefetches the next image in memory during idle time for instant viewing.
- 파일 형태로 저장되는 캐시는 항상 암호화된 형태로 보호됩니다. Stores only encrypted server ciphertext on disk (never saves plaintext).

## Build Executable

```bash
gradlew.bat clean
gradlew.bat [assembleRelease|assembleDebug]
cd android/app/build/outputs/apk/debug
```
