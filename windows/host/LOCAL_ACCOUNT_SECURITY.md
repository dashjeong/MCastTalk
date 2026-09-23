# Offline local accounts and sessions

This is the local Windows host's account domain, not cloud identity. It does
not remove the loopback-only/TLS/network admission release gates.

## Policy and integration contract

- `LocalAccounts(dataRoot)` is an AutoCloseable lifetime owner. Open one store
  for the host process; keep it open until shutdown. A second cooperating
  process/instance must fail its exclusive account-store lock.
- The installer/first-run desktop flow calls `isInitialized()` followed by
  `createInitialAdmin(username, displayName, password: CharArray)` only when
  truly uninitialized. There is no default password, self-signup, anonymous
  guest or automatic reset/reinitialization.
- ADMIN manages accounts. USER and GUEST cannot list or mutate accounts.
  Operations validate the actor's current enabled status, server-side role
  and `securityVersion`, not client claims. The last enabled ADMIN is retained;
  an administrator cannot disable, demote or delete their own account even
  when another administrator exists. Self display-name/password changes are
  possible and invalidate old sessions.
- GUEST requires a specific `guestRoomId` and `expiresAt`. New/active guest
  expiration must be in the future. HTTP/WS must enforce room scope on every
  relevant operation; storing a scope alone does not authorize media/chat.
- Username: case-normalized ASCII `[A-Za-z0-9][A-Za-z0-9_.-]{2,31}`, no trimming.
  Display name: nonblank, at most 64 UTF-16 units, no ISO controls. Password:
  8–128 UTF-16 units with whitespace preserved; no trimming or normalization.
  The minimum was reduced from 15 to 8 at the user's explicit request on
  2026-09-19; this policy is not a claim of authentication-standard compliance.
  Existing hashes, accounts, and longer passwords remain valid without migration.
  Callers clear CharArray inputs in `finally`; no credentials/tokens in logs.

## Password protection and bounded authentication

PBKDF2-HMAC-SHA256 uses 600,000 iterations, a unique SecureRandom 16-byte salt,
a 32-byte derived hash, and `MessageDigest.isEqual` comparison. Unknown users
still perform a dummy hash for valid-length credentials. Failure, disabled,
expired, incorrect, unknown, throttled and hash-slot-busy logins all return
the same null result. These choices follow the relevant guidance in
[OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2)
and [OWASP Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).

OWASP generally prefers Argon2id; PBKDF2 here is the deliberate JDK17 built-in,
dependency-minimal offline choice, not a claim of superior hashing or FIPS
certification. Two process-wide non-waiting hash slots cap parallel CPU use.
Login limits allow at most 8 attempts per normalized username per 5 minutes,
60 total per minute, with at most 512 tracked names per store. Successful
attempts also consume this budget. This can temporarily deny legitimate
users during an attack; public-network abuse controls remain separate work.

## Persistence and failure behavior

`dataRoot/config/security` contains `accounts.lock`, the `initialized.v1`
sentinel, and the bounded canonical `accounts.v1.jsonl` snapshot. Limits are
256 accounts and 1 MiB. Password hashes, salts and parameters are stored;
original passwords and session tokens are not. JSON lines are an internal
format, not a supported manual editing interface.

The initial sentinel is created exclusively and forced before atomically
installing the first snapshot. Interrupted setup with only a sentinel fails
closed, even on restart. Missing/corrupt/unsupported/incomplete snapshots,
duplicate identities/usernames, invalid hash parameters and absence of an
enabled administrator do not create a new administrator. An operator
recovery/backup policy is required; no recovery bypass is implemented.

Updates write/force a same-directory temporary file and atomically replace
the snapshot. There is no non-atomic filesystem fallback. Memory and account
versions publish only after the write succeeds; failure preserves the old
state. Callback notifications happen after commit and outside the account
monitor. The exclusive FileChannel lock prevents cooperating duplicate
hosts from overwriting one workspace. It does not defend against an OS
administrator or a process able to ignore the lock and tamper with files.
Inherited filesystem ACLs still matter; encrypted storage, hardened owner
ACL provisioning, backup protection and power-loss recovery certification
are not claimed by this implementation.

## Sessions and revocation

`LocalSessions.issue(authenticatedAccount)` takes the exact account version
returned by authentication. It must never be exposed as an unauthenticated
account-id-to-token endpoint. A reset between authentication and issuance
therefore cannot mint a session against changed credentials.

Each token contains 256 SecureRandom bits encoded as 43 base64url characters.
Only its SHA-256 key and account/version/timestamps are retained in memory.
At most 1,024 sessions exist; restart forgets all of them. Server-side idle
expiry is 30 minutes, absolute lifetime 8 hours, with guest expiry taking
precedence. Clock rollback invalidates an existing session. These server-side
expiration controls align with
[OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

`resolve(token, touch = false)` is mandatory for passive WS output/sweeps so
incoming room traffic cannot prolong idle authentication. Authenticated user
activity may use the default `touch = true`. Every resolution rechecks current
account/version/expiry. Password reset, disable, role/scope/expiry/name changes
invalidate old versions immediately. A delayed change callback retains a
freshly authenticated new-version session. Explicit revokeAccount revokes
all versions; logout revokes only that exact token.

Revocation listeners receive accountId only, not tokens. A WS listener should
re-resolve its own token with `touch = false` before closing, so one browser's
logout does not close another valid session. Listener failure cannot make a
revoked token valid; inbound/outbound checks and the transport's expiry timer
must still fail closed. HTTP owns bounded request bodies, cookie flags,
Origin/CSRF policy, response redaction and transport authorization.
Notifications use a single drain owner and coalesce pending account IDs.
Resolving further expired tokens inside a listener queues a later pass;
it does not recursively call listeners or hold account/session locks while
callbacks execute. A regression fixture expires all 1,024 sessions together
and requires callback depth to remain one.

## Verification scope

The source includes real production-600,000-iteration hash tests, store
reopen/corruption/partial setup/atomic-save failure fixtures, administrator
authorization, guest expiry, bounded counts/login attempts, token uniqueness,
idle/absolute expiry, no-touch checks, password and role revocation, and stale
authentication snapshots. Integration test results are reported by the host
test owner; merely adding these tests is not evidence that they passed.
These tests do not establish LAN/TLS readiness, a penetration-test result,
high-concurrency abuse resistance, crash/power-loss certification or account
recovery usability.
