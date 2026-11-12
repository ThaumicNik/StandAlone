package com.edu.standalone.server;

import com.edu.standalone.sync.jvm.HttpRemoteStore;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskApiTest {
    @Test public void tasksShareTheSyncLogAndSurviveRestart() throws Exception {
        File file = Files.createTempDirectory("tasks").resolve("sync.bin").toFile();
        HttpClient client = HttpClient.newHttpClient();
        try {
            try (SyncServer server = new SyncServer(file, 0)) {
                String base = "http://localhost:" + server.getPort();
                assertEquals(200, request(client, base + "/tasks/one", "PUT", "{\"title\":\"Fix pump\"}").statusCode());
                assertTrue(request(client, base + "/tasks", "GET", "").body().contains("Fix pump"));
                var changes = new HttpRemoteStore(base).getChanges(0, 10).getRemoteChanges();
                assertEquals("task:one", changes.get(0).getOperation().getEntityId());
                assertEquals(400, request(client, base + "/tasks/bad.id", "PUT", "x").statusCode());
            }
            try (SyncServer server = new SyncServer(file, 0)) {
                String base = "http://localhost:" + server.getPort();
                assertTrue(request(client, base + "/tasks", "GET", "").body().contains("Fix pump"));
                assertEquals(200, request(client, base + "/tasks/one", "DELETE", "").statusCode());
                assertEquals("[]", request(client, base + "/tasks", "GET", "").body());
                assertEquals(2, new HttpRemoteStore(base).getChanges(0, 10).getRemoteChanges().size());
            }
        } finally { JavaApiSmoke.deleteTree(file.toPath().getParent()); }
    }
    private static HttpResponse<String> request(HttpClient client, String url, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
