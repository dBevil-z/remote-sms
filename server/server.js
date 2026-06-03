import { createServer } from "node:http";
import { readFile, mkdir, appendFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.PORT || 8787);
const TOKEN = process.env.SMS_TOKEN || "change-me";
const PUBLIC_READ = process.env.PUBLIC_READ === "1";
const DATA_DIR = resolve(process.env.DATA_DIR || join(process.cwd(), "data"));
const DATA_FILE = join(DATA_DIR, "messages.jsonl");
const PUBLIC_DIR = join(process.cwd(), "public");

const clients = new Set();

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolveBody, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 1024 * 1024) {
        reject(new Error("request body too large"));
        req.destroy();
      }
    });
    req.on("end", () => resolveBody(body));
    req.on("error", reject);
  });
}

function authorized(req, allowPublicRead = false) {
  if (allowPublicRead && PUBLIC_READ) return true;
  const header = req.headers.authorization || "";
  if (header === `Bearer ${TOKEN}`) return true;
  const url = new URL(req.url, `http://${req.headers.host}`);
  return url.searchParams.get("token") === TOKEN;
}

async function readMessages(limit = 200) {
  if (!existsSync(DATA_FILE)) return [];
  const raw = await readFile(DATA_FILE, "utf8");
  return raw
    .split("\n")
    .filter(Boolean)
    .map(line => JSON.parse(line))
    .sort((a, b) => b.receivedAt - a.receivedAt)
    .slice(0, limit);
}

function broadcast(message) {
  const payload = `data: ${JSON.stringify(message)}\n\n`;
  for (const client of clients) {
    client.write(payload);
  }
}

async function handleApiMessages(req, res) {
  if (req.method === "GET") {
    if (!authorized(req, true)) return sendJson(res, 401, { error: "unauthorized" });
    const url = new URL(req.url, `http://${req.headers.host}`);
    const limit = Math.min(Number(url.searchParams.get("limit") || 200), 1000);
    return sendJson(res, 200, { messages: await readMessages(limit) });
  }

  if (req.method !== "POST") return sendJson(res, 405, { error: "method not allowed" });
  if (!authorized(req)) return sendJson(res, 401, { error: "unauthorized" });

  let body;
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    return sendJson(res, 400, { error: "invalid json" });
  }

  const message = {
    id: body.id || randomUUID(),
    deviceId: String(body.deviceId || "unknown"),
    sender: String(body.sender || ""),
    body: String(body.body || ""),
    receivedAt: Number(body.receivedAt || Date.now()),
    simSlot: body.simSlot == null ? null : Number(body.simSlot),
    syncedAt: Date.now()
  };

  await mkdir(DATA_DIR, { recursive: true });
  await appendFile(DATA_FILE, `${JSON.stringify(message)}\n`, "utf8");
  broadcast(message);
  return sendJson(res, 201, { ok: true, id: message.id });
}

async function serveStatic(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const file = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
  const path = join(PUBLIC_DIR, file);
  if (!path.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    return res.end("forbidden");
  }

  try {
    const body = await readFile(path);
    const type = path.endsWith(".css")
      ? "text/css; charset=utf-8"
      : path.endsWith(".js")
        ? "text/javascript; charset=utf-8"
        : "text/html; charset=utf-8";
    res.writeHead(200, { "content-type": type, "cache-control": "no-store" });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end("not found");
  }
}

const server = createServer(async (req, res) => {
  try {
    if (req.url?.startsWith("/api/messages")) return await handleApiMessages(req, res);
    if (req.url?.startsWith("/events")) {
      if (!authorized(req, true)) return sendJson(res, 401, { error: "unauthorized" });
      res.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-store",
        connection: "keep-alive"
      });
      res.write(": connected\n\n");
      clients.add(res);
      req.on("close", () => clients.delete(res));
      return;
    }
    if (req.url === "/health") return sendJson(res, 200, { ok: true });
    return await serveStatic(req, res);
  } catch (error) {
    console.error(error);
    return sendJson(res, 500, { error: "internal server error" });
  }
});

await mkdir(DATA_DIR, { recursive: true });
if (!existsSync(DATA_FILE)) await writeFile(DATA_FILE, "", "utf8");

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Remote SMS server listening on http://0.0.0.0:${PORT}`);
  console.log(`Use SMS_TOKEN=${TOKEN === "change-me" ? "change-me (please change)" : "[set]"}`);
});
