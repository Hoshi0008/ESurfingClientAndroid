#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mock BRAS / 校园网门户，用来联调 Java 端（桌面与 Android 均可）。

它用参考后端的加解密实现来应答，所以只要 Java 端能跑通完整流程，
就说明两边的报文、算法、头字段完全对齐。

用法:
    python tools/mock-bras/mock_bras.py [--ref <参考仓库目录>] [--user U] [--pass P]

端口:
    :7001  门户（index.cgi / ticket.cgi / auth.cgi）
    :7002  登录响应里下发的 keep-url / term-url（模拟"另一台 BRAS"）
"""
import argparse
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ZERO_UUID = "00000000-0000-0000-0000-000000000000"
ALGOS = {}
BAD_ALGOS = set()
DoubleAesCbc = None

# 已认证的账号（写死两份便于测试）
KNOWN = {}


def load_ref(ref_dir):
    global ALGOS, BAD_ALGOS, DoubleAesCbc
    sys.path.insert(0, ref_dir)
    from esurfing import DoubleAesCbc as Impl  # noqa
    from esurfing_algos import ALGOS as TABLE, BAD_ALGOS as BAD  # noqa
    DoubleAesCbc = Impl
    ALGOS.update(TABLE)
    BAD_ALGOS.update(BAD)


def make_cipher(algo_id):
    entry = ALGOS.get(algo_id)
    if not entry:
        return None
    kind, key, iv = entry
    assert kind == "aes_double_cbc"
    return DoubleAesCbc(key, iv)


def pstr(text):
    raw = text.encode()
    return bytes([len(raw)]) + raw


def zsm_body(algo_id):
    return b"001" + pstr("mock-zsm") + pstr(algo_id)


def xml_field(xml, name):
    m = re.search(r"<%s>(.*?)</%s>" % (name, name), xml, re.S)
    return m.group(1).strip() if m else ""


class State(object):
    def __init__(self):
        self.zsm_round = 0
        self.ticket = ""
        self.keep_count = 0
        self.logged_in = False
        self.lock = threading.Lock()


STATE = State()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MockBras/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] %s %s\n" % (self.address_string(), fmt % args))

    # ---------------------------------------------------------------- 基础

    def _send(self, status, body=b"", error_code=None, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        if error_code is not None:
            self.send_header("Error-Code", str(error_code))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        return self.rfile.read(length) if length > 0 else b""

    # ---------------------------------------------------------------- 路由

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/generate_204", "/gen_204"):
            self._send(204)
            return
        if path == "/index.cgi":
            self._send(200, self._index_html().encode())
            return
        # 随便一个未知地址：当作"有外网"
        self._send(200, b"<html><body>mock</body></html>")

    def do_POST(self):
        path = self.path.split("?")[0]
        body = self._body()
        algo_hdr = self.headers.get("Algo-ID") or ""
        sys.stderr.write("[mock] POST %s algo=%s len=%d checksum=%s\n" % (
            path, algo_hdr, len(body), self.headers.get("CDC-Checksum")))
        if path == "/ticket.cgi":
            self._ticket(body, algo_hdr)
        elif path == "/auth.cgi":
            self._auth(body, algo_hdr)
        elif path == "/keep.cgi":
            self._keep(body, algo_hdr)
        elif path == "/term.cgi":
            self._term(body, algo_hdr)
        else:
            self._send(404, b"not found", 404)

    # ---------------------------------------------------------------- 各接口

    def _index_html(self):
        return (
            "<html><head><title>mock bras</title></head><body>\n"
            "<!--//config.campus.js.chinatelecom.com\n"
            "<config>\n"
            "  <ticket-url><![CDATA[http://127.0.0.1:7001/ticket.cgi"
            "?wlanuserip=127.0.0.1&wlanacip=127.0.0.1]]></ticket-url>\n"
            "</config>\n"
            "//config.campus.js.chinatelecom.com-->\n"
            "<script>var wlanacip=\"127.0.0.1\";</script>\n"
            "wlanacip=127.0.0.1\n"
            "</body></html>\n")

    def _ticket(self, body, algo_hdr):
        if algo_hdr == ZERO_UUID:
            with STATE.lock:
                STATE.zsm_round += 1
                n = STATE.zsm_round
            # 前两轮故意发不可用的算法，验证客户端的丢弃/重试逻辑
            if n == 1:
                algo = sorted(BAD_ALGOS)[0]
            elif n == 2:
                algo = "11111111-2222-3333-4444-555555555555"  # 未实现
            else:
                algo = "066474E5-503E-4B82-98C4-DF4483DAF0B5"
            sys.stderr.write("[mock] 下发 ZSM %s（第 %d 轮）\n" % (algo, n))
            self._send(200, zsm_body(algo), 200)
            return

        cipher = make_cipher(algo_hdr)
        if cipher is None:
            self._send(200, b"", 4)
            return
        try:
            req = cipher.decrypt(body.decode()).decode("utf-8", "replace")
        except Exception as exc:  # noqa
            sys.stderr.write("[mock] 解不开 ticket 请求: %r\n" % exc)
            self._send(200, b"", 3)
            return
        ip = xml_field(req, "ipv4")
        mac = xml_field(req, "mac")
        host = xml_field(req, "host-name")
        sys.stderr.write("[mock] ticket 请求 ipv4=%s mac=%s host=%s\n" % (ip, mac, host))
        if not ip or not mac or not host:
            self._send(200, b"", 5)
            return
        with STATE.lock:
            STATE.ticket = "MOCKTICKET%04d" % (len(req) + 1)
            ticket = STATE.ticket
        resp = ('<?xml version="1.0" encoding="utf-8"?>\n<response>\n'
                "    <ticket>%s</ticket>\n    <expire>600</expire>\n</response>\n" % ticket)
        self._send(200, cipher.encrypt(resp.encode()).encode(), 0)

    def _auth(self, body, algo_hdr):
        cipher = make_cipher(algo_hdr)
        if cipher is None:
            self._send(200, b"", 4)
            return
        try:
            req = cipher.decrypt(body.decode()).decode("utf-8", "replace")
        except Exception as exc:  # noqa
            sys.stderr.write("[mock] 解不开 auth 请求: %r\n" % exc)
            self._send(200, b"", 3)
            return
        user = xml_field(req, "userid")
        passwd = xml_field(req, "passwd")
        ticket = xml_field(req, "ticket")
        sys.stderr.write("[mock] auth 请求 user=%s ticket=%s\n" % (user, ticket))
        if ticket != STATE.ticket:
            self._send(200, b"", 7)
            return
        if KNOWN.get(user) != passwd:
            resp = ('<?xml version="1.0" encoding="utf-8"?>\n<response>\n'
                    "    <error>账号或密码错误</error>\n</response>\n")
            self._send(200, cipher.encrypt(resp.encode()).encode(), "13012000")
            return
        with STATE.lock:
            STATE.logged_in = True
        resp = ('<?xml version="1.0" encoding="utf-8"?>\n<response>\n'
                "    <keep-retry>5</keep-retry>\n"
                "    <keep-url><![CDATA[http://127.0.0.1:7002/keep.cgi]]></keep-url>\n"
                "    <term-url><![CDATA[http://127.0.0.1:7002/term.cgi]]></term-url>\n"
                "    <user-config><![CDATA[<config><interval>300</interval></config>]]></user-config>\n"
                "</response>\n")
        self._send(200, cipher.encrypt(resp.encode()).encode(), 0)

    def _keep(self, body, algo_hdr):
        cipher = make_cipher(algo_hdr)
        if cipher is None:
            self._send(200, b"", 4)
            return
        if not STATE.logged_in:
            self._send(200, b"", 14)
            return
        with STATE.lock:
            STATE.keep_count += 1
            n = STATE.keep_count
        resp = ('<?xml version="1.0" encoding="utf-8"?>\n<response>\n'
                "    <interval>300</interval>\n    <level>0</level>\n</response>\n")
        sys.stderr.write("[mock] keep #%d 正常\n" % n)
        self._send(200, cipher.encrypt(resp.encode()).encode(), 0)

    def _term(self, body, algo_hdr):
        with STATE.lock:
            STATE.logged_in = False
        self._send(200, b"", 0)


def serve(port, stop_event):
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default=os.environ.get("ESURFING_REF", ""))
    ap.add_argument("--user", default="20230001")
    ap.add_argument("--passwd", dest="passwd", default="pass1234")
    a = ap.parse_args()
    if not a.ref:
        print("需要 --ref 指向参考后端仓库目录（含 esurfing.py / esurfing_algos.py）")
        return 2
    load_ref(a.ref)
    KNOWN[a.user] = a.passwd
    serve(7001, None)
    serve(7002, None)
    sys.stderr.write("[mock] 已监听 127.0.0.1:7001 / 7002，账号 %s / %s\n" % (a.user, a.passwd))
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
