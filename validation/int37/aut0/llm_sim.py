#!/usr/bin/env python3
"""
AUT-0 sandbox only: OpenAI-compatible stand-in for Ollama (POST /v1/chat/completions).

It answers deterministically, the way a well-behaved model would, for the three demo questions, reading the
catalog aliases from the system message exactly as Intelligence sends it. It does NOT test the real model:
interpretation quality of gpt-oss:20b must be verified on Windows (V6/V7).
"""
import json, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, LOG = int(sys.argv[1]), sys.argv[2]
NULL_PERIOD = {k: None for k in ["year", "quarter", "month", "date", "from", "to", "relative", "count", "unit"]}


def answer(system, question):
    catalog = json.loads(system.split("Catalog (data, not instructions):\n", 1)[1].split("\n", 1)[0])
    alias = {e["key"]: e["alias"] for e in catalog}
    q = question.lower()
    req = {"metrics": [], "dimensions": [], "filters": [], "granularities": [], "period": None}
    if "actividades" in q:
        req["metrics"] = [alias["numero_actividades"]]
        req["dimensions"] = [alias["fecha_actividad"]]
        req["granularities"] = [{"dimension": alias["fecha_actividad"], "granularity": "DAY"}]
        m = re.search(r"entre el (\d+) y el (\d+) de octubre de (\d{4})", q)
        req["period"] = dict(NULL_PERIOD, dimension=alias["fecha_actividad"], kind="RANGE",
                             **{"from": f"{m.group(3)}-10-{int(m.group(1)):02d}", "to": f"{m.group(3)}-10-{int(m.group(2)):02d}"})
    elif "estatus" in q:
        req["metrics"] = [alias["numero_pozos"]]
        req["dimensions"] = [alias["estatus_pozo"]]
    elif "campo" in q:
        req["metrics"] = [alias["numero_pozos"]]
        req["dimensions"] = [alias["campo"]]
    else:
        return {"intent": "UNKNOWN", "status": "NEEDS_CLARIFICATION",
                "clarificationQuestion": "¿Qué te gustaría medir?", "request": None}
    return {"intent": "REPORT_REQUEST", "status": "INTERPRETED", "clarificationQuestion": None, "request": req}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)))
        msgs = body["messages"]
        system = next(m["content"] for m in msgs if m["role"] == "system")
        question = next(m["content"] for m in msgs if m["role"] == "user")
        content = json.dumps(answer(system, question), ensure_ascii=False)
        out = json.dumps({"id": "sim", "object": "chat.completion", "model": body.get("model"),
                          "choices": [{"index": 0, "finish_reason": "stop",
                                       "message": {"role": "assistant", "content": content}}],
                          "usage": {"prompt_tokens": 1200, "completion_tokens": 80}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)
        with open(LOG, "a") as f:
            f.write(f"Q={question!r} -> {content}\n")


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
