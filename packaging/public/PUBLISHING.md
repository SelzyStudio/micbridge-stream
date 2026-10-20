# Publication is deliberately not performed

The user has not provided a GitHub owner and has explicitly prohibited uploading. Do not create a repository, push, publish a release or replace a working installation as part of preparation.

When a publisher and repository are chosen:

1. Retain LICENSE/NOTICE and component notices. Mark this as an unofficial modified Apollo/Artemis fork. Review the source kit and matching source inputs; do not publish local working folders, private signing material, certificates, pairings, recordings or raw device logs.
2. Supply `MicBridge-Stream-1.0.0-rc1-Corresponding-Source.zip` with equivalent access from the same release page as the binary assets. Keep it available for users receiving or interacting remotely with this version. The smaller GitHub source kit intentionally excludes the large input archives.
3. Replace the null source URL in PUBLICATION.json and the host's `assets/web/assets/micbridge-source.json` with the exact HTTPS source asset URL. The Android notice identifies the matching versioned source asset. Update documentation with the real repository URL. No placeholder account or upstream binary-update feed should be substituted.
4. Obtain genuine Authenticode signing if desired, sign and timestamp the final installer, then regenerate hashes and rerun scanning. Android uses the existing newly generated release key; retain it privately with a separate protected backup. The key is not in either public ZIP.
5. Perform the documented first-install test on a separate physical Windows PC and Android phone. Confirm microphone input, stream playback without noise, defaults/permissions, network handoff and installer lifecycle. Preserve the personal working installations used by the original task.
6. Regenerate SHA256SUMS and attach the exact validation receipt. State tested scope and known limits. Public publication requires a later explicit user instruction.

Collected POM license fields can be inherited or absent; full notices, source JARs and additional upstream texts are also included. An artifact inventory is not legal certification or exhaustive vulnerability analysis. OpenSSL source SHA256 and MSYS2 recipe checksums were checked; detached signatures were archived without claiming independent PGP verification.
