# Public release approval

This source candidate is not approval to publish or distribute an APK.

## Source boundary

- Export only reviewed tracked source files. Never copy a working directory with
  its `.git`, caches, logs, conversations, signing material, or local settings.
- Start a new repository from that export. Do not fork, mirror, migrate, reuse a
  private remote, or change another repository's visibility.
- Record the complete file manifest and SHA-256 of the proposed source archive.
  Re-run review after any content changes.
- Run `python3 scripts/test-public-snapshot.py` and
  `python3 scripts/verify-public-snapshot.py` before committing the export.
- Check every ref, tag, reflog, object, alternate-object path and worktree link in
  the new repository. Its initial history must contain exactly one parentless
  commit; no unrelated refs or objects may be present.
- Secret-pattern checks cannot establish absence of all personal information.
  Review database contents, license notices, author/contact metadata and embedded
  archives separately. Preserve legally required third-party attribution.

## Destination and approval

- Before any remote write, show the owner, repository name and immutable GitHub
  repository ID, intended visibility, exact commit/archive hash and file list.
- Create a separate private staging repository only after authorization. Confirm
  it is not a fork and has no association with a private development repository.
- Use credentials limited to the selected public-release repository where the
  platform supports this. Do not rely on the currently selected GUI window.
- Inspect the uploaded private staging repository, branches, tags, releases,
  workflow artifacts and visible metadata before requesting public visibility.
- Publication and release uploads require explicit approval for the exact result.
  CI verifies source/builds and does not upload APKs or diagnostic artifacts.

## Product, privacy and rights

- Resolve the individual redistribution terms listed in
  `THIRD_PARTY_LICENSES.md`, including bundled terminology data and model/voice
  components. Missing permission blocks the affected distribution; either obtain
  evidence or propose a reviewed replacement/removal before publication.
- Review `SECURITY.md`: local processing is not guaranteed network isolation;
  transcript persistence, access defaults and HTTP transport require disclosure.
- A signed APK requires verification of its exact packaged contents, signature,
  alignment, hash, installation/update and user-visible behavior. Unsigned CI
  builds and source tests do not prove physical-device audio or stability.
- Never upload raw Android logcat. Review and sanitize any separate support export
  before sharing; third-party SDK output may contain installation credentials.
- Keep incident evidence and recovered conversations outside all public exports.
