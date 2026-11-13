package example;

import com.edu.standalone.sync.SyncEngine;
import com.edu.standalone.sync.jvm.FileLocalStore;
import com.edu.standalone.sync.local.SyncClient;
import com.edu.standalone.sync.remote.InMemoryRemoteStore;
import com.edu.standalone.sync.retrofit.RetrofitRemoteStore;
import java.nio.file.*;
import java.util.UUID;

/** Separate Gradle build: resolves published artifacts and transitive dependencies. */
public final class LibraryConsumer {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("standalone-consumer");
        try {
            var file = directory.resolve("local").toFile();
            var local = new FileLocalStore(file);
            var client = new SyncClient(local, "java", () -> UUID.randomUUID().toString(), System::currentTimeMillis);
            client.set("task:sample", "hello");
            var remote = new InMemoryRemoteStore();
            new SyncEngine(local, remote, "java").synchronize();
            if (!"hello".equals(remote.get("task:sample")) || !"hello".equals(new FileLocalStore(file).get("task:sample")))
                throw new AssertionError("Published library failed round trip/recovery");
            // Construction verifies the published adapter exposes its required transitive types.
            new RetrofitRemoteStore("http://localhost:8080", new okhttp3.OkHttpClient.Builder().build());
            System.out.println("Published core + Retrofit Java consumer: PASS");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }
}
