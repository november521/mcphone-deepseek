"""假的 DeepSeek 服务端：把流式那条路径完整跑一遍。

路径决定行为：
  /ok/chat/completions       正常：先吐思考，再吐正文，中间夹一个坏块
  /slow/chat/completions     慢：每块之间等 300ms，用来测中途取消
  /err402/chat/completions   HTTP 402 + 官方那种错误体
  /inline/chat/completions   状态码 200，但流里塞了一个 error 对象
  /ok/models                 模型列表
最后一次收到的请求体写在 last_body.json 里，用来核对请求形状。
"""
import json, time, io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LAST = "last_body.json"


def sse(obj):
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")


def delta(**kw):
    return {"id": "x", "object": "chat.completion.chunk", "model": "deepseek-v4-flash",
            "choices": [{"index": 0, "delta": kw, "finish_reason": None}]}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.endswith("/models"):
            body = json.dumps({"object": "list", "data": [
                {"id": "deepseek-v4-flash", "object": "model", "owned_by": "deepseek"},
                {"id": "deepseek-v4-pro", "object": "model", "owned_by": "deepseek"},
                {"id": "deepseek-v4-flash-vision-exp", "object": "model", "owned_by": "deepseek"},
            ]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        io.open(LAST, "wb").write(raw)
        # 顺带把 Authorization 记下来，核对 Bearer 拼得对不对
        io.open("last_auth.txt", "w").write(self.headers.get("Authorization", ""))

        mode = self.path.split("/")[1] if "/" in self.path[1:] else ""

        if mode == "err402":
            body = json.dumps({"error": {"message": "Insufficient Balance",
                                         "type": "insufficient_balance"}}).encode()
            self.send_response(402)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        slow = mode == "slow"

        def emit(payload):
            chunk = payload
            self.wfile.write(hex(len(chunk))[2:].encode() + b"\r\n" + chunk + b"\r\n")
            self.wfile.flush()

        try:
            emit(b": keep-alive\n\n")                       # 注释行，该被忽略

            if mode == "inline":
                emit(sse({"error": {"message": "quota exhausted"}}))
                emit(b"data: [DONE]\n\n")
                emit(b"")
                return

            for piece in ["嗯，", "这个问题", "要分两步看。"]:
                emit(sse(delta(reasoning_content=piece)))
                if slow:
                    time.sleep(0.3)

            emit(b"data: {this is not json}\n\n")           # 坏块，该被跳过

            for piece in ["**结论**：", "先做 A，", "再做 B。\n\n",
                          "```java\nint a = 1;\n```"]:
                emit(sse(delta(content=piece)))
                if slow:
                    time.sleep(0.3)

            emit(sse({"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                      "usage": {"prompt_tokens": 9, "completion_tokens": 42}}))
            emit(b"data: [DONE]\n\n")
            emit(b"")
        except (BrokenPipeError, ConnectionResetError):
            io.open("cancelled.flag", "w").write("client hung up")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8777), H).serve_forever()
