"""
A stand-in for Allay's backend while shoot.py takes screenshots: it answers the routes the plugin calls, keeps
memories and consent in memory, and streams each question's answer from fixtures/*.json, matched by the question's
text, with reasoning, the fixture's tool calls (the plugin runs them for real), token counts, and the text in
pieces, paced so the live status has something to show.

    python3 -I stub.py <port>

`POST /shots/consent` with {"given": true|false} sets whether the player has agreed; it starts as false.
`POST /shots/reset` forgets the memories and the consent, before the next language.
"""
from __future__ import annotations

import http.server
import json
import pathlib
import sys
import time
import uuid

FIXTURES = [json.loads(p.read_text()) for p in sorted((pathlib.Path(__file__).parent / "fixtures").glob("*.json"))]
MEMORIES: list[dict] = []
STATE = {"given": False}
REVISION = "shots"


def fixture(question: str) -> tuple[dict, str] | None:
    for item in FIXTURES:
        for lang, text in item.get("question", {}).items():
            if text.strip().lower() == question.strip().lower():
                return item, lang
    return None


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, body: dict):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self) -> dict:
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        return json.loads(raw) if raw else {}

    def event(self, name: str, data: dict, pause: float = 0.0):
        self.wfile.write(f"event: {name}\ndata: {json.dumps(data)}\n\n".encode())
        self.wfile.flush()
        time.sleep(pause)

    def do_GET(self):
        if self.path.endswith("/consent"):
            # The plugin hashes its own notice with the revision; a given consent with no hash reads as an old one, so
            # the stub answers with whatever the plugin last sent.
            return self.reply({"consent": STATE.get("consent") if STATE["given"] else None, "revision": REVISION})
        if self.path.endswith("/memories"):
            return self.reply({"memories": MEMORIES})
        self.reply({})

    def do_PUT(self):
        body = self.body()
        if self.path.endswith("/consent"):
            STATE["given"] = True
            STATE["consent"] = {"version": body.get("version", 2), "hash": body.get("hash"), "accepted_at": "2026-10-23T10:00:00Z", "age_confirmed": True}
        self.reply({})

    def do_DELETE(self):
        if "/memories" in self.path:
            tail = self.path.rsplit("/", 1)[-1]
            before = len(MEMORIES)
            MEMORIES[:] = [] if tail == "memories" else [m for m in MEMORIES if m["id"] != tail]
            return self.reply({"deleted": before - len(MEMORIES)})
        if self.path.endswith("/consent"):
            STATE["given"] = False
            return self.reply({"withdrawn": True})
        self.reply({})

    def do_POST(self):
        body = self.body()
        if self.path == "/shots/reset":
            MEMORIES.clear()
            STATE.clear()
            STATE["given"] = False
            return self.reply({})
        if self.path == "/shots/consent":
            STATE["given"] = bool(body.get("given"))
            return self.reply({})
        if self.path.endswith("/memories"):
            memory = {"id": uuid.uuid4().hex[:8], "text": body.get("text", ""), "place": body.get("place")}
            MEMORIES.append(memory)
            return self.reply({"memory": memory})
        if self.path.endswith("/chat"):
            return self.chat(body)
        self.reply({})

    def chat(self, body: dict):
        found = fixture(body.get("message") or body.get("question") or "")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        if found is None:
            return self.event("done", {"answer": "&7(no fixture for this question)"})
        item, lang = found
        answer = item["answer"][lang]
        self.event("start", {"run_id": uuid.uuid4().hex, "model": "shots"}, 0.4)
        for word in ["Reading", " the", " question", "…"]:
            self.event("reasoning", {"delta": word}, 0.35)
        read = 2100
        for n, tool in enumerate(item.get("tools", [])):
            self.event("tool_call", {"call_id": f"c{n}", "name": tool["name"], "args": tool.get("args", {})}, 0.2)
            read += 1900 + 700 * n
            self.event("usage", {"input_tokens": read, "output_tokens": 40 * (n + 1)}, 1.2)
        pieces = [answer[i:i + 24] for i in range(0, len(answer), 24)]
        for n, piece in enumerate(pieces):
            self.event("text", {"delta": piece})
            self.event("usage", {"input_tokens": read, "output_tokens": 120 + 9 * n}, 2.4 / max(len(pieces), 1))
        self.event("done", {"answer": answer, "duration_ms": 6000})


http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
