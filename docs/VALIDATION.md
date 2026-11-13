# Acceptance and reliability validation

## Automated checks

The Gradle test suites cover algorithm invariants, disk failures, interrupted
delivery/reopening, actual local HTTP exchange, response classification, Java
consumption, endpoint URL identity, indexed history and concurrent local edits
while a remote response is blocked. Empty polls must not add event files.

Device tests in `TaskPersistenceTest` use private temporary files/preferences:

- Switching endpoints keeps independent pending histories and preserves the
  legacy journal's ownership.
- A failed local save retains the draft; reconstructing the ViewModel from its
  saved state restores the editor fields.

These tests have compiled. A connected-device run is required before calling
their runtime behavior verified.

## Manual device acceptance (open)

Use two devices/emulators and a fresh test server. Record OS/device, build commit,
server data identity, timestamps and final task/delivery states for each scenario.
Do not use real employee data.

| Scenario | Required observation |
| --- | --- |
| Offline creation and restart | Saved task remains visible and pending after process restart; reconnect eventually clears pending |
| Slow or blackholed connection | Local reads and saves complete while the remote call is blocked; no refresh backlog |
| Rotation while editing/saving | Draft survives recreation; successful save closes editor; failed save preserves entered fields |
| Process interruption around save | Reopening retains committed data; retrying the restored draft uses the same task ID |
| Endpoint A to B to A | B never receives A's queued tasks; returning to A restores its cache and pending queue |
| Worker after app backgrounding | Connectivity/backoff resumes work; document actual scheduling delays |
| Server accepts but response is lost | Retry retains operation ID; server revision appears once |
| Competing address/status edits | Both clients converge to the last server-accepted full record; document overwritten fields |
| Rejected operations | Pending clears, audit retains reason, existing task displays the latest rejection |
| Delete during detail view | Updating the removed task reports an error rather than recreating it from an old UI snapshot |

SavedStateHandle is Android saved instance state, not a durable draft database.
Force-stop/power-loss draft recovery is not guaranteed. Committed task data is
stored in the journal. First-time rejected creations may have no visible record;
full audit/error management remains future UI work.

## Research experiments (open)

The desktop smoke benchmark in BENCHMARKS.md is an initial storage measurement.
It does not validate the thesis's unstable-network hypothesis. For that study:

1. Compare online-only writes, a queue without operation-ID deduplication, and
   the durable synchronization client using identical scripted changes.
2. Vary latency, disconnection windows, bandwidth, lost responses and server
   downtime. Include 100/1,000/10,000-operation backlogs where practical.
3. Run several repetitions with fixed input/seeds and report distributions.
4. Measure lost/duplicated operations, time to convergence, bytes transferred,
   local write latency, startup latency, disk/memory and Android battery cost.
5. Keep raw measurements, environment and commit identity with the report.

Do not claim those comparisons, Android lifecycle behavior, power-loss durability
or additional KMP platforms from a passing JVM suite or APK compilation.
