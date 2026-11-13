# StandAlone

An offline-first synchronization library and experimental Android task app for
intermittent connections. Current support is Kotlin/Java on JVM 11; the Android
consumer builds, while device acceptance remains open.

| Module | Responsibility |
| --- | --- |
| `sync-core` | GET/SET/DEL, pending overlays, delivery audit, retries, ordered change feed and durable adapters |
| `sync-retrofit` | Blocking Retrofit adapter with injectable HTTP client |
| `sync-server` | Local Java sync/task backend and Docker distribution |
| `app` | Compose task UI, ViewModel/saved drafts, endpoint-specific stores and WorkManager |

The core persists operations before sending them and retries the same operation ID
after an unknown outcome. The compatible server deduplicates IDs and assigns
acceptance-order revisions. Protocol v1 replaces whole string records; it does
not detect stale edits or merge fields. The file adapters retain history and are
intended for small experimental datasets.

## Build and verify

```powershell
.\gradlew.bat :sync-core:jvmTest :sync-retrofit:jvmTest :sync-server:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Use `--offline` when dependencies are cached. The Android test APK compiling
does not establish device behavior. With a connected device, run
`:app:connectedDebugAndroidTest` and the [acceptance scenarios](docs/VALIDATION.md).

- [Local backend and Android setup](sync-server/README.md)
- [Library contract and storage compatibility](sync-core/README.md)
- [Independent published-artifact consumer](examples/jvm-consumer/README.md)
- [Current progress](docs/PROGRESS.md)
- [Review and remaining limitations](docs/REVIEW.md)
- [Benchmark methodology](docs/BENCHMARKS.md)
- [Original project and research goals](docs/PROJECT.md)
