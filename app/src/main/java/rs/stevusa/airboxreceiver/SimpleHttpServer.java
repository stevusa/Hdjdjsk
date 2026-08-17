package rs.stevusa.airboxreceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SimpleHttpServer {
    interface ControlHandler {
        void onPlay(String url);
        void onStopPlayback();
    }

    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private final int port;
    private final ControlHandler handler;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile String pendingUri;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    SimpleHttpServer(int port, ControlHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "AirBox-HTTP");
        acceptThread.start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        clients.shutdownNow();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            while (running) {
                Socket socket = serverSocket.accept();
                clients.execute(() -> handleClient(socket));
            }
        } catch (SocketException ignored) {
        } catch (IOException ignored) {
        } finally {
            running = false;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(5000);
            HttpRequest request = readRequest(client.getInputStream());
            if (request == null) return;
            route(request, client.getOutputStream());
        } catch (Exception ignored) {
        }
    }

    private void route(HttpRequest request, OutputStream out) throws IOException {
        String path = request.path;

        if ("OPTIONS".equals(request.method)) {
            writeResponse(out, 204, "text/plain; charset=utf-8", "");
            return;
        }

        if ("GET".equals(request.method) && "/".equals(path)) {
            writeResponse(out, 200, "text/html; charset=utf-8", controlPage());
            return;
        }

        if ("GET".equals(request.method) && "/device.xml".equals(path)) {
            writeResponse(out, 200, "text/xml; charset=utf-8", deviceDescription());
            return;
        }

        if ("GET".equals(request.method) && "/status".equals(path)) {
            String body = "{\"name\":\"AirBox Receiver\",\"running\":true,\"ip\":\"" +
                    jsonEscape(NetworkUtils.getLocalIpv4()) + "\",\"port\":" + port + "}";
            writeResponse(out, 200, "application/json; charset=utf-8", body);
            return;
        }

        if ("GET".equals(request.method) && "/play".equals(path)) {
            String url = request.query.get("url");
            if (url == null || url.trim().isEmpty()) {
                writeResponse(out, 400, "text/plain; charset=utf-8", "Missing url");
                return;
            }
            pendingUri = url;
            handler.onPlay(url);
            writeResponse(out, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            return;
        }

        if ("POST".equals(request.method) && "/play".equals(path)) {
            String url = request.body.trim();
            if (url.isEmpty()) {
                writeResponse(out, 400, "text/plain; charset=utf-8", "Missing media URL in body");
                return;
            }
            pendingUri = url;
            handler.onPlay(url);
            writeResponse(out, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            return;
        }

        if (("GET".equals(request.method) || "POST".equals(request.method)) && "/stop".equals(path)) {
            handler.onStopPlayback();
            writeResponse(out, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            return;
        }

        if ("POST".equals(request.method) &&
                ("/upnp/control/AVTransport1".equals(path) || "/control".equals(path))) {
            handleAvTransport(request, out);
            return;
        }

        writeResponse(out, 404, "text/plain; charset=utf-8", "Not found");
    }

    private void handleAvTransport(HttpRequest request, OutputStream out) throws IOException {
        String soapAction = request.headers.get("soapaction");
        String action = soapAction == null ? "" : soapAction.replace("\"", "");
        if (action.contains("#")) action = action.substring(action.indexOf('#') + 1);

        if (action.isEmpty()) {
            if (request.body.contains("SetAVTransportURI")) action = "SetAVTransportURI";
            else if (request.body.contains("GetTransportInfo")) action = "GetTransportInfo";
            else if (request.body.contains("GetPositionInfo")) action = "GetPositionInfo";
            else if (request.body.contains("Stop")) action = "Stop";
            else if (request.body.contains("Play")) action = "Play";
        }

        switch (action) {
            case "SetAVTransportURI":
                String uri = extractXmlValue(request.body, "CurrentURI");
                if (uri != null && !uri.isEmpty()) pendingUri = uri;
                writeSoapResponse(out, "SetAVTransportURI", "");
                break;
            case "Play":
                if (pendingUri != null && !pendingUri.isEmpty()) handler.onPlay(pendingUri);
                writeSoapResponse(out, "Play", "");
                break;
            case "Stop":
                handler.onStopPlayback();
                writeSoapResponse(out, "Stop", "");
                break;
            case "GetTransportInfo":
                writeSoapResponse(out, "GetTransportInfo",
                        "<CurrentTransportState>PLAYING</CurrentTransportState>" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                        "<CurrentSpeed>1</CurrentSpeed>");
                break;
            case "GetPositionInfo":
                writeSoapResponse(out, "GetPositionInfo",
                        "<Track>1</Track><TrackDuration>00:00:00</TrackDuration>" +
                        "<TrackMetaData></TrackMetaData><TrackURI>" + xmlEscape(pendingUri == null ? "" : pendingUri) + "</TrackURI>" +
                        "<RelTime>00:00:00</RelTime><AbsTime>00:00:00</AbsTime>" +
                        "<RelCount>0</RelCount><AbsCount>0</AbsCount>");
                break;
            default:
                writeSoapResponse(out, action.isEmpty() ? "Unknown" : action, "");
                break;
        }
    }

    private void writeSoapResponse(OutputStream out, String action, String inner) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:" + action + "Response xmlns:u=\"" + AV_TRANSPORT + "\">" +
                inner + "</u:" + action + "Response></s:Body></s:Envelope>";
        writeResponse(out, 200, "text/xml; charset=utf-8", body);
    }

    private HttpRequest readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (headerBytes.size() < 65536) {
            int b = in.read();
            if (b < 0) return null;
            headerBytes.write(b);
            if ((matched == 0 || matched == 2) && b == '\r') matched++;
            else if ((matched == 1 || matched == 3) && b == '\n') matched++;
            else matched = b == '\r' ? 1 : 0;
            if (matched == 4) break;
        }

        String headersText = headerBytes.toString("ISO-8859-1");
        String[] lines = headersText.split("\\r\\n");
        if (lines.length == 0) return null;
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) return null;

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }

        int length = 0;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        length = Math.max(0, Math.min(length, 4 * 1024 * 1024));
        byte[] bodyBytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bodyBytes, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        String body = new String(bodyBytes, 0, offset, StandardCharsets.UTF_8);

        String target = requestLine[1];
        String path = target;
        Map<String, String> query = new HashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            String rawQuery = target.substring(q + 1);
            for (String part : rawQuery.split("&")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String key = decode(eq >= 0 ? part.substring(0, eq) : part);
                String value = decode(eq >= 0 ? part.substring(eq + 1) : "");
                query.put(key, value);
            }
        }

        return new HttpRequest(requestLine[0].toUpperCase(Locale.US), path, query, headers, body);
    }

    private void writeResponse(OutputStream out, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : "Not Found";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, SOAPAction\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private String controlPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>AirBox Receiver</title>" +
                "<style>body{font-family:sans-serif;background:#111;color:#eee;max-width:760px;margin:40px auto;padding:20px}input,button{font-size:18px;padding:12px;margin:6px 0;width:100%;box-sizing:border-box}button{cursor:pointer}</style></head>" +
                "<body><h1>AirBox Receiver</h1><p>Pošalji direktan HTTP/HTTPS/RTSP media URL na TV box.</p>" +
                "<input id=\"u\" placeholder=\"https://example.com/video.mp4\"><button onclick=\"play()\">Pusti</button><button onclick=\"stopPlay()\">Stop</button>" +
                "<script>function play(){fetch('/play?url='+encodeURIComponent(document.getElementById('u').value))}function stopPlay(){fetch('/stop')}</script></body></html>";
    }

    private String deviceDescription() {
        String ip = NetworkUtils.getLocalIpv4();
        return "<?xml version=\"1.0\"?>" +
                "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion>" +
                "<URLBase>http://" + ip + ":" + port + "/</URLBase>" +
                "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
                "<friendlyName>AirBox Receiver</friendlyName><manufacturer>Stevusa</manufacturer>" +
                "<modelName>AirBox Receiver</modelName><modelNumber>0.1</modelNumber>" +
                "<UDN>uuid:4c155456-20ff-4be8-9000-airbox000001</UDN>" +
                "<serviceList><service><serviceType>" + AV_TRANSPORT + "</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>" +
                "<SCPDURL>/avtransport.xml</SCPDURL>" +
                "<controlURL>/upnp/control/AVTransport1</controlURL>" +
                "<eventSubURL>/upnp/event/AVTransport1</eventSubURL>" +
                "</service></serviceList></device></root>";
    }

    private static String extractXmlValue(String xml, String tag) {
        Pattern pattern = Pattern.compile("(?s)<(?:\\w+:)?" + Pattern.quote(tag) + "[^>]*>(.*?)</(?:\\w+:)?" + Pattern.quote(tag) + ">");
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? xmlUnescape(matcher.group(1).trim()) : null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String xmlUnescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final String body;

        HttpRequest(String method, String path, Map<String, String> query,
                    Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }
}
