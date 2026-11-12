package com.edu.standalone.server;

import com.edu.standalone.sync.*;
import com.edu.standalone.sync.jvm.FileLocalStore;
import com.edu.standalone.sync.local.SyncClient;
import com.edu.standalone.sync.remote.InMemoryRemoteStore;
import java.io.File;

/** Two invocations exercise Java API and recovery in a fresh JVM process. */
public final class JavaApiSmoke {
    @org.junit.Test public void javaConsumer() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("java-consumer").toFile();
        File file = new File(dir, "state.bin");
        try {
            main(new String[]{"create", file.getPath()});
            main(new String[]{"recover", file.getPath()});
        } finally { deleteTree(dir.toPath()); }
    }
    static void deleteTree(java.nio.file.Path root) throws java.io.IOException {
        try (var paths = java.nio.file.Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))
                java.nio.file.Files.delete(path);
        }
    }
    public static void main(String[] args) {
        FileLocalStore local = new FileLocalStore(new File(args[1]));
        SyncClient client = new SyncClient(local, "java", () -> "java-operation", () -> 0L);
        if (args[0].equals("create")) {
            client.set("record", "offline");
            new SyncEngine(local, new InMemoryRemoteStore(), "java", 1).synchronize();
        } else {
            if (!"offline".equals(client.get("record")) || local.getCursor() != 1L
                    || local.getOperations().size() != 1
                    || local.getDelivery("java-operation").getStatus() != DeliveryStatus.ACKNOWLEDGED)
                throw new AssertionError("Fresh-process recovery failed");
            System.out.println("Java API and fresh-process recovery: PASS");
        }
    }
}
