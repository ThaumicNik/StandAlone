# Journal and backlog smoke benchmark

Run `.\gradlew.bat :sync-server:benchmark` from the repository root. CSV output is
written to `build/benchmarks/current.csv`; benchmark data lives in temporary
directories and is removed after each scenario.

The harness performs a discarded 30-operation warm-up, then 100, 1,000 and 3,000
independent 256-byte SETs. It measures local save mean/p95, journal reopening, and
complete catch-up against a file-backed server over in-process calls. Catch-up
includes all bounded passes needed to download acknowledgements. Ten subsequent
idle passes must add zero event files.

`journal_bytes` and `event_files` cover local plus remote storage after catch-up.
`heap_used_bytes` is a process heap snapshot, not retained memory attributable to
one store: GC timing, warm-up and earlier scenarios affect it. There is one timed
sample per backlog, so these numbers cannot establish a performance trend or
serve as a before/after comparison. Filesystem cache and antivirus are uncontrolled.
No HTTP, mobile hardware, bandwidth limits or network faults are modeled here.

The benchmark deliberately exposes remaining collection-copy, replay and
many-small-files costs. A transactional database adapter/compaction design should
be selected using repeated workload measurements, preserving deduplication,
pending overlays and atomic cursor advancement. See VALIDATION.md for the broader
research protocol and device acceptance checklist.

## Recorded run: 13 November 2026

Environment: Windows 11, Temurin JVM 25.0.2, Gradle 9.5.0. The benchmark was run
from this working tree before its hardening commit. Sources are in
`SyncBenchmark.java`; raw results are in
[2026-11-13-windows-jvm.csv](benchmarks/2026-11-13-windows-jvm.csv).

| Operations | Save mean / p95 (ms) | Reopen (ms) | Complete catch-up (ms) | Event files | Idle added files |
| --- | --- | --- | --- | --- | --- |
| 100 | 4.853 / 5.173 | 533.242 | 1,405.678 | 401 | 0 |
| 1,000 | 4.925 / 5.225 | 5,516.192 | 14,432.689 | 4,010 | 0 |
| 3,000 | 4.977 / 5.230 | 16,218.609 | 43,565.171 | 12,030 | 0 |

This confirms zero idle growth in these cases and exposes substantial reopen and
catch-up cost in the current file adapter. It supports prioritizing a transactional
database adapter before larger field workloads. It does not establish causality,
a mobile performance budget, a baseline improvement, or unreliable-network results.
The first harness attempt assumed one bounded pass was enough; that assertion was
corrected to wait for convergence before recording the successful run above.
