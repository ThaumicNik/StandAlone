# sync-core

Kotlin Multiplatform synchronization library, currently targeting JVM 11.
Kotlin and Java consumers use the same blocking API. Android calls it on a
serialized worker thread. Additional platforms remain deferred.

`SyncClient` provides offline GET/SET/DEL and immutable audit. `SyncEngine`
executes pull–send–pull with cursor pagination. Server order decides SET/DEL
conflicts. Pending edits overlay confirmed state; rejected edits leave audit
and fall back to confirmed state. Acknowledged edits remain visible until the
corresponding server revision has been downloaded.

Adapters:

- `InMemoryLocalStore`, `InMemoryRemoteStore`: sequential algorithm testing.
- `FileLocalStore`: durable records, audit, delivery and cursor via atomic file
  replacement. One client/process owns each file and serializes API calls.
- `FileRemoteStore`: synchronized durable acceptance and deduplication history.
- `HttpRemoteStore`: blocking HTTP adapter with finite timeouts and explicit
  temporary-error classification.

Files use a versioned event snapshot, fsync before atomic replacement, and fail
closed for an invalid file. History is retained indefinitely; snapshot rewrites
and replay favor a small MVP over large datasets. Filesystem support for atomic
rename is required. Power loss durability of directory metadata is filesystem
dependent; process-restart recovery is covered by tests.

The `sync-server` Java application is also a Java consumer of the library.
The Android demo provides record editing, manual synchronization and delivery
inspection with a persistent client ID. [REST protocol](../docs/REST.md).

Build: `gradlew :sync-core:jvmTest :sync-core:jvmJar :sync-server:build
:app:assembleDebug`. Server: `gradlew :sync-server:run` (optional arguments:
port and data file). For restricted offline environments, `tools/verify-jvm.ps1`
uses installed cached Kotlin compiler/JUnit artifacts to compile the library,
server and JVM tests directly. This does not validate Gradle or Android builds.

See [MVP](../docs/MVP.md) and [progress](../docs/PROGRESS.md) for verification
status and remaining platform checks.
