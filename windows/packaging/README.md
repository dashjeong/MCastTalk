# Windows packaging

## 0.4.1 single offline installer

The new offline path is `package.ps1 -Type app-image`, `stage-offline.cjs`, then
`offline.iss` compiled by Inno Setup 6.7.3. **Do not use the legacy host-only EXE
as the offline-model distribution.** This candidate has a separate application
ID and default installation directory, preserving the user's installed 0.3.1.

Build from `source/`, after the latest Gradle installDist/tests finish. First
build/verify the static native profile described in `NATIVE_STATIC_BUILD.md`:

```powershell
windows/packaging/package.ps1 -Type app-image -Version 0.4.1 `
  -OutputDir build/windows-packaging/media-041-candidate -SkipGradle
node windows/packaging/stage-offline.cjs `
  build/windows-packaging/media-041-candidate/MCastTalk <verified-python-runtime> `
  <verified-static-native-runtime-directory>
.tools/inno673/compiler/ISCC.exe `
  /DAppImage=<absolute-app-image-directory> `
  /DOutputRoot=<absolute-output-directory> windows/packaging/offline.iss
```

The staging script refuses to overwrite a bundle. It copies pinned offline
models, llama.cpp CPU/Vulkan and whisper runtimes, a standalone Python runtime,
NumPy/sherpa-onnx, the worker and notices; SHA-256 manifests cover every file.
No model download happens during installation, startup or a meeting.
Native dependency inspection rejects VC++/OpenMP runtime DLL imports before
staging. This does not replace execution in a clean, network-disabled Windows VM.

The installer selects application and data directories. On first launch the
operator confirms local model setup; verified files are placed in the chosen
data workspace, without replacing existing operator model configurations.
The operator then chooses local-only or LAN HTTPS and creates the initial
administrator. No default credentials are supplied. LAN client CA trust and
private-subnet firewall configuration are deliberate operator actions, never
silently changed by setup. See `OFFLINE_NOTICES.md` for terms and limits.

The final artifact name is `MCastTalk-0.4.1-Offline-Preview.exe`. Exact measured
size, SHA-256 and current test status are in `../POSTBOOT_OFFLINE_VERIFICATION_2026-09-22.md`.
The exact final r6 artifact passed a network-disabled clean Windows Sandbox run.
It remains unsigned; synthetic VM checks do not certify physical-device, human
translation quality, manual-wizard or low-latency performance acceptance.

### Installation information revision (2026-09-22)

The installer has a basic program-information page and the normal Install/Cancel
confirmation. It does not treat installation as blanket license acceptance.
`Components and licenses` opens the bundled self-contained HTML viewer, with
component names, official reference links and embedded original license texts.
The same viewer is available from a Start-menu shortcut after installation.
No network access is needed to view the originals. External reference links are
optional and never fetched automatically. The application's separate voice-model
terms and server-side participation checks are unchanged.

`stage-offline.cjs` calls `build_notices.py` with the bundled Python. To update
only the installer, copy a previously verified app image to a new build directory
and run `build_notices.py --app <new-image>`, then compile `offline.iss`. Do not
overwrite an existing `legal` directory or change the previously verified image.
The notice builder rejects missing originals and unclassified JVM artifacts.

## Historical preview boundary through 0.3.1

The packaging target is a **loopback-only authenticated room/control and
original-text chat preview**, not the complete multilingual meeting release.
Current source includes first-administrator setup, ADMIN/USER/GUEST accounts,
HTTP/WebSocket authentication, login/password controls, and user/guest management
UI. A rebuilt preview contains the host, web UI, and JVM. It does **not** include
a model pack, Python worker runtime, STT/NMT/TTS host integration, SFU/video media
server, or TLS. The separate CPU STT adapter smoke result does not make inference
available in an installer.

The existing `MCastTalk-0.1.0.exe` chat-only artifact predates the account feature;
`0.2.0` adds local accounts and `0.3.0` adds operator diagnostics and participant
identity/search improvements. `0.3.1` adds local room invitation links and the
user-requested 8–128-character password policy; existing hashes remain valid.
Exact tested paths and hashes are in the root
verification records. Current source code,
successful packaging, an app-image smoke run, and a clean-machine installation
are distinct pieces of evidence.

The final single installer must also contain all approved model/runtime packs,
notices and manifests, and pass clean-machine installation and no-network
acceptance tests. A successful EXE build alone proves neither installation nor
full offline meeting operation. Clean-machine install/uninstall and the manual
Swing workspace/administrator setup flow remain untested. Current developer
artifacts are unsigned. Do not distribute this preview as the final offline product.

The subsequent 2026-09-19 installation request was fulfilled on the current PC:
0.3.0 installed per-user with Windows Installer exit code 0, registered shortcuts,
and the installed executable passed 23 real Edge scenarios. This is not a clean
Windows VM test. User completion of the workspace/admin GUI remains unverified;
the desktop automation could not expose that dialog as a targetable window.
See root `INSTALLATION_VERIFICATION_2026-09-19.md` and `START_HERE_WINDOWS.md`.

The subsequent 0.3.1 upgrade added eight-character minimum passwords and local
room invitation links. The installed executable passed 34 Edge scenarios and
preserved the existing user's four data files plus launcher settings by hash;
a verified backup was retained before upgrading. See root
`VERIFICATION_INVITATIONS_2026-09-19.md`. Despite the requested per-user build
flag and user-local installation path, the observed uninstall registration on
this PC is under HKLM. Unprivileged per-user install scope still needs a clean
environment test. This is not evidence of external-device invitation support.

The intended distribution artifact is one per-user `MCastTalk-<version>.exe` made
by the JDK `jpackage` tool. The installer contains the JVM and the Gradle
`windows:host` distribution, so the end user does not need Java or Python.
The installer chooses the application installation directory. The installed
executable then performs first-run workspace selection, defaulting to
`MCastTalkData` beside that executable. For a new workspace, the Windows user
running setup directly specifies the first administrator's username, display
name, password, and confirmation before any room server starts. There is no
default account or browser/HTTP bootstrap. Model downloads are not part of this
installation flow.

Subsequent launches reuse that workspace's accounts. The browser UI requires
login; administrators create and manage other accounts. A GUEST is an issued
account with an expiration and one assigned room, not anonymous access. Damaged
or interrupted account setup stops startup and requires operator recovery; it
must not create a replacement administrator automatically.

## Prerequisites

- The repository-portable JDK under `.tools\jdk17` (preferred automatically),
  or another JDK that contains `jpackage` (JDK 17 or newer). Set
  `MCASTTALK_JDK_HOME` to explicitly override the portable JDK.
- The repository's existing `gradlew.bat` and its already-cached Gradle
  dependencies.
- For an `.exe`, WiX v3 `candle.exe` and `light.exe` available on `PATH`, or
  under `WIX_HOME`, `WIX`, or `WIX_TOOLSET_PATH`. WiX v4's `wix.exe` is
  reported for diagnostics but is not substituted because `jpackage` requires
  the v3 tool pair on supported JDKs.

The packaging flow does not download, install, or update a tool.

## Detect without changing the machine

```powershell
pwsh -File .\source\windows\packaging\detect-toolchain.ps1 -AsJson
```

The command exits with status 2 when a required tool is missing. Use
`-Type app-image` to inspect the JDK/Gradle prerequisites without WiX.

## Build the single installer

```powershell
$releaseVersion = Read-Host 'New verified-build version (do not reuse the chat-only 0.1.0 artifact)'
pwsh -File .\source\windows\packaging\package.ps1 `
  -Type exe -Version $releaseVersion
```

The command runs Gradle offline (`:windows:host:installDist --offline`) and
writes the selected version under
`source\build\windows-packaging\exe\MCastTalk-<version>.exe` when invoked from
the shared workspace as shown. This is an output convention, not a claim that
every locally generated EXE has passed installation testing. To package an
already-built distribution, pass `-SkipGradle`; the script then fails if
`windows\host\build\install\host\lib\host.jar` is absent.

For CI smoke checks or machines without WiX:

```powershell
pwsh -File .\source\windows\packaging\package.ps1 `
  -Type app-image -Version $releaseVersion
```

The app-image is a diagnostic artifact, not the release installer.
