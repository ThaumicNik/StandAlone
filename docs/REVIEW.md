# Project Review Findings

This document records the findings from the project review of 9 November 2026. The project is an offline-first synchronization MVP with replaceable storage and remote interfaces. The findings below describe work needed before relying on it in the field.

## Implementation update — 12 November 2026

- **Finding 1:** Journal writes reject events over 16 MiB before publication. The file-backed server limits each change page to 8 MiB of encoded changes. Regression tests cover large histories, reopening and oversized-page rejection.
- **Finding 2:** A process-wide repository owns the Android store. Activity actions and background workers share its serialized executor; synchronized access also protects the ownership boundary. Activity destruction no longer shuts down the shared executor.
- **Finding 3:** WorkManager schedules persistent synchronization after startup, local edits and endpoint submission, with a connected-network constraint and exponential backoff. Temporary transport failures and remaining remote history request another attempt. Permanent failures finish the work and can be retried after correcting the configuration.
- **Finding 4:** Each download phase is limited to ten pages by default, configurable through SyncEngine. Uploads therefore run even when the remote feed keeps growing. A successful pass does not promise complete catch-up; subsequent passes continue from the saved cursor.
- **Finding 5:** New journal commits publish individual immutable event files instead of rewriting history. Candidate state forks avoid replaying the journal before every mutation. Legacy snapshots remain readable. Startup replay, retained history, collection-copy costs and growing file counts remain limitations; a database adapter and compaction are future work, not claimed as implemented.
- **Finding 6:** RetrofitRemoteStore accepts an injected OkHttpClient. Defaults are 30-second connect/read and a 60-second overall call timeout. The core README now states payload, ordering, deduplication and backend integration requirements explicitly.
- **Finding 7:** Android cleartext access is restricted to local demo addresses. The server binds to loopback by default and optionally enforces a shared Bearer token through SYNC_API_TOKEN. Docker publishes to host loopback. HTTPS termination and per-user authorization remain deployment responsibilities, documented in the server README.

**Storage compatibility:** Back up or move the original snapshot file, if present, together with its sibling `<file>.events` directory. Existing snapshots are not deleted or rewritten. Older library versions cannot read newly published event files. Atomic rename is required; power-loss durability of directory metadata remains filesystem dependent.

**Current verification:** The full Gradle run passed `:sync-core:jvmTest`, `:sync-retrofit:jvmTest`, `:sync-server:test` and `:app:assembleDebug`: 38 core tests, 2 Retrofit tests and 2 server tests. The direct JVM verification script also passed all 38 core tests and Java recovery in a fresh process. Dependencies missing from the offline cache were downloaded before the successful offline rerun. Android lifecycle, background scheduling and network-security behavior have compiled but have not been exercised on a device. The original review and its original verification limits follow below as historical context.

## 1. Large downloaded pages can prevent store recovery

`Journal` rejects events larger than 16 MiB when reopening a file, but does not enforce that limit when appending an event. A downloaded page is stored as one event, and the wire protocol permits many records with strings up to 1 MiB each. A valid large page can therefore be committed successfully and cause recovery to fail after restart.

**Suggested direction:** Apply consistent size limits before committing and bound downloaded pages by bytes as well as record count. Test a large page and reopening the resulting store.

Relevant files: `sync-core/src/jvmMain/kotlin/com/edu/standalone/sync/jvm/Journal.kt`, `Wire.kt`, and `FileStores.kt`.

## 2. Activity recreation can create multiple owners of one store

The Android activity creates its own worker and `FileLocalStore`. Shutting down the executor allows already queued work to continue, so a recreated activity can open the same file while the previous activity's worker is still using it. This violates the store's single-owner requirement and risks lost updates.

**Suggested direction:** Give the file a single process-wide owner and serialize all access through it. Verify behavior when the activity is recreated during a save or synchronization.

Relevant files: `app/src/main/java/com/edu/standalone/MainActivity.kt` and `sync-core/src/jvmMain/kotlin/com/edu/standalone/sync/jvm/FileStores.kt`.

## 3. Android synchronization requires manual action

The demo starts synchronization only when the user presses a button. The core retains pending operations after temporary failures, but the app does not schedule automatic retries or resume them after process restart.

**Suggested direction:** Add a persistent Android scheduling integration with backoff and retry after connectivity returns. Keep Android-specific scheduling separate from the synchronization core.

Relevant file: `app/src/main/java/com/edu/standalone/MainActivity.kt`.

## 4. An unbounded initial download can delay uploads

A synchronization pass downloads until it receives an empty page before sending pending operations. A large remote backlog can consume a short connection window, and a continuously growing feed may prevent the first download phase from finishing.

**Suggested direction:** Consider bounded synchronization passes or a server-defined snapshot boundary that gives pending uploads an opportunity to run while preserving the intended ordering and conflict semantics.

Relevant file: `sync-core/src/commonMain/kotlin/com/edu/standalone/sync/SyncEngine.kt`.

## 5. File storage becomes expensive as history grows

Every local commit replays the full journal and rewrites the entire file. Offline edits and retained audit history therefore increase processing time and storage writes over time. This is a reasonable simplification for a small MVP, but it needs measurement before use with larger workloads.

**Suggested direction:** Measure performance with realistic backlogs and consider a transactional database-backed adapter. Define retention or compaction rules separately, without weakening retry and deduplication guarantees.

Relevant files: `sync-core/src/jvmMain/kotlin/com/edu/standalone/sync/jvm/FileStores.kt` and `Journal.kt`.

## 6. Integration flexibility and configuration are limited

The interfaces allow custom adapters, but the data model uses full string SET/DEL values, server-order conflict resolution, and a server implementing the synchronization protocol. It does not automatically synchronize an arbitrary existing REST API or merge concurrent edits. The Retrofit adapter also constructs its own HTTP client with fixed timeouts.

**Suggested direction:** Document these integration requirements prominently. Consider allowing callers to inject their HTTP client and configure timeouts. Add other data models or conflict policies only when justified by concrete use cases.

Relevant files: `sync-core/src/commonMain/kotlin/com/edu/standalone/sync/SyncOperation.kt` and `sync-retrofit/src/jvmMain/kotlin/com/edu/standalone/sync/retrofit/RetrofitRemoteStore.kt`.

## 7. Security is limited to a local test setup

The demo permits cleartext traffic, and the test server is intended for local test data. Authentication and transport security need an explicit design before real deployment.

**Suggested direction:** Preserve the documented local-test scope until secure transport and authentication are implemented and verified for the intended deployment.

Relevant files: `app/src/main/AndroidManifest.xml` and `sync-server/src/main/java/com/edu/standalone/server/SyncServer.java`.

## Verification limits

The review included the current working tree, including uncommitted changes. The offline Gradle build could not start because the wrapper attempted to download its distribution and network access was denied. The fallback JVM check stopped when the compiler could not access a cached dependency. A passing current build or test run was therefore not established, and Android behavior was not tested on a device.
