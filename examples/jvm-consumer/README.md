# Separate Java consumer

From the repository root:

```powershell
.\gradlew.bat :sync-core:publishAllPublicationsToWorkspaceRepository :sync-retrofit:publishAllPublicationsToWorkspaceRepository
.\gradlew.bat -p examples/jvm-consumer run
```

This is an independent Gradle build: it resolves Maven artifacts and metadata
from `build/maven-repository`, rather than depending on project modules. It checks
Java use, synchronization, durable reopening and adapter construction. Nothing
is uploaded to a public registry. Use `--offline` when dependencies are cached.
