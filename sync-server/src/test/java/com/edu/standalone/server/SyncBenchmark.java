package com.edu.standalone.server;

import com.edu.standalone.sync.*;
import com.edu.standalone.sync.jvm.*;
import com.edu.standalone.sync.local.SyncClient;
import java.nio.file.*;
import java.util.*;

/** Repeatable smoke benchmark, not an Android or unstable-network experiment. */
public final class SyncBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> rows = new ArrayList<>();
        rows.add("operations,save_mean_ms,save_p95_ms,reopen_ms,catchup_ms,journal_bytes,event_files,idle_added_files,heap_used_bytes");
        run(30); // JIT/filesystem warm-up, excluded from the output
        for (int count : new int[]{100, 1000, 3000}) {
            String row = run(count);
            rows.add(row);
            System.out.println(row);
        }
        Files.write(output, rows);
        System.out.println("JVM: " + System.getProperty("java.version") + "; OS: " + System.getProperty("os.name"));
        rows.forEach(System.out::println);
    }

    private static String run(int count) throws Exception {
        Path dir = Files.createTempDirectory("standalone-benchmark");
        try {
            var file = dir.resolve("local").toFile();
            var local = new FileLocalStore(file);
            var client = new SyncClient(local, "bench", () -> UUID.randomUUID().toString(), System::currentTimeMillis);
            long[] saves = new long[count];
            for (int i = 0; i < count; i++) {
                long start = System.nanoTime();
                client.set("task:" + i, "x".repeat(256));
                saves[i] = System.nanoTime() - start;
            }
            long start = System.nanoTime();
            var reopened = new FileLocalStore(file);
            double reopen = ms(System.nanoTime() - start);
            var remote = new FileRemoteStore(dir.resolve("remote").toFile());
            start = System.nanoTime();
            new SyncEngine(reopened, remote, "bench").synchronize();
            int passes = 1;
            while (reopened.getCursor() != count && passes++ < 100)
                new SyncEngine(reopened, remote, "bench").synchronize();
            double catchup = ms(System.nanoTime() - start);
            if (reopened.getCursor() != count || !reopened.getPendingOperations("bench").isEmpty())
                throw new AssertionError("Benchmark did not converge");
            long before = eventCount(dir);
            for (int i = 0; i < 10; i++) new SyncEngine(reopened, remote, "bench").synchronize();
            long idleAdded = eventCount(dir) - before;
            if (idleAdded != 0) throw new AssertionError("Idle polls grew the journal");
            long bytes;
            try (var files = Files.walk(dir)) {
                bytes = files.filter(Files::isRegularFile).mapToLong(path -> {
                    try { return Files.size(path); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }).sum();
            }
            double mean = ms(Arrays.stream(saves).sum()) / count;
            Arrays.sort(saves);
            long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            return String.format(Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d", count, mean,
                ms(saves[(int)Math.ceil(count * 0.95) - 1]), reopen, catchup, bytes, before, idleAdded, heap);
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }
    private static long eventCount(Path dir) throws java.io.IOException {
        try (var files = Files.walk(dir)) { return files.filter(path -> path.toString().endsWith(".event")).count(); }
    }
    private static double ms(long nanos) { return nanos / 1_000_000.0; }
}
