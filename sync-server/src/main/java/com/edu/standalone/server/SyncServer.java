package com.edu.standalone.server;

import com.edu.standalone.sync.jvm.*;
import com.edu.standalone.sync.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local test server. Single executor serializes acceptance and durable commit order. */
public final class SyncServer implements AutoCloseable {
    private final HttpServer server;
    public SyncServer(File file, int port) throws IOException {
        FileRemoteStore store = new FileRemoteStore(file);
        String bind = System.getenv().getOrDefault("SYNC_BIND_ADDRESS", "127.0.0.1");
        String token = System.getenv("SYNC_API_TOKEN");
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", exchange -> {
            try {
                if (token != null && !token.isBlank()) {
                    String supplied = exchange.getRequestHeaders().getFirst("Authorization");
                    if (supplied == null || !java.security.MessageDigest.isEqual(
                            ("Bearer " + token).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                        exchange.sendResponseHeaders(401, -1); return;
                    }
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                    respond(exchange, 200, "text/plain; charset=utf-8", "ok"); return;
                } else if (path.equals("/tasks") && exchange.getRequestMethod().equals("GET")) {
                    Map<String, String> tasks = new TreeMap<>();
                    long cursor = 0;
                    while (true) {
                        var page = store.getChanges(cursor, 1000);
                        for (var change : page.getRemoteChanges()) {
                            var op = change.getOperation();
                            if (!op.getEntityId().startsWith("task:")) continue;
                            String id = op.getEntityId().substring(5);
                            if (op.getType() == OperationType.DEL) tasks.remove(id);
                            else tasks.put(id, op.getPayload());
                        }
                        if (page.getRemoteChanges().isEmpty()) break;
                        cursor = page.getNextCursor();
                    }
                    StringJoiner result = new StringJoiner(",", "[", "]");
                    tasks.forEach((id, payload) -> result.add("{\"id\":" + json(id) + ",\"payload\":" + json(payload) + "}"));
                    respond(exchange, 200, "application/json; charset=utf-8", result.toString()); return;
                } else if (path.startsWith("/tasks/") && (exchange.getRequestMethod().equals("PUT") || exchange.getRequestMethod().equals("DELETE"))) {
                    String id = path.substring(7);
                    if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid task id");
                    boolean deleting = exchange.getRequestMethod().equals("DELETE");
                    byte[] body = exchange.getRequestBody().readNBytes(65537);
                    if (body.length > 65536 || (!deleting && body.length == 0)) throw new IllegalArgumentException("Invalid task payload");
                    String payload = deleting ? null : new String(body, StandardCharsets.UTF_8);
                    var op = new SyncOperation(UUID.randomUUID().toString(), "task:" + id, "task-api", 0L,
                            deleting ? OperationType.DEL : OperationType.SET, payload, System.currentTimeMillis());
                    store.applyOperation(op);
                    respond(exchange, 200, "application/json; charset=utf-8", "{\"id\":" + json(id) + "}"); return;
                } else if (path.equals("/operations") && exchange.getRequestMethod().equals("POST")) {
                    byte[] body = exchange.getRequestBody().readNBytes(4 * 1048576 + 1);
                    if (body.length > 4 * 1048576) throw new IllegalArgumentException("Body too large");
                    DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
                    var operation = Wire.INSTANCE.operation(input);
                    if (input.read() != -1) throw new IllegalArgumentException("Trailing data");
                    Wire.INSTANCE.result(out, store.applyOperation(operation));
                } else if (path.equals("/changes") && exchange.getRequestMethod().equals("GET")) {
                    long after = 0; int limit = 100;
                    String query = exchange.getRequestURI().getQuery();
                    if (query != null) for (String item : query.split("&")) {
                        String[] pair = item.split("=", 2);
                        if (pair[0].equals("after")) after = Long.parseLong(pair[1]);
                        else if (pair[0].equals("limit")) limit = Integer.parseInt(pair[1]);
                    }
                    if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid limit");
                    Wire.INSTANCE.page(out, store.getChanges(after, limit));
                } else { exchange.sendResponseHeaders(404, -1); return; }
                byte[] response = bytes.toByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.standalone.sync-v1");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                exchange.sendResponseHeaders(400, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(503, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }
    public int getPort() { return server.getAddress().getPort(); }
    private static void respond(HttpExchange exchange, int status, String type, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
    @Override public void close() { server.stop(0); }
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        var server = new SyncServer(new File(args.length > 1 ? args[1] : "sync-server-data.bin"), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Local sync server listening on " + server.getPort());
    }
}
