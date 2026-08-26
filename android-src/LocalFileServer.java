package com.larzos.beatstudio;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Serves ONE file over http://127.0.0.1:<port> so the WebView - loaded from a
 * remote https origin - can fetch a full-length recorded take without pushing
 * tens of MB of base64 through the Capacitor message bridge.
 *
 * Chromium treats http://127.0.0.1 as a "potentially trustworthy" origin, so the
 * https page is allowed to fetch it despite mixed content. The Private Network
 * Access preflight (public page -> local address) is answered with
 * Access-Control-Allow-Private-Network: true.
 */
final class LocalFileServer {

    private ServerSocket socket;
    private Thread thread;
    private volatile File file;
    private volatile String contentType = "application/octet-stream";
    private int port = -1;

    int start(File f, String type) throws IOException {
        stop();
        this.file = f;
        this.contentType = type != null ? type : "application/octet-stream";
        this.socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        this.port = socket.getLocalPort();
        this.thread = new Thread(this::loop, "bs-local-file-server");
        this.thread.setDaemon(true);
        this.thread.start();
        return port;
    }

    int getPort() { return port; }

    private void loop() {
        ServerSocket s = socket;
        while (s != null && !s.isClosed()) {
            Socket client = null;
            try {
                client = s.accept();
                handle(client);
            } catch (IOException e) {
                // socket closed (stop()) or a client error - keep serving
            } finally {
                if (client != null) {
                    try { client.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "US-ASCII"));
        String requestLine = in.readLine();
        String method = "GET";
        if (requestLine != null) {
            int sp = requestLine.indexOf(' ');
            if (sp > 0) method = requestLine.substring(0, sp);
        }
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) { /* drain headers */ }

        OutputStream out = client.getOutputStream();
        final String cors =
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n";

        if ("OPTIONS".equalsIgnoreCase(method)) {
            out.write(("HTTP/1.1 204 No Content\r\n" + cors + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            out.flush();
            return;
        }

        File f = file;
        if (f == null || !f.exists()) {
            out.write(("HTTP/1.1 404 Not Found\r\n" + cors + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            out.flush();
            return;
        }

        long len = f.length();
        String head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Content-Length: " + len + "\r\n" +
            "Cache-Control: no-store\r\n" +
            cors +
            "Connection: close\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));

        if (!"HEAD".equalsIgnoreCase(method)) {
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        out.flush();
    }

    void stop() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        File f = file;
        file = null;
        if (f != null && f.exists()) {
            try { boolean ignored = f.delete(); } catch (Exception ignored) {}
        }
        port = -1;
    }

    static byte[] readAll(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        return data;
    }
}
