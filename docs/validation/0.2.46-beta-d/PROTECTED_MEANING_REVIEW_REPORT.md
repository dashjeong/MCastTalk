# Protected meaning: candidate review, release blocked

## Scope and implementation

No prompt, TRAIN, model weights, frozen source/context/style/target, approved exact translation, or production deadline was changed. Realtime remains 10 seconds; native terminal protection remains 120 seconds. Offline evaluation uses a separate 60-second request budget.

The core guard checks narrow observable Korean negative-statement / Chinese terminal-question form, supported multiple-currency value/currency multisets, and explicit verbatim quotation scope. Unsupported/incomplete numeric parsing is conservative; readable currency labels may be checked independently. Amount-to-actor assignment, terminology, contrast relations, unsupported numeric notation and general semantics remain outside its proof. Malformed quotation parsing and conservative double-negative exclusion can skip checks.

Approved exact domain translations retain early-return authority. Generated translations are checked with domain hints OFF or ON. Existing surface repair remains conditional on nonblank reference hints at the domain boundary. Gemma validation runs after backend retry, so completed semantic review failures are not treated as driver errors.

The broadcasting Gemma failover policy suppresses automatic fallback only for the three protected-review machine codes. It throws before cooldown, callback or global fallback state changes. Ordinary timeout/backend recovery and default Failover behavior remain unchanged. Language-specific DEGRADED handling continues the next item; operator broadcast/source controls are unchanged. Mock-engine/PCM tests prove contracts, not actual ML Kit or translated speech.

Final narrow fix excludes explicit negation of the quotation action only from ALL/unique-B quote requirements. Negation inside quoted content and A/B 아닌/아니라 contrast do not disable positive verbatim checks. Sentence/currency checks remain active. New unit regressions cover these cases; negated-action native quality was not independently measured.

## Evidence generations and denominators

All text is controlled synthetic fixture material. Invariants were review-only and were not sent to the model. Raw rejected native translations are unavailable, limiting false-positive regrading. A completed response or accepted guard contract is not semantic quality approval.

| Source/APK generation | Evaluation | Planned/attempted | Native completed/failed | Not attempted | Separate ML Kit comparison completed/failed |
|---|---|---:|---:|---:|---:|
| Initial guard, product 98e2cc7d | first independent 8 × OFF/ON | 16/16 | 12/4 | 0 | 3/1 of 4 |
| Initial guard | recovery DEV | 12/12 | 6/6 | 0 | 0/6 of 6 |
| Initial guard | meeting DEV | 6/6 | 5/1 | 0 | 1/0 of 1 |
| Parser/request-only policy, product 1737a53f | second independent 6 × OFF/ON | 12/12 | 7/5 | 0 | 2/3 of 5 |
| Parser/request-only policy | recovery DEV | 12/12 | 5/7 | 0 | 1/6 of 7 |
| Parser/request-only policy | meeting DEV | 6/6 | 5/1 | 0 | 1/0 of 1 |

All these suites recorded cleanup and functionalCompletion=false. JUnit suite completion is not full translation quality PASS. First samples became DEV after results informed fixes. Second independent results predate the final quotation-negation fix and cannot be reused as final-source blind approval. Protected failure ML Kit calls were comparison-only, not production publication.

Independent owner review identified RH02 actor/self-review errors, RH05 deposit→contribution terminology errors, SH01 airfare→airline errors, MC09 fallback sentence collapse, and completed native Japanese outputs containing Korean number/currency tokens. SH02ON currency change and SH06 quotation failures were rejected. Currency/quote guards do not solve every role or contrast error. RC04 mixed-script errors remain; MC09 suppression does not generate a correct interpretation.

## Final source verification

Final module gates: BUILD SUCCESSFUL, 1m32s; Gemma 176, core 294, Alpha 592 tests, zero failures/errors/skips; lint, alpha product/test assembly and license verification completed. Exact final product SHA-256 is `1e6573452fce69660aab71d77ea2960e42a19a7a69fcf3822021b33e44e61853`; test SHA is `08e16c46d2eaa398697d897b5fb96ae8c010ec99c6f1abcc05a60d5c9fd33ba5`. Test bytes legitimately remained identical because the final core change is in the target product. Both have expected release certificate, v3 signature and 16KiB alignment. Exact pair installed successfully; emulator-only storage reserve restored null→20MiB→null.

Final actual E2B native + real Failover class, offline text-only: 2 attempted, 1 review, 1 completed, 0 failed, 0 unattempted. RC05 quote review occurred at 2168ms; next native request completed at 1135ms. Fallback calls and failure callbacks were both 0, usingFallback remained false, cleanup completed. JUnit 27.632s. This proves only the observed pair, not live broadcast/TTS/realtime phone behavior.

Final basic 12 emulator regressions passed in 12.986s: domain database/hint/import behavior and local source server/test-tone/open/stop/restart/input controls. Previous 13.399s basic run belongs to the previous exact APK pair. Test-tone PCM is not translated speech or human listening.

## Release blockers and handoff

Critical role/terminology/contrast and mixed-script meaning errors remain. Actual phone realtime latency, translated audio/listening, network recovery and long-run reliability are unverified for this exact candidate. No release/main merge or model promotion is justified. No hardware benchmark is claimed. Keep public beta-c and Draft PR until subsequent independent holdout and phone gates pass.

Source hashes, exact artifacts and immutable controlled evidence are listed in [MANIFEST.json](protected-meaning-evaluation/MANIFEST.json). Its uncommitted status records the pre-commit handoff snapshot. Codex independently checked all 15 source hashes and 15 evidence hashes, final APK signature/alignment/hashes, final JUnit records, and controlled fixture inputs. Commit and CI status are tracked separately in [Draft PR 17](https://github.com/dashjeong/MCastTalk/pull/17); neither grants APK release approval. Staging excludes the existing user-owned `docs/HANDOVER_DOMAIN_CORPUS.md`, private signing data, APK binaries and local raw logs.
