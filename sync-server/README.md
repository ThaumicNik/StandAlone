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

The Android demo uses `sync-retrofit`, a separate JVM Retrofit implementation of
`RemoteStore`. Its calls are blocking and run on the demo's worker thread. To
test offline behavior, save a `task:` record with SET, disconnect the device,
try Synchronize, and check Audit and delivery for PENDING. Reconnect and
synchronize again; the operation should become ACKNOWLEDGED and appear in
`GET /tasks`. The demo schedules persistent synchronization after startup and local edits, with network constraints and exponential retry backoff.

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
Android demo is a generic record editor, not a task-specific UI. Task listing
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
