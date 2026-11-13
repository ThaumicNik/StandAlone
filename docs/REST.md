# REST protocol v1 (local test environment)

Transport: HTTP, `application/vnd.standalone.sync-v1`, binary big-endian fields.
All strings are a signed 32-bit byte length followed by UTF-8 bytes (maximum
1 MiB). Integers and longs are signed 32-bit and 64-bit values. Boolean is one
byte (0 or 1). No Java object serialization is used.

An operation contains, in order: operationId string, entityId string, clientId
string, localSequence long, type byte (0 SET, 1 DEL), payload string for SET
only, createdAt long. IDs must remain stable across retries.

- `POST /operations`: operation body. A 200 response contains accepted boolean,
  operationId string, then revision long if accepted or reason string otherwise.
- `GET /changes?after=0&limit=100`: 200 response contains count int, followed by
  that many pairs of operation and serverRevision long, then nextCursor long.
  `after >= 0`, `1 <= limit <= 10000`. Empty pages retain the request cursor.
- 400: invalid request or operation ID reused with different content; protocol
  failure, not an automatic retry. 404: unknown route/method.
- 408, 429, 5xx and I/O failure: temporary/unknown outcome. Retain PENDING and
  retry the original operation. A 200 rejection is final and retained in audit.

Server acceptance is serialized and committed before responding. Reopening the
server file restores revisions and deduplication. GET responses never advance a
client cursor by themselves. Local page contents and cursor commit together.

The demo uses port 8080 and binds to loopback by default. SYNC_BIND_ADDRESS
overrides the binding; Docker publishes only to host loopback. SYNC_API_TOKEN
enables shared Bearer authentication, including health. There is no built-in
TLS or per-user authorization. See the server README for deployment boundaries.
Default Android emulator endpoint: `http://10.0.2.2:8080`.
Android schedules WorkManager after startup, local edits and endpoint changes.
Network calls run outside the serialized local transaction boundary. Each
normalized endpoint owns its journal, cursor and sync status; old data remains
bound to its previous endpoint. Returning to that endpoint resumes its queue.
URL identity cannot detect a server reset at the same address: retain the server
history, or use a new endpoint identity. No second KMP platform is claimed.

Protocol v1 replaces full records in server acceptance order. It has no base
entity version or explicit conflict negotiation. Concurrent edits to different
fields can overwrite one another. A future optimistic-concurrency protocol needs
versioning, server enforcement, migration rules and client conflict presentation;
it must not be described as an existing v1 guarantee.
