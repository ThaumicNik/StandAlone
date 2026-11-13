# Local employee task backend

The task backend extends the sync test server. Build the distribution first:

```powershell
.\gradlew.bat :sync-server:installDist
docker compose up --build -d
```

Data is stored in the `task-data` Docker volume. `docker compose down` stops the
server without removing it. Check `http://localhost:8080/health` for `ok`.
The Android emulator uses `http://10.0.2.2:8080`; a physical device needs the
computer's reachable LAN address and network access.

The Android app uses `sync-retrofit`, a separate JVM Retrofit implementation of
`RemoteStore`. Its blocking calls run on a worker thread. To test offline
behavior, disconnect the device, create or change a task, and check its pending
indicator. Reconnect and synchronize; the task should appear in `GET /tasks`.
The app schedules synchronization after startup and local edits, with network
constraints and exponential retry backoff.

The task API uses UTF-8 payloads, leaving the task model to the application.
For example, store JSON containing title, assignee and status:

```powershell
curl.exe -X PUT http://localhost:8080/tasks/field-1 -H "Content-Type: application/json" --data-raw '{"title":"Inspect water pump","assignee":"Alex","status":"open"}'
curl.exe http://localhost:8080/tasks
curl.exe -X DELETE http://localhost:8080/tasks/field-1
```

`GET /tasks` returns an array of objects with `id` and `payload` (the original
payload as a string). IDs contain 1вЂ“100 ASCII letters, digits, `_` or `-`.
PUT replaces the whole payload; DELETE records a tombstone. Each task maps to
the sync entity ID `task:<id>`. Sync clients can read or write the same records
using the binary endpoints described in [REST.md](../docs/REST.md). The existing
Android app reads these records into its task list. Server task listing
replays the change feed, so this backend is intended for small test datasets.

Concurrent edits use the sync server's last-accepted-operation-wins rule. The
task API generates an operation ID for each request: retries can create another
revision, whereas sync clients retain their operation ID and deduplicate retries.
There is no built-in TLS, per-user access control, or task schema validation;
use only isolated networks and test data. Do not expose it to the internet.

The server now binds to 127.0.0.1 by default. Set SYNC_BIND_ADDRESS explicitly
for a LAN deployment. Docker binds inside the container to 0.0.0.0 but publishes
only on the host loopback address. Set SYNC_API_TOKEN to require a Bearer token
on every endpoint, including health. Inject an authenticated OkHttpClient in
Retrofit consumers. This shared-token option is not per-user access control.
Use an HTTPS reverse proxy for remote deployment; the embedded server does not
terminate TLS. The Android demo permits cleartext only for localhost, 127.0.0.1
and the emulator host 10.0.2.2; other endpoints must use HTTPS.
Back up the data file and its sibling .events directory together.

## Android task app

The Compose app displays locally cached `task:<id>` records and allows users to
create a task and change its status while offline. Each edit is stored through
`SyncClient`; WorkManager sends pending operations and downloads changes when a
network is available. The sync banner shows the last completed synchronization,
and each task with unsent changes is marked. Tap the banner to request another
sync. Search works on cached titles, addresses, and IDs. The Profile screen sets
the server URL; the default is the Android emulator host.
Normalized server URLs now own separate journals, download cursors and sync status.
Changing the URL opens that endpoint's local dataset; returning resumes its pending
queue. Existing `sync.bin` data stays bound to the endpoint configured at upgrade.
No files are moved or deleted. Drafts are scoped to the endpoint as well.
A reset/replaced server at the same URL cannot be detected by protocol v1: preserve
server history or configure a new URL/path identity. URL aliases are treated as
separate datasets unless normalization makes them identical.

Task payloads are JSON objects with `title`, `address`, `time`, and `status`.
Statuses used by the app are `open`, `in_progress`, and `done`. Existing extra
JSON fields survive status changes. Server records without a valid JSON object
are not displayed. The server resolves concurrent updates as described above;
this demo has no employee authentication, assignment filtering, or map.
Background sync is subject to Android WorkManager scheduling and network
availability. HTTP is permitted only for the local test hosts listed above.

Local transactions are serialized independently of remote calls, allowing local
edits during a slow HTTP request. Status changes read the latest local JSON under
that transaction lock. This avoids replacing newer local fields from a stale UI
snapshot; independent clients still use whole-record server-order replacement.
