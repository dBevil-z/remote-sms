package dev.dbevil.remotesms;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EmbeddedHttpServer {
    private static final String TAG = "RemoteSms";
    private static final int PORT = 8787;
    private static EmbeddedHttpServer instance;

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;

    private EmbeddedHttpServer(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized void start(Context context) {
        if (instance == null) instance = new EmbeddedHttpServer(context);
        instance.startInternal();
    }

    private synchronized void startInternal() {
        if (running) return;
        running = true;
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    executor.execute(() -> handle(socket));
                }
            } catch (Exception error) {
                Log.w(TAG, "web server stopped", error);
                running = false;
            }
        });
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(closeable.getOutputStream(), StandardCharsets.UTF_8))) {
            InputStream input = closeable.getInputStream();
            String headerBlock = readHeaderBlock(input);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.io.ByteArrayInputStream(headerBlock.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8
            ));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
                if ("content-length".equals(key)) contentLength = Integer.parseInt(value);
            }
            String body = new String(readExact(input, contentLength), StandardCharsets.UTF_8);

            if (target.startsWith("/api/messages")) {
                if (!authorized(headers, target)) {
                    send(writer, 401, "application/json; charset=utf-8", "{\"error\":\"未授权\"}");
                    return;
                }
                Map<String, String> params = query(target);
                int limit = params.containsKey("limit") ? parseInt(params.get("limit"), 10) : 10;
                int offset = params.containsKey("offset") ? parseInt(params.get("offset"), 0) : 0;
                String search = params.get("q");
                String direction = params.get("direction");
                String sim = params.get("sim");
                boolean codeOnly = "1".equals(params.get("codeOnly"));
                JSONObject response = new JSONObject();
                response.put("messages", LocalMessageStore.list(context, offset, limit, search, direction, sim, codeOnly));
                response.put("total", LocalMessageStore.count(context, search, direction, sim, codeOnly));
                response.put("offset", Math.max(offset, 0));
                response.put("limit", Math.max(limit, 1));
                send(writer, 200, "application/json; charset=utf-8", response.toString());
                return;
            }

            if (target.startsWith("/api/sims")) {
                if (!authorized(headers, target)) {
                    send(writer, 401, "application/json; charset=utf-8", "{\"error\":\"未授权\"}");
                    return;
                }
                JSONObject response = new JSONObject();
                response.put("sims", SmsSendService.listSims(context));
                send(writer, 200, "application/json; charset=utf-8", response.toString());
                return;
            }

            if (target.startsWith("/api/device")) {
                if (!authorized(headers, target)) {
                    send(writer, 401, "application/json; charset=utf-8", "{\"error\":\"未授权\"}");
                    return;
                }
                send(writer, 200, "application/json; charset=utf-8", DeviceStatus.snapshot(context).toString());
                return;
            }

            if (target.startsWith("/api/send")) {
                if (!authorized(headers, target)) {
                    send(writer, 401, "application/json; charset=utf-8", "{\"error\":\"未授权\"}");
                    return;
                }
                if (!"POST".equals(method)) {
                    send(writer, 400, "application/json; charset=utf-8", "{\"error\":\"请使用 POST 发送\"}");
                    return;
                }
                try {
                    JSONObject request = new JSONObject(body);
                    int subscriptionId = request.optInt("subscriptionId", -1);
                    String recipient = request.optString("recipient", "");
                    String message = request.optString("body", "");
                    JSONObject response = SmsSendService.send(context, subscriptionId, recipient, message);
                    send(writer, 200, "application/json; charset=utf-8", response.toString());
                } catch (Exception error) {
                    JSONObject response = new JSONObject();
                    response.put("error", error.getMessage() == null ? "发送失败" : error.getMessage());
                    send(writer, 400, "application/json; charset=utf-8", response.toString());
                }
                return;
            }

            if (target.startsWith("/api/config")) {
                if (!authorized(headers, target)) {
                    send(writer, 401, "application/json; charset=utf-8", "{\"error\":\"未授权\"}");
                    return;
                }
                if ("POST".equals(method)) {
                    JSONObject request = new JSONObject(body);
                    String token = request.optString("token", "").trim();
                    if (!token.isEmpty()) {
                        if (token.length() < 8) {
                            send(writer, 400, "application/json; charset=utf-8", "{\"error\":\"密码至少需要 8 位\"}");
                            return;
                        }
                        Config.setToken(context, token);
                    }
                    Config.FrpConfig current = Config.frpConfig(context);
                    Config.setFrpConfig(context, new Config.FrpConfig(
                            request.optString("publicUrl", current.publicUrl),
                            request.optString("frpServerAddr", current.serverAddr),
                            request.optString("frpServerPort", current.serverPort),
                            request.optString("frpAuthToken", current.authToken),
                            request.optString("frpRemotePort", current.remotePort)
                    ));
                }
                JSONObject response = Config.configJson(context);
                response.put("deviceId", Config.deviceId(context));
                response.put("port", PORT);
                send(writer, 200, "application/json; charset=utf-8", response.toString());
                return;
            }

            if (target.startsWith("/health")) {
                send(writer, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                return;
            }

            send(writer, 200, "text/html; charset=utf-8", html(context));
        } catch (Exception error) {
            Log.w(TAG, "web request failed", error);
        }
    }

    private static String readHeaderBlock(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        int value;
        byte[] end = new byte[]{'\r', '\n', '\r', '\n'};
        while ((value = input.read()) != -1) {
            output.write(value);
            if ((byte) value == end[matched]) {
                matched++;
                if (matched == end.length) break;
            } else {
                matched = (byte) value == end[0] ? 1 : 0;
            }
            if (output.size() > 64 * 1024) throw new IllegalArgumentException("请求头过大");
        }
        return output.toString("UTF-8");
    }

    private static byte[] readExact(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) break;
            offset += count;
        }
        if (offset == length) return bytes;
        byte[] trimmed = new byte[offset];
        System.arraycopy(bytes, 0, trimmed, 0, offset);
        return trimmed;
    }

    private boolean authorized(Map<String, String> headers, String target) {
        String token = Config.token(context);
        String auth = headers.get("authorization");
        if (("Bearer " + token).equals(auth)) return true;
        String queryToken = query(target).get("token");
        return token.equals(queryToken);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, String> query(String target) {
        Map<String, String> params = new HashMap<>();
        int question = target.indexOf('?');
        if (question < 0 || question == target.length() - 1) return params;
        String raw = target.substring(question + 1);
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            String key = decode(pair.substring(0, equals));
            String value = decode(pair.substring(equals + 1));
            params.put(key, value);
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void send(BufferedWriter writer, int status, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writer.write("HTTP/1.1 " + status + " " + statusText(status) + "\r\n");
        writer.write("Content-Type: " + type + "\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Cache-Control: no-store\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(body);
        writer.flush();
    }

    private static String statusText(int status) {
        if (status == 200) return "OK";
        if (status == 400) return "Bad Request";
        if (status == 401) return "Unauthorized";
        return "Error";
    }

    private static String html(Context context) {
        try (InputStream input = context.getAssets().open("index.html");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return html();
        }
    }

    private static String html() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'%3E%3Crect width='48' height='48' rx='10' fill='%23D9EEF3'/%3E%3Cpath fill='%23176B87' d='M14 13h22c2.2 0 4 1.8 4 4v14c0 2.2-1.8 4-4 4H26l-7 5v-5h-5c-2.2 0-4-1.8-4-4V17c0-2.2 1.8-4 4-4z'/%3E%3Cpath fill='white' fill-opacity='.96' d='M17 20h18v3H17z'/%3E%3Cpath fill='white' fill-opacity='.78' d='M17 26h13v3H17z'/%3E%3Ccircle cx='35' cy='34' r='4' fill='%2362BFD6'/%3E%3C/svg%3E\">"
                + "<link rel=\"apple-touch-icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'%3E%3Crect width='48' height='48' rx='10' fill='%23D9EEF3'/%3E%3Cpath fill='%23176B87' d='M14 13h22c2.2 0 4 1.8 4 4v14c0 2.2-1.8 4-4 4H26l-7 5v-5h-5c-2.2 0-4-1.8-4-4V17c0-2.2 1.8-4 4-4z'/%3E%3Cpath fill='white' fill-opacity='.96' d='M17 20h18v3H17z'/%3E%3Cpath fill='white' fill-opacity='.78' d='M17 26h13v3H17z'/%3E%3Ccircle cx='35' cy='34' r='4' fill='%2362BFD6'/%3E%3C/svg%3E\">"
                + "<title>短信接收助手</title><style>"
                + ":root{color-scheme:light dark;--text:#1f2d31;--muted:#65767b;--line:rgba(255,255,255,.55);--glass:rgba(255,255,255,.62);--glass-strong:rgba(255,255,255,.8);--accent:#176b87;--accent-soft:#d9eef3;--shadow:0 18px 48px rgba(31,55,62,.16)}"
                + "@media(prefers-color-scheme:dark){:root{--text:#eef5f5;--muted:#a8b7ba;--line:rgba(255,255,255,.12);--glass:rgba(28,34,38,.64);--glass-strong:rgba(34,40,45,.84);--accent:#62bfd6;--accent-soft:rgba(98,191,214,.18);--shadow:0 18px 48px rgba(0,0,0,.35)}}"
                + "*{box-sizing:border-box}body{min-height:100vh;margin:0;color:var(--text);font:15px/1.45 system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:linear-gradient(135deg,#f2f7f6,#d9ecee 46%,#f5efe4)}"
                + "body:before{content:'';position:fixed;inset:0;background:linear-gradient(180deg,rgba(255,255,255,.36),rgba(255,255,255,0));pointer-events:none}"
                + "button,input,select,textarea{font:inherit}button{min-height:38px;border:1px solid var(--line);border-radius:999px;background:var(--glass-strong);color:var(--text);padding:0 14px;cursor:pointer;backdrop-filter:blur(14px)}button.primary{border-color:transparent;background:var(--accent);color:white}input,select,textarea{width:100%;min-height:42px;border:1px solid var(--line);border-radius:12px;background:var(--glass-strong);color:var(--text);padding:0 12px;outline:none}textarea{min-height:120px;padding:10px 12px;resize:vertical}"
                + ".shell{position:relative;max-width:960px;margin:0 auto;padding:18px}.top{position:sticky;top:0;z-index:2;display:flex;align-items:center;justify-content:space-between;gap:14px;margin:-18px -18px 18px;padding:18px;background:rgba(255,255,255,.42);border-bottom:1px solid var(--line);backdrop-filter:blur(18px)}.brand{display:flex;align-items:center;gap:10px;min-width:0}.brandIcon{width:38px;height:38px;flex:0 0 38px;border-radius:10px;box-shadow:0 8px 22px rgba(23,107,135,.18)}"
                + "h1{margin:0;font-size:22px;letter-spacing:0}.sub{margin-top:3px;color:var(--muted);font-size:13px}.actions{display:flex;gap:8px;flex-shrink:0}.status{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px;padding:14px 16px;border:1px solid var(--line);border-radius:18px;background:var(--glass);box-shadow:var(--shadow);backdrop-filter:blur(18px)}"
                + ".dot{width:10px;height:10px;border-radius:50%;background:#d58c22;box-shadow:0 0 0 4px rgba(213,140,34,.14)}.ok .dot{background:#1c9a65;box-shadow:0 0 0 4px rgba(28,154,101,.14)}.list{display:grid;gap:12px}.message{padding:16px;border:1px solid var(--line);border-radius:18px;background:var(--glass);box-shadow:var(--shadow);backdrop-filter:blur(18px)}"
                + ".dashboard{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:14px}.metric{min-height:112px;padding:13px 14px;border:1px solid var(--line);border-radius:18px;background:var(--glass);box-shadow:var(--shadow);backdrop-filter:blur(18px)}.metric h3{margin:0 0 9px;font-size:13px;color:var(--muted);font-weight:700}.metric strong{display:block;font-size:23px;line-height:1.1}.metric small{display:block;margin-top:5px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.bar{height:8px;margin-top:11px;border-radius:999px;background:rgba(111,126,130,.18);overflow:hidden}.fill{height:100%;width:0;border-radius:inherit;background:var(--accent)}.fill.warn{background:#d58c22}.fill.bad{background:#bd3434}.facts{grid-column:span 4;min-height:0}.compactFacts{display:flex;flex-wrap:wrap;gap:6px}.chip{display:inline-flex;align-items:center;min-height:28px;max-width:100%;padding:0 9px;border:1px solid var(--line);border-radius:999px;background:rgba(255,255,255,.36);color:var(--text);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.chip b{margin-right:4px;color:var(--muted);font-weight:600}.fact{min-height:58px;padding:10px 12px;border-radius:14px;background:rgba(255,255,255,.36);border:1px solid var(--line)}.fact span{display:block;color:var(--muted);font-size:12px}.fact b{display:block;margin-top:4px;word-break:break-word}"
                + ".meta{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px;margin-bottom:10px;color:var(--muted);font-size:13px}.sender{color:var(--text);font-weight:700}.body{white-space:pre-wrap;word-break:break-word;font-size:16px}.empty{padding:30px;text-align:center;color:var(--muted)}.tag{display:inline-flex;align-items:center;min-height:22px;padding:0 8px;border-radius:999px;background:var(--accent-soft);color:var(--accent);font-size:12px;font-weight:700}.tag.fail{background:rgba(204,71,71,.14);color:#bd3434}.tag.wait{background:rgba(213,140,34,.16);color:#a06617}"
                + ".modal{position:fixed;inset:0;z-index:5;display:none;align-items:center;justify-content:center;padding:18px;background:rgba(20,30,34,.32);backdrop-filter:blur(12px)}.modal.open{display:flex}.panel{width:min(420px,100%);border:1px solid var(--line);border-radius:20px;background:var(--glass-strong);box-shadow:var(--shadow);padding:18px;backdrop-filter:blur(22px)}"
                + ".panel h2{margin:0 0 6px;font-size:18px}.panel p{margin:0 0 14px;color:var(--muted);font-size:13px}.panel-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}.field{margin-top:10px}.field label{display:block;margin:0 0 6px;color:var(--muted);font-size:13px}.pager{display:flex;align-items:center;justify-content:center;gap:10px;margin:16px 0 2px;color:var(--muted);font-size:13px}.pager button:disabled{opacity:.45;cursor:not-allowed}"
                + "@media(max-width:840px){.dashboard{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.facts{grid-column:span 2}}@media(max-width:640px){.shell{padding:12px}.top{margin:-12px -12px 12px;padding:12px}.top{align-items:flex-start}.brand{gap:8px}.brandIcon{width:32px;height:32px;flex-basis:32px;border-radius:8px}.actions{margin-top:2px;gap:6px}.actions button{min-height:34px;padding:0 11px}h1{font-size:19px}.sub{font-size:12px}.status{align-items:flex-start;flex-direction:column;margin-bottom:10px;padding:11px 13px;border-radius:14px}.message{border-radius:14px}.dashboard{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-bottom:10px}.metric{min-height:76px;padding:10px 11px;border-radius:14px}.metric h3{margin-bottom:5px;font-size:12px}.metric strong{font-size:20px}.metric small{font-size:12px;margin-top:3px}.bar{height:6px;margin-top:8px}.facts{grid-column:span 2;padding:9px 10px}.compactFacts{gap:5px}.chip{min-height:24px;padding:0 8px;font-size:11px}}"
                + "</style></head><body><div class=\"shell\"><header class=\"top\"><div class=\"brand\"><svg class=\"brandIcon\" viewBox=\"0 0 48 48\" aria-hidden=\"true\"><rect width=\"48\" height=\"48\" rx=\"10\" fill=\"#D9EEF3\"/><path fill=\"#176B87\" d=\"M14 13h22c2.2 0 4 1.8 4 4v14c0 2.2-1.8 4-4 4H26l-7 5v-5h-5c-2.2 0-4-1.8-4-4V17c0-2.2 1.8-4 4-4z\"/><path fill=\"#fff\" fill-opacity=\".96\" d=\"M17 20h18v3H17z\"/><path fill=\"#fff\" fill-opacity=\".78\" d=\"M17 26h13v3H17z\"/><circle cx=\"35\" cy=\"34\" r=\"4\" fill=\"#62BFD6\"/></svg><div><h1>短信接收助手</h1><div class=\"sub\">坚果手机正在本机保存和展示短信</div></div></div><div class=\"actions\"><button id=\"sendOpen\">发短信</button><button id=\"settings\">设置</button><button class=\"primary\" id=\"refresh\">刷新</button></div></header>"
                + "<section class=\"status\" id=\"statusBox\"><div><strong id=\"status\">未连接</strong><div class=\"sub\" id=\"hint\">读取短信前需要访问密码</div></div><span class=\"dot\"></span></section><section class=\"dashboard\" id=\"deviceBoard\"></section><section class=\"list\" id=\"messages\"></section><div class=\"pager\"><button id=\"prevPage\">上一页</button><span id=\"pageInfo\">第 1 页</span><button id=\"nextPage\">下一页</button></div></div>"
                + "<div class=\"modal\" id=\"modal\"><div class=\"panel\"><h2>访问设置</h2><p>填写网页访问密码，也可以在这里修改手机端密码。</p><input id=\"token\" type=\"password\" placeholder=\"访问密码\"><input id=\"newToken\" type=\"password\" placeholder=\"新的手机端访问密码\" style=\"margin-top:10px\"><div class=\"panel-actions\"><button id=\"close\">取消</button><button id=\"save\">保存并读取</button><button class=\"primary\" id=\"updateToken\">更新密码</button></div></div></div>"
                + "<div class=\"modal\" id=\"sendModal\"><div class=\"panel\"><h2>发送短信</h2><p>选择要使用的 SIM 卡，填写号码和短信内容。</p><div class=\"field\"><label>发送 SIM</label><select id=\"simSelect\"></select></div><div class=\"field\"><label>收件号码</label><input id=\"recipient\" inputmode=\"tel\" placeholder=\"手机号或短信号码\"></div><div class=\"field\"><label>短信内容</label><textarea id=\"smsBody\" placeholder=\"输入要发送的短信内容\"></textarea></div><div class=\"panel-actions\"><button id=\"sendClose\">取消</button><button class=\"primary\" id=\"sendSms\">发送</button></div></div></div>"
                + "<script>"
                + "const tokenInput=document.querySelector('#token'),newToken=document.querySelector('#newToken'),statusEl=document.querySelector('#status'),hintEl=document.querySelector('#hint'),statusBox=document.querySelector('#statusBox'),listEl=document.querySelector('#messages'),deviceBoard=document.querySelector('#deviceBoard'),modal=document.querySelector('#modal'),sendModal=document.querySelector('#sendModal'),simSelect=document.querySelector('#simSelect'),recipient=document.querySelector('#recipient'),smsBody=document.querySelector('#smsBody'),pageInfo=document.querySelector('#pageInfo'),prevPage=document.querySelector('#prevPage'),nextPage=document.querySelector('#nextPage');let messages=[],sims=[],offset=0,total=0;const pageSize=10;"
                + "const urlToken=new URLSearchParams(location.search).get('token');if(urlToken){localStorage.setItem('smsToken',urlToken)}tokenInput.value=localStorage.getItem('smsToken')||'';function headers(){return tokenInput.value?{Authorization:'Bearer '+tokenInput.value}:{}}"
                + "function esc(v){return String(v).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('\"','&quot;').replaceAll(\"'\",'&#039;')}function openModal(){modal.classList.add('open');setTimeout(()=>tokenInput.focus(),30)}function closeModal(){modal.classList.remove('open')}"
                + "function statusTag(m){if(m.direction!=='outgoing')return '<span class=\"tag\">收到</span>';const text=m.statusText||'发送中';const cls=m.status==='failed'?' fail':(m.status==='sending'?' wait':'');return '<span class=\"tag'+cls+'\">'+esc(text)+'</span>'}"
                + "function bytes(v){if(!v)return '0 B';const u=['B','KB','MB','GB','TB'];let n=Number(v),i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return (i? n.toFixed(1):Math.round(n))+' '+u[i]}function uptime(ms){let s=Math.floor(Number(ms||0)/1000),d=Math.floor(s/86400);s%=86400;let h=Math.floor(s/3600),m=Math.floor((s%3600)/60);return (d?d+'天 ':'')+h+'时'+m+'分'}function fillClass(p,mode){if(mode==='battery')return p<=20?'bad':(p<=40?'warn':'');return p>=90?'bad':(p>=75?'warn':'')}function meter(title,value,sub,p,mode){return '<article class=\"metric\"><h3>'+esc(title)+'</h3><strong>'+esc(value)+'</strong><small title=\"'+esc(sub)+'\">'+esc(sub)+'</small><div class=\"bar\"><div class=\"fill '+fillClass(Number(p||0),mode)+'\" style=\"width:'+Math.max(0,Math.min(100,Number(p||0)))+'%\"></div></div></article>'}function chip(k,v){return '<span class=\"chip\" title=\"'+esc(k+' '+(v||'未知'))+'\"><b>'+esc(k)+'</b>'+esc(v||'未知')+'</span>'}"
                + "function renderDevice(d){if(!d){deviceBoard.innerHTML='';return}const b=d.battery||{},mem=d.memory||{},st=d.storage||{},dev=d.device||{},net=d.network||{},svc=d.services||{},sms=d.sms||{};const batt=b.level==null?0:Number(b.level);const sim=(sms.sims||[]).map(s=>'卡'+(Number(s.slotIndex)+1)+' '+(s.carrierName||s.displayName||'SIM')).join('；')||'未读取';deviceBoard.innerHTML=meter('电量',(b.level==null?'未知':b.level+'%'),(b.charging?'充电':'未充电')+(b.temperatureC!=null?' · '+b.temperatureC+'°C':''),batt,'battery')+meter('内存',mem.usedPercent+'%',bytes(mem.usedBytes)+' / '+bytes(mem.totalBytes),mem.usedPercent)+meter('存储',st.usedPercent+'%',bytes(st.usedBytes)+' / '+bytes(st.totalBytes),st.usedPercent)+meter('短信',String(sms.storedMessages||0),'本机记录',Math.min(100,(Number(sms.storedMessages||0)/500)*100))+'<article class=\"metric facts\"><div class=\"compactFacts\">'+chip('设备',[(dev.manufacturer||''),dev.model].filter(Boolean).join(' '))+chip('系统','Android '+(dev.android||''))+chip('运行',uptime(dev.uptimeMs))+chip('网络',(net.connected?'已连 ':'未连 ')+(net.type||''))+chip('桥',svc.sendBridge?'已连接':(svc.requiresSendBridge?'未启动':'不需要'))+chip('SIM',sim)+'</div></article>'}"
                + "function render(){if(!messages.length){listEl.innerHTML='<article class=\"message empty\">暂无短信</article>';return}listEl.innerHTML=messages.map(m=>{const outgoing=m.direction==='outgoing';const who=outgoing?('发给 '+(m.recipient||m.sender||'未知号码')):(m.sender||'未知号码');return '<article class=\"message\"><div class=\"meta\">'+statusTag(m)+'<span class=\"sender\">'+esc(who)+'</span><span>'+esc(new Date(m.receivedAt).toLocaleString())+'</span><span>'+(m.simSlot!=null?'卡槽 '+(Number(m.simSlot)+1):esc(m.deviceId||''))+'</span></div><div class=\"body\">'+esc(m.body||'')+'</div></article>'}).join('')}"
                + "function renderSims(){simSelect.innerHTML=sims.length?sims.map(s=>'<option value=\"'+s.subscriptionId+'\">卡槽 '+(s.slotIndex+1)+' · '+esc(s.displayName||s.carrierName||'SIM')+(s.isDefault?' · 默认':'')+'</option>').join(''):'<option value=\"-1\">默认短信 SIM</option>'}"
                + "function renderPager(){const page=Math.floor(offset/pageSize)+1;const pages=Math.max(Math.ceil(total/pageSize),1);pageInfo.textContent='第 '+page+' / '+pages+' 页 · 共 '+total+' 条';prevPage.disabled=offset<=0;nextPage.disabled=offset+pageSize>=total}"
                + "async function loadDevice(){const r=await fetch('/api/device',{headers:headers()});if(!r.ok)throw new Error(r.status===401?'密码错误或未登录':'读取设备状态失败 HTTP '+r.status);renderDevice(await r.json())}async function load(){if(!tokenInput.value){statusEl.textContent='未登录';hintEl.textContent='请在设置里填写访问密码';statusBox.classList.remove('ok');render();renderDevice(null);openModal();return}statusEl.textContent='正在读取';hintEl.textContent='正在连接坚果手机';const [msg]=await Promise.all([fetch('/api/messages?limit='+pageSize+'&offset='+offset,{headers:headers()}),loadDevice().catch(()=>{})]);if(!msg.ok)throw new Error(msg.status===401?'密码错误或未登录':'读取失败 HTTP '+msg.status);const d=await msg.json();messages=d.messages||[];total=d.total||messages.length;render();renderPager();statusBox.classList.add('ok');statusEl.textContent='连接正常';hintEl.textContent='当前页 '+messages.length+' 条短信 · 状态已更新'}"
                + "async function loadSims(){const r=await fetch('/api/sims',{headers:headers()});if(!r.ok)throw new Error(r.status===401?'密码错误或未登录':'读取 SIM 失败 HTTP '+r.status);const d=await r.json();sims=d.sims||[];renderSims()}"
                + "document.querySelector('#settings').onclick=openModal;document.querySelector('#close').onclick=closeModal;modal.onclick=e=>{if(e.target===modal)closeModal()};document.querySelector('#save').onclick=()=>{localStorage.setItem('smsToken',tokenInput.value);closeModal();load().catch(e=>{statusEl.textContent=e.message;hintEl.textContent='请检查访问密码';statusBox.classList.remove('ok')})};"
                + "document.querySelector('#sendOpen').onclick=()=>{if(!tokenInput.value){openModal();return}sendModal.classList.add('open');loadSims().catch(e=>{statusEl.textContent=e.message;hintEl.textContent='请检查访问密码'});setTimeout(()=>recipient.focus(),30)};document.querySelector('#sendClose').onclick=()=>sendModal.classList.remove('open');sendModal.onclick=e=>{if(e.target===sendModal)sendModal.classList.remove('open')};"
                + "document.querySelector('#sendSms').onclick=async()=>{const payload={subscriptionId:Number(simSelect.value||-1),recipient:recipient.value,body:smsBody.value};const r=await fetch('/api/send',{method:'POST',headers:{...headers(),'Content-Type':'application/json'},body:JSON.stringify(payload)});const d=await r.json().catch(()=>({}));if(!r.ok){statusEl.textContent=d.error||('发送失败 HTTP '+r.status);hintEl.textContent='请检查号码、内容和发送权限';return}sendModal.classList.remove('open');recipient.value='';smsBody.value='';offset=0;statusEl.textContent='短信已提交发送';hintEl.textContent='已写入发送记录，共 '+(d.parts||1)+' 段';await load();setTimeout(()=>load().catch(()=>{}),2500);setTimeout(()=>load().catch(()=>{}),8000)};"
                + "document.querySelector('#refresh').onclick=()=>load().catch(e=>{statusEl.textContent=e.message;hintEl.textContent='请检查访问密码';statusBox.classList.remove('ok')});prevPage.onclick=()=>{offset=Math.max(0,offset-pageSize);load().catch(()=>{})};nextPage.onclick=()=>{if(offset+pageSize<total){offset+=pageSize;load().catch(()=>{})}};"
                + "document.querySelector('#updateToken').onclick=async()=>{const r=await fetch('/api/config',{method:'POST',headers:{...headers(),'Content-Type':'application/json'},body:JSON.stringify({token:newToken.value})});if(!r.ok){statusEl.textContent=r.status===401?'当前密码错误，无法修改':'更新失败 HTTP '+r.status;hintEl.textContent='请检查后重试';return}tokenInput.value=newToken.value;localStorage.setItem('smsToken',tokenInput.value);newToken.value='';closeModal();statusEl.textContent='访问密码已更新';load().catch(e=>statusEl.textContent=e.message)};"
                + "load().catch(e=>{statusEl.textContent=e.message;hintEl.textContent='请检查访问密码';statusBox.classList.remove('ok')});</script></body></html>";
    }
}
