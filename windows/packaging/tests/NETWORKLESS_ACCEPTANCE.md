# Internet-blocked Windows acceptance gate

Gate rule: **NOT PASSED** until `acceptance.json` from the exact current installer
in a real network-disabled Windows Sandbox says `passed: true` and is reviewed.
Unit tests, local models and browser request interception are not substitutes.

Current recorded result: final r6, attempt-06, **PASS** on 2026-09-22 23:18 KST.
See `../../POSTBOOT_OFFLINE_VERIFICATION_2026-09-22.md` for the bound installer hash,
286 regression tests, 15 account/layout and 18 interpretation/media browser checks,
raw evidence hashes, timings and deliberately unclaimed physical-device scope.
An altered installer requires a new run; this result is not a standing exemption.

The user has rebooted; HypervisorPresent=true and Sandbox now runs. Real
network-disabled attempts have been executed. The old shared-native installer failed
with a missing MSVC/OpenMP DLL; it is not an accepted offline package. Follow
`../../POSTBOOT_OFFLINE_VERIFICATION_2026-09-22.md` for the replacement static
native installer and the fresh-VM result. Do not reboot automatically.
Preparation may use internet; the acceptance guest may not.

## Prepare, without changing Windows

From `source`, run `prepare-networkless-sandbox.ps1 -Installer <new EXE>
-OutputDir <absolute fresh path under source/.run> -JavaLauncher <matching JDK/bin/java.exe>
-NodeExecutable <node.exe> -PlaywrightModules <node_modules>`. The two required
Playwright packages (`playwright`, `playwright-core`) and Node are test-only tools,
copied into the read-only kit; no tool or browser is downloaded inside the guest.
It creates a `.wsb` with
Networking disabled and two read-only mappings: installer directory and test
tools. Only the dedicated test-results directory is writable. No real workspace,
passwords, personal files or repository-wide mapping is shared with the guest.
The default guest uses 10 GiB; `-MemoryInMB 8192` selects 8 GiB.
Camera/microphone/clipboard/vGPU are disabled. Browser media uses synthetic tracks.
The engine probe explicitly selects CPU. Browser interpretation uses the installed
application's automatic backend: its selected executable name is not proof of
physical GPU acceleration or actual layer offload.

## Automated checks inside the guest

1. Refuse to run outside `WDAGUtilityAccount`.
2. Assert no active adapters/default routes, and failed public TCP connections.
3. Verify the exact installer SHA-256; install in a fresh guest-local directory.
4. Verify all installed offline-bundle hashes and the local license-viewer hash.
5. Create fresh test accounts using a test-only helper and the real account store.
   jpackage omits native java.exe: use an isolated copy of the installed JVM plus
   the exact matching JDK launcher, checked by version and hash. Never change the
   installed runtime or claim that this helper is required for end-user startup.
   Run production offline bundle preparation using only installed model files.
6. Execute real installed CPU STT → translation → TTS for en/ko/ja/zh-CN with
   synthetic speech; preserve generated audio, text and elapsed time.
7. Start the actual installed EXE, check loopback readiness and the local meeting
   page, then execute the guest Edge account/navigation/license and four-language
   interpretation browser scenarios. Test-only Java does not launch the host.
8. Relaunch the installed EXE; uninstall only inside the disposable guest and
   verify the fresh workspace configuration/account hashes are preserved.
9. Assert the guest is still network-disabled. Report every failure, never only
   the last successful check. Stop only the owned test host process tree.

Review `results/acceptance.json`, `install.log`, `payload.json` and
`engine/engine-results.json`, `browser/account-browser.json`,
`browser/inference-results.json`, screenshots and `uninstall.log`.
The test kit is not part of the user installer.
Check the acceptance start/end timestamps and installer SHA against the actual
run; a stale or missing result is not a pass. The guest exits nonzero on failure.

## Synthetic evidence-validator regression tests

`test_networkless_evidence.py` exercises manifest/hash rejection and mocked
Windows route/adapter-query failures. It includes positive controls and checks
the specific expected failure, so an unavailable helper cannot masquerade as a
successful negative test. Query errors fail closed; an empty/duplicate/unsafe
manifest cannot pass, and CLI failure replaces stale successful JSON evidence.
These tests do not install Windows, block the host internet, or validate the
application inside a VM. Keep their results separate from guest acceptance.

## Additional gates (not implied by the automated probe)

- Human first-run administrator GUI / readable Korean installer / local license links.
- Interrupted setup recovery and manual UI workflows not covered by fixtures.
- A LAN without a WAN uplink, at least two real devices, CA trust and private
  firewall rules: Sandbox Networking=Disable cannot validate external LAN peers.
- Physical microphone/camera/screen sharing, human language-quality UAT,
  Radeon/Intel/NVIDIA GPU measurements and low-latency acceptance.

Do not label the product “internet-blocked Windows verified” from a successful
build or this procedure alone. The existing latency/Chinese ASR/unsigned-preview
limitations remain until independently resolved.
