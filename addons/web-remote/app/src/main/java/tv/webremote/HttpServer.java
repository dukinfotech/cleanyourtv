package tv.webremote;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server the TV runs: it serves the control page and, once a device has paired, one WebSocket per device carrying
 * its commands. No dependencies, plain sockets. A peer outside the home network is dropped before it is read; every
 * request must name an address (not a rebindable name) in Host and, when it names an Origin, that same address.
 */
final class HttpServer {

    static final int PORT = 6789;
    private static final int MAX_CONNECTIONS = 12, MAX_LINE = 2048, MAX_HEADERS = 8192, MAX_BODY = 1024;
    private static final int REQUEST_TIMEOUT_MS = 15_000, SOCKET_TIMEOUT_MS = 45_000;
    private static final String PAGE_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "connect-src 'self' ws: wss:; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static final class Session {
        final Socket socket;
        final OutputStream out;

        Session(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** state: whether this page is being sent frames; shared: whether the TV is sharing its screen at all. */
    /** state: whether this page is being sent sound; allowed: whether the TV lets pages listen at all. */
    private static String audioJson(boolean on, boolean allowed) {
        return "{\"t\":\"audio\",\"state\":\"" + (on ? "on" : "off") + "\",\"allowed\":" + allowed + "}";
    }

    /**
     * Sends sound to one page on its own thread. Sound is not skipped like video frames, but a page that cannot keep up
     * would otherwise grow the queue for ever, so it holds about a second and then drops the oldest chunk.
     */
    private static final class AudioFeed implements Controller.AudioSink, Runnable {
        private static final int MAX_QUEUED = 50;
        private final Session session;
        private final java.util.ArrayDeque<byte[]> queue = new java.util.ArrayDeque<>();
        private boolean closed;

        AudioFeed(Session session) {
            this.session = session;
            Thread t = new Thread(this, "web-remote-audio-feed");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public synchronized void audio(byte[] chunk) {
            if (closed) return;
            if (queue.size() >= MAX_QUEUED) queue.pollFirst();
            queue.addLast(chunk);
            notifyAll();
        }

        @Override
        public void ended() {
            try {
                WebSocketFrames.writeText(session.out, audioJson(false, false));
            } catch (IOException ignored) {
                // the page is gone
            }
            close();
        }

        synchronized boolean isClosed() {
            return closed;
        }

        synchronized void close() {
            closed = true;
            queue.clear();
            notifyAll();
        }

        @Override
        public void run() {
            while (true) {
                byte[] next;
                synchronized (this) {
                    while (queue.isEmpty() && !closed) {
                        try {
                            wait();
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                    if (closed) return;
                    next = queue.pollFirst();
                }
                try {
                    WebSocketFrames.writeBinary(session.out, next);
                } catch (IOException broken) {
                    session.close();
                    return;
                }
            }
        }
    }

    private static String screenJson(boolean on, boolean shared) {
        return "{\"t\":\"screen\",\"state\":\"" + (on ? "on" : "off") + "\",\"shared\":" + shared + "}";
    }

    /**
     * Sends the screen's frames to one page on its own thread, so a slow link never holds up the capture or the other
     * viewers: only the newest frame waits, any older one is dropped.
     */
    private static final class ScreenFeed implements Controller.FrameSink, Runnable {
        private final Session session;
        private byte[] latest;
        private boolean closed;
        private long statAt;

        private final Controller controller;

        ScreenFeed(Session session, Controller controller) {
            this.session = session;
            this.controller = controller;
            Thread t = new Thread(this, "web-remote-screen");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public synchronized void frame(byte[] jpeg) {
            latest = jpeg;
            notifyAll();
        }

        @Override
        public void ended() {
            try {
                WebSocketFrames.writeText(session.out, screenJson(false, false));
            } catch (IOException ignored) {
                // the page is gone
            }
            close();
        }

        synchronized boolean isClosed() {
            return closed;
        }

        synchronized void close() {
            closed = true;
            notifyAll();
        }

        @Override
        public void run() {
            while (true) {
                byte[] next;
                synchronized (this) {
                    while (latest == null && !closed) {
                        try {
                            wait();
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                    if (closed) return;
                    next = latest;
                    latest = null;
                }
                try {
                    WebSocketFrames.writeBinary(session.out, next);
                    long now = System.currentTimeMillis();
                    if (now - statAt >= 1_000) {
                        statAt = now;
                        WebSocketFrames.writeText(session.out, "{\"t\":\"stat\",\"enc\":" + controller.screenEncodeMs() + "}");
                    }
                } catch (IOException broken) {
                    session.close();
                    return;
                }
            }
        }
    }

    private final int port;
    private final byte[] page;
    private final byte[] touchIcon;
    private final Pairing pairing;
    private final CommandHandler commands;
    private final Controller controller;
    private final String tvName;
    private final AtomicInteger connections = new AtomicInteger();
    private final Map<String, List<Session>> sessions = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "web-remote");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket server;
    private volatile boolean running;

    HttpServer(int port, byte[] page, byte[] touchIcon, Pairing pairing, Controller controller, String tvName) {
        this.port = port;
        this.page = page;
        this.touchIcon = touchIcon;
        this.pairing = pairing;
        this.controller = controller;
        this.commands = new CommandHandler(controller);
        this.tvName = tvName;
    }

    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port), 16);
        server = s;
        running = true;
        pool.execute(this::acceptLoop);
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        server = null;
        synchronized (sessions) {
            for (List<Session> list : sessions.values()) for (Session s : list) s.close();
            sessions.clear();
        }
    }

    boolean isRunning() {
        return running;
    }

    /** The port actually bound: the one asked for, or the one the system picked when asked for 0. */
    synchronized int port() {
        return server == null ? port : server.getLocalPort();
    }

    int clientCount() {
        synchronized (sessions) {
            int n = 0;
            for (List<Session> list : sessions.values()) n += list.size();
            return n;
        }
    }

    /** Tells every connected page the app's language changed; the pages switch to it. */
    void broadcastLanguage(String lang) {
        List<Session> all = new ArrayList<>();
        synchronized (sessions) {
            for (List<Session> list : sessions.values()) all.addAll(list);
        }
        String message = "{\"t\":\"lang\",\"v\":\"" + ("vi".equals(lang) ? "vi" : "en") + "\"}";
        for (Session session : all) {
            try {
                WebSocketFrames.writeText(session.out, message);
            } catch (IOException broken) {
                session.close();
            }
        }
    }

    /** Ids of the paired devices that have a page open and connected right now. */
    java.util.Set<String> connectedIds() {
        synchronized (sessions) {
            return new java.util.HashSet<>(sessions.keySet());
        }
    }

    /** Tells every connection of this paired device it has been revoked, then drops it. */
    void disconnect(String deviceId) {
        List<Session> gone;
        synchronized (sessions) {
            gone = sessions.remove(deviceId);
        }
        if (gone == null) return;
        for (Session s : gone) {
            try {
                WebSocketFrames.writeText(s.out, "{\"t\":\"revoked\"}");
            } catch (IOException ignored) {
                // dropping it anyway
            }
            s.close();
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException | NullPointerException closed) {
                return;
            }
            if (!NetPolicy.isAllowedPeer(socket.getInetAddress()) || connections.incrementAndGet() > MAX_CONNECTIONS) {
                if (connections.get() > MAX_CONNECTIONS) connections.decrementAndGet();
                closeQuietly(socket);
                continue;
            }
            pool.execute(() -> {
                try {
                    serve(socket);
                } catch (IOException | RuntimeException ignored) {
                    // a broken or hostile connection ends here
                } finally {
                    closeQuietly(socket);
                    connections.decrementAndGet();
                }
            });
        }
    }

    private void serve(Socket socket) throws IOException {
        socket.setSoTimeout(REQUEST_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(socket.getInputStream(), 4096);
        OutputStream out = socket.getOutputStream();

        String requestLine = readLine(in, MAX_LINE);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            respond(out, 400, "Bad Request", "text/plain", "bad request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        for (String line = readLine(in, MAX_LINE); line != null && !line.isEmpty(); line = readLine(in, MAX_LINE)) {
            total += line.length();
            if (total > MAX_HEADERS) {
                respond(out, 431, "Request Header Fields Too Large", "text/plain", new byte[0]);
                return;
            }
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }

        String host = headers.get("host");
        if (!NetPolicy.isAcceptableHost(host)) {
            respond(out, 421, "Misdirected Request", "text/plain", "use the TV's address".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!NetPolicy.originMatches(headers.get("origin"), host)) {
            respond(out, 403, "Forbidden", "text/plain", "wrong origin".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String method = parts[0], target = parts[1];
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        String query = q < 0 ? "" : target.substring(q + 1);

        if (method.equals("GET") && "websocket".equalsIgnoreCase(headers.get("upgrade")) && path.equals("/ws")) {
            webSocket(socket, in, out, headers, query);
        } else if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            respond(out, 200, "OK", "text/html; charset=utf-8", page, "Content-Security-Policy: " + PAGE_CSP);
        } else if (method.equals("GET") && path.equals("/icon")) {
            icon(out, query, false);
        } else if (method.equals("GET") && path.equals("/banner")) {
            icon(out, query, true);
        } else if (method.equals("GET") && (path.equals("/apple-touch-icon.png") || path.equals("/apple-touch-icon-precomposed.png"))) {
            // The icon iOS uses for "Add to Home Screen". It is the same picture as the TV app's icon; nothing private.
            respond(out, 200, "OK", "image/png", touchIcon, "Cache-Control: public, max-age=86400");
        } else if (method.equals("GET") && path.equals("/favicon.ico")) {
            respond(out, 204, "No Content", "image/x-icon", new byte[0]);
        } else if (method.equals("POST") && path.equals("/api/pair")) {
            pair(in, out, headers);
        } else {
            respond(out, 404, "Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** GET /icon?id=<package>&token=<token> (or /banner): an app's icon, or its banner, for the page's app list; a paired device only. */
    private void icon(OutputStream out, String query, boolean banner) throws IOException {
        if (pairing.authenticate(queryValue(query, "token"), System.currentTimeMillis()) == null) {
            respond(out, 401, "Unauthorized", "text/plain", new byte[0]);
            return;
        }
        String id = queryValue(query, "id");
        boolean valid = id != null && id.matches("[A-Za-z0-9._]{1,200}");
        byte[] png = !valid ? null : banner ? controller.appBanner(id) : controller.appIcon(id);
        if (png == null) {
            respond(out, 404, "Not Found", "text/plain", new byte[0]);
            return;
        }
        respond(out, 200, "OK", "image/png", png, "Cache-Control: private, max-age=86400");
    }

    private void pair(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        String type = headers.get("content-type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            respond(out, 415, "Unsupported Media Type", "text/plain", new byte[0]);
            return;
        }
        int length;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException bad) {
            length = -1;
        }
        if (length < 0 || length > MAX_BODY) {
            respond(out, 413, "Payload Too Large", "text/plain", new byte[0]);
            return;
        }
        byte[] body = new byte[length];
        int done = 0;
        while (done < length) {
            int n = in.read(body, done, length - done);
            if (n < 0) return;
            done += n;
        }
        String pin = "", name = "";
        try {
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            pin = o.optString("pin");
            name = o.optString("name");
        } catch (JSONException malformed) {
            respond(out, 400, "Bad Request", "text/plain", new byte[0]);
            return;
        }
        Pairing.Result result = pairing.pair(pin, name, System.currentTimeMillis());
        try {
            JSONObject json = new JSONObject();
            int code = 200;
            String reason = "OK";
            if (result.status == Pairing.Status.OK) {
                json.put("token", result.token);
            } else {
                code = 403;
                reason = "Forbidden";
                json.put("error", result.status == Pairing.Status.BAD_PIN ? "bad_pin"
                        : result.status == Pairing.Status.LOCKED ? "locked" : "closed");
                if (result.status == Pairing.Status.LOCKED) json.put("retry", result.retrySeconds);
            }
            respond(out, code, reason, "application/json", json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
    }

    private void webSocket(Socket socket, InputStream in, OutputStream out, Map<String, String> headers, String query)
            throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null || !"13".equals(headers.get("sec-websocket-version"))) {
            respond(out, 400, "Bad Request", "text/plain", new byte[0]);
            return;
        }
        Pairing.Device device = pairing.authenticate(queryValue(query, "token"), System.currentTimeMillis());
        if (device == null) {
            respond(out, 401, "Unauthorized", "text/plain", new byte[0]);
            return;
        }
        String head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptKey(key) + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);

        pairing.noteAddress(device.id, socket.getInetAddress().getHostAddress());
        Session session = new Session(socket, out);
        synchronized (sessions) {
            List<Session> list = sessions.get(device.id);
            if (list == null) {
                list = new ArrayList<>();
                sessions.put(device.id, list);
            }
            list.add(session);
        }
        ScreenFeed feed = null;
        AudioFeed listen = null;
        try {
            CommandHandler.Reply reply = json -> {
                try {
                    WebSocketFrames.writeText(out, json);
                } catch (IOException broken) {
                    session.close();
                }
            };
            reply.send(CommandHandler.helloJson(controller.screenWidth(), controller.screenHeight(), tvName, controller.language()));
            while (running) {
                String message = WebSocketFrames.readText(in, out, CommandHandler.MAX_MESSAGE);
                if (message == null) break;
                if (CommandHandler.isUnpair(message)) {
                    // The page's own "Hủy ghép": revoke this device only, as the TV's Thu hồi button does.
                    pairing.revoke(device.id);
                    disconnect(device.id);
                    break;
                }
                Boolean screen = CommandHandler.screenRequest(message);
                if (screen != null) {
                    if (feed != null && feed.isClosed()) {
                        // Sharing was stopped under this page; a later request starts a new feed.
                        controller.screenUnsubscribe(feed);
                        feed = null;
                    }
                    if (screen && feed == null && controller.screenReady()) {
                        feed = new ScreenFeed(session, controller);
                        controller.screenSubscribe(feed);
                    } else if (!screen && feed != null) {
                        controller.screenUnsubscribe(feed);
                        feed.close();
                        feed = null;
                    }
                    reply.send(screenJson(feed != null, controller.screenReady()));
                    continue;
                }
                Boolean sound = CommandHandler.audioRequest(message);
                if (sound != null) {
                    if (listen != null && listen.isClosed()) {
                        // Sharing or the sound switch was stopped under this page; a later request starts again.
                        controller.audioUnsubscribe(listen);
                        listen = null;
                    }
                    if (sound && listen == null && controller.audioReady()) {
                        AudioFeed fresh = new AudioFeed(session);
                        if (controller.audioSubscribe(fresh)) {
                            listen = fresh;
                        } else {
                            fresh.close();
                        }
                    } else if (!sound && listen != null) {
                        controller.audioUnsubscribe(listen);
                        listen.close();
                        listen = null;
                    }
                    reply.send(audioJson(listen != null, controller.audioReady()));
                    continue;
                }
                commands.handle(message, reply);
            }
        } finally {
            if (listen != null) {
                controller.audioUnsubscribe(listen);
                listen.close();
            }
            if (feed != null) {
                controller.screenUnsubscribe(feed);
                feed.close();
            }
            synchronized (sessions) {
                List<Session> list = sessions.get(device.id);
                if (list != null) {
                    list.remove(session);
                    if (list.isEmpty()) sessions.remove(device.id);
                }
            }
        }
    }

    private static String queryValue(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException impossible) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean hasHeader(String[] headers, String name) {
        for (String h : headers) if (h.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return true;
        return false;
    }

    private static void respond(OutputStream out, int code, String reason, String type, byte[] body, String... extra)
            throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append(hasHeader(extra, "Cache-Control") ? "" : "Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Connection: close\r\n");
        for (String h : extra) head.append(h).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** A line without its CR LF; null at the end of the stream; an error when it is longer than max. */
    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') return line.toString("UTF-8").replace("\r", "");
            if (line.size() >= max) throw new IOException("line too long");
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString("UTF-8");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
