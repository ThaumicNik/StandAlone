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
- `FileLocalStore`: durable records, audit, delivery and cursor via immutable event
  files. One client/process owns each store and serializes API calls.
- `FileRemoteStore`: synchronized durable acceptance and deduplication history.
- `HttpRemoteStore`: blocking HTTP adapter with finite timeouts and explicit
  temporary-error classification.
- `SerializedLocalStore`: protects individual local calls and compound `access`
  transactions. Run network calls outside `access`, and serialize sync passes
  separately. The Android repository follows this ownership model.

Each new event is fsynced and atomically published under `<file>.events/`.
Existing version-1 snapshot files remain readable and are retained as the base.
Backups and moves must include both the base file (if present) and its `.events`
directory. Older library versions cannot read the new events. Never delete or
rename individual event files. Unpublished `.tmp` files are ignored on recovery.
History is retained indefinitely. Recovery streams events without retaining a
second encoded history; empty pages validate their cursor without a journal write.
Entity lookup and sequence allocation use indexes, and the server slices its
acceptance-ordered feed without scanning/sorting all history for each page.
Writes no longer replay or rewrite all history, but in-memory collection copies,
startup replay and the number of event files
still grow with history. Large installations need a database adapter or a separately
designed compaction protocol. Atomic rename support is required; directory metadata
power-loss durability remains filesystem dependent.

Download phases default to ten pages each so a growing feed cannot indefinitely
block uploads. A pass can finish with remote history remaining; schedule subsequent
passes to catch up. The file-backed server limits pages to 8 MiB of encoded changes;
local journal events are limited to 16 MiB before publication.

Integration requires full string SET/DEL payloads and a compatible ordered change
feed with operation-ID deduplication. Server acceptance order decides conflicts;
field merging and arbitrary REST API integration require application-specific work.
The Retrofit adapter accepts an injected OkHttpClient for credentials, TLS settings
and timeouts. The default client uses 30-second connect/read and 60-second call limits.

The `sync-server` Java application is also a Java consumer of the library.
The Android demo provides task creation/status changes, cached search, pending
and rejection indicators, and WorkManager scheduling with a persistent client ID.
Its ViewModel retains drafts in saved state and awaits successful local persistence
before closing the editor. [REST protocol](../docs/REST.md).

Maven coordinates: `com.edu.standalone:sync-core-jvm:0.1.0-SNAPSHOT` and
`com.edu.standalone:sync-retrofit-jvm:0.1.0-SNAPSHOT`. Local publication and a
separate Java consumer are described in [examples/jvm-consumer](../examples/jvm-consumer/README.md).
Publication includes dependency metadata; public-registry distribution is not configured.

Build: `gradlew :sync-core:jvmTest :sync-core:jvmJar :sync-server:build
:app:assembleDebug`. Server: `gradlew :sync-server:run` (optional arguments:
port and data file). For restricted offline environments, `tools/verify-jvm.ps1`
uses installed cached Kotlin compiler/JUnit artifacts to compile the library,
server and JVM tests directly. This does not validate Gradle or Android builds.

See [MVP](../docs/MVP.md) and [progress](../docs/PROGRESS.md) for verification
status and remaining platform checks.
