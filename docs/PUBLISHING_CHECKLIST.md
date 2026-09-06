# Release checklist

1. Confirm the version name/code and public repository destination.
2. Run the repository quality gates and inspect the public source snapshot.
3. Build the distributable Alpha variant with R8 and verify packaged licenses.
4. Sign with the existing release key, then verify signature, alignment and SHA-256.
5. Install that exact APK over the previous release on the available emulator and
   exercise broadcast, transcript, stop/restart and language-isolation paths.
6. Record measured results and untested environments in [TEST_REPORT.md](TEST_REPORT.md).
7. Check Korean/English instructions, screenshots, links and release asset names.
8. Commit public product files, require a successful source CI run, and attach the
   signed APK and checksum to a versioned GitHub prerelease.
9. Verify the published download matches the tested SHA-256.

Keep signing keys, credentials, raw diagnostics, private coordination records,
downloaded model caches and build intermediates outside Git. A passing emulator
test does not prove physical-device performance or unattended field stability.
