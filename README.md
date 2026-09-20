# MediaHub-android v0.0.0

project WHY(Web Hub Yard) MediaHub-android

> MediaHub is encrypted media streaming service for users who want to share data with others

## Usage

- Android client optimized for convenient and secure media streaming.
- `Clear Cache` if recent server contents or changes are not reflecting.
- Enable `No TLS Check` option when connecting to servers with self-signed certificates.

## Auto Login

- Enable `Auto Login` during manual login to register and save credentials.
- Leave the password field empty and login to execute auto-login.
- Secured via hardware-backed Keystore (TEE) and biometric authentication.

## Caching

- Caches encrypted metadata and thumbnails on disk with automatic management.
- Prefetches the next image in memory during idle time for instant viewing.
- Stores only encrypted server ciphertext on disk (never saves plaintext).

## Build Executable

```bash
gradlew.bat clean
gradlew.bat [assembleRelease|assembleDebug]
cd android/app/build/outputs/apk/debug
```
