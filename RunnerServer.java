import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Simple multithreaded HTTP server with a fixed thread pool.
 * - Serves files from ./www
 * - Supports GET and POST
 * - Returns 405 for other methods
 * - Logs: IP, method, path, responseCode -> server.log
 *
 * Usage:
 *   javac RunnerServer.java
 *   java RunnerServer 8080
 */
public class RunnerServer {

    private static final int DEFAULT_PORT = 8080;
    private static final int THREADS = 8;
    private static final Path WWW_ROOT = Paths.get("www");
    private static final Path LOG_FILE = Paths.get("server.log");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ServerSocket serverSocket;
    private final ExecutorService pool;
    private final Object logLock = new Object();

    public RunnerServer(int port, int threads) throws IOException {
        serverSocket = new ServerSocket(port);
        pool = Executors.newFixedThreadPool(threads);
        if (!Files.exists(WWW_ROOT)) {
            Files.createDirectories(WWW_ROOT);
        }
        // ensure log file exists
        if (!Files.exists(LOG_FILE)) Files.createFile(LOG_FILE);
    }

    public void start() {
        System.out.println("RunnerServer listening on " + serverSocket.getLocalPort());
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(new ClientHandler(client));
            } catch (IOException e) {
                System.err.println("Accept error: " + e.getMessage());
            }
        }
        shutdown();
    }

    public void shutdown() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
        pool.shutdown();
    }

    private void logRequest(String clientIp, String method, String path, int status) {
        String line = String.format("%s %s %s %s %d%n",
                LocalDateTime.now().format(TS_FMT),
                clientIp, method, path, status);
        synchronized (logLock) {
            try (BufferedWriter w = Files.newBufferedWriter(LOG_FILE, StandardOpenOption.APPEND)) {
                w.write(line);
            } catch (IOException e) {
                System.err.println("Failed to write log: " + e.getMessage());
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket sock;

        ClientHandler(Socket sock) {
            this.sock = sock;
        }

        @Override
        public void run() {
            String clientIp = sock.getInetAddress().getHostAddress();
            try (InputStream in = sock.getInputStream();
                 OutputStream out = sock.getOutputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {

                // Read request line
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    sock.close();
                    return;
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 3) {
                    respondSimple(out, 400, "Bad Request", "Malformed request line");
                    logRequest(clientIp, "-", "-", 400);
                    return;
                }
                String method = parts[0];
                String rawPath = parts[1];
                String version = parts[2];

                // Read headers
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String name = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        headers.put(name.toLowerCase(Locale.ROOT), value);
                    }
                }

                int responseCode = 200;
                if ("GET".equalsIgnoreCase(method)) {
                    responseCode = handleGet(rawPath, out);
                } else if ("POST".equalsIgnoreCase(method)) {
                    int contentLength = 0;
                    String cl = headers.get("content-length");
                    if (cl != null) {
                        try { contentLength = Integer.parseInt(cl.trim()); } catch (NumberFormatException ignored) {}
                    }
                    // read body (if any)
                    char[] bodyChars = new char[contentLength];
                    if (contentLength > 0) {
                        int read = 0;
                        while (read < contentLength) {
                            int r = reader.read(bodyChars, read, contentLength - read);
                            if (r == -1) break;
                            read += r;
                        }
                    }
                    String body = (contentLength > 0) ? new String(bodyChars) : "";
                    responseCode = handlePost(rawPath, body, out);
                } else {
                    responseCode = 405;
                    writeStatusOnly(out, 405, "Method Not Allowed");
                }

                logRequest(clientIp, method, rawPath, responseCode);

            } catch (IOException e) {
                System.err.println("Client handler error: " + e.getMessage());
            } finally {
                try { sock.close(); } catch (IOException ignored) {}
            }
        }

        private int handleGet(String rawPath, OutputStream out) throws IOException {
            // sanitize path
            String path = rawPath.split("\\?")[0];
            if (path.equals("/")) path = "/index.html";
            Path file = WWW_ROOT.resolve(path.substring(1)).normalize();
            if (!file.startsWith(WWW_ROOT)) {
                writeStatusOnly(out, 403, "Forbidden");
                return 403;
            }
            if (Files.exists(file) && !Files.isDirectory(file)) {
                String contentType = guessContentType(file);
                byte[] data = Files.readAllBytes(file);
                writeResponse(out, 200, "OK", contentType, data);
                return 200;
            } else {
                String body = "<html><body><h2>404 Not Found</h2><p>Resource not found.</p></body></html>";
                writeResponse(out, 404, "Not Found", "text/html; charset=utf-8", body.getBytes("UTF-8"));
                return 404;
            }
        }

        private int handlePost(String rawPath, String body, OutputStream out) throws IOException {
            // A simple echo endpoint for demonstration: /echo
            if (rawPath.equalsIgnoreCase("/echo")) {
                String html = "<html><body><h2>POST Echo</h2>"
                        + "<pre>" + escapeHtml(body) + "</pre>"
                        + "<p><a href=\"/\">Back</a></p>"
                        + "</body></html>";
                writeResponse(out, 200, "OK", "text/html; charset=utf-8", html.getBytes("UTF-8"));
                return 200;
            } else {
                // If POSTing to a resource that exists (e.g., form target), you can implement more logic.
                String bodyResp = "<html><body><h2>Received POST</h2><pre>" + escapeHtml(body) + "</pre></body></html>";
                writeResponse(out, 200, "OK", "text/html; charset=utf-8", bodyResp.getBytes("UTF-8"));
                return 200;
            }
        }

        private String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private String guessContentType(Path file) {
            try {
                String type = Files.probeContentType(file);
                return (type != null) ? type : "application/octet-stream";
            } catch (IOException e) {
                return "application/octet-stream";
            }
        }

        private void writeStatusOnly(OutputStream out, int code, String phrase) throws IOException {
            String resp = String.format("HTTP/1.1 %d %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", code, phrase);
            out.write(resp.getBytes("UTF-8"));
            out.flush();
        }

        private void respondSimple(OutputStream out, int code, String phrase, String message) throws IOException {
            byte[] body = ("<html><body><h2>" + code + " " + phrase + "</h2><p>" + message + "</p></body></html>").getBytes("UTF-8");
            writeResponse(out, code, phrase, "text/html; charset=utf-8", body);
        }

        private void writeResponse(OutputStream out, int code, String phrase, String contentType, byte[] body) throws IOException {
            String headers = String.format("HTTP/1.1 %d %s\r\nDate: %s\r\nServer: RunnerServer/1.0\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
                    code, phrase, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()), contentType, body.length);
            out.write(headers.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        }
    }

    // Entry point
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        int threads = THREADS;
        if (args.length >= 1) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 2) {
            try { threads = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        try {
            RunnerServer server = new RunnerServer(port, threads);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}
