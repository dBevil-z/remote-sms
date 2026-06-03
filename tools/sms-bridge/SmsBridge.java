import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SmsBridge {
    private static final int PORT = 8790;

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
        while (true) {
            final Socket socket = server.accept();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    handle(socket);
                }
            }).start();
        }
    }

    private static void handle(Socket socket) {
        try (Socket closeable = socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line != null && line.startsWith("GET /health ")) {
                write(socket, 200, "{\"ok\":true}");
                return;
            }
            if (line == null || !line.startsWith("POST /send ")) {
                write(socket, 404, "{\"ok\":false,\"error\":\"not found\"}");
                return;
            }

            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon))) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }

            char[] chars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int count = reader.read(chars, read, contentLength - read);
                if (count < 0) break;
                read += count;
            }

            JSONObject request = new JSONObject(new String(chars, 0, read));
            int subId = request.optInt("subscriptionId", 6);
            String recipient = request.optString("recipient", "").trim();
            String body = request.optString("body", "");
            if (recipient.isEmpty() || body.trim().isEmpty()) {
                write(socket, 400, "{\"ok\":false,\"error\":\"recipient/body required\"}");
                return;
            }

            ProcessResult result = send(subId, recipient, body);
            JSONObject response = new JSONObject();
            response.put("ok", result.exitCode == 0);
            response.put("exitCode", result.exitCode);
            response.put("output", result.output);
            write(socket, result.exitCode == 0 ? 200 : 500, response.toString());
        } catch (Exception error) {
            try {
                write(socket, 500, new JSONObject().put("ok", false).put("error", error.toString()).toString());
            } catch (Exception ignored) {
            }
        }
    }

    private static ProcessResult send(int subId, String recipient, String body) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("service");
        command.add("call");
        command.add("isms");
        command.add("6");
        command.add("i32");
        command.add(String.valueOf(subId > 0 ? subId : 6));
        command.add("s16");
        command.add("dev.dbevil.remotesms");
        command.add("s16");
        command.add(recipient);
        command.add("s16");
        command.add("null");
        command.add("s16");
        command.add(body);
        command.add("i32");
        command.add("0");
        command.add("i32");
        command.add("0");
        command.add("i32");
        command.add("1");

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return new ProcessResult(process.waitFor(), output.toString().trim());
    }

    private static void write(Socket socket, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
