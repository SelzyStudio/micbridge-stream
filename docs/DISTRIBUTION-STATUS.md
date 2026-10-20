# 1.0.0-rc1 validation and limits

Release: https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1

The exact previously inspected candidate is distributed as a prerelease. Android APK signature, application identity, 16KB alignment and native/executable payload comparison passed. Selected Android tests: 422; prior host policy tests: 22. Prior candidate Android 6/15 emulator startup and isolated Windows installer lifecycle checks passed; the release notice-only variant retains the same executable/native payloads.

The candidate package assets passed Defender with signature 1.461.112.0 and no threats detected. Scanner receipts are limited to the listed hashes and tested scope. Windows Authenticode signing and another physical PC/phone end-to-end test are not completed. Steam audio drivers are not redistributed. See ANDROID-SUPPORT and the Windows installation guide for first-use requirements.

Frozen build/source receipts may say unpublished or contain false publication flags: they describe the earlier preparation step, not the current release page. Application sources and binary assets were not changed by publication. The user's working PC and phone installation were not modified.
