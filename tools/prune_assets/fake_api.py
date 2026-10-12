#!/usr/bin/env python3
"""`scripts/prune-issue-assets.sh` 的假平台。

为什么不用真的平台接口跑自测：那要 `repo-manage:rw`，而且**真删** ——
自测会变成"每次跑就真删一批用户的附件"。这里用真 HTTP（不是函数打桩），
因为要测的三件事恰好都在 HTTP 这一层的边界上：

  · 分页（`page_size` 边界那一页要能停住，不无限翻）；
  · 状态码（403 / 422 / 连接失败各自的处置完全不同）；
  · 请求**实际发出去几条、删了哪些 ID** —— 「该删的删了」与「不该删的
    一条都没碰」是两条独立的断言，只看输出文本分不开。

用法（被 run_prune_assets_tests.sh 拉起）：
    python3 tools/prune_assets/fake_api.py <port> <scenario> <记录文件>

scenario 决定这个假平台怎么回应：
    ok            正常：按 page_size 分页返回记录
    forbid        list-assets 一律 403（缺 scope）
    refuse        端口上没人监听（客户端连不上）
    del422        DELETE 一律 422（不可删的类型）
    del500        DELETE 一律 500（单条失败不中断整批）
落一个 requests.log 到记录文件同目录，逐行 `GET <path>` / `DELETE <id>`。
"""
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
SCENARIO = sys.argv[2]
RECORDS_FILE = sys.argv[3]
LOG = os.path.join(os.path.dirname(RECORDS_FILE), 'requests.log')

with open(RECORDS_FILE, encoding='utf-8') as f:
    RECORDS = json.load(f)

open(LOG, 'w').close()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _log(self, line):
        with open(LOG, 'a', encoding='utf-8') as f:
            f.write(line + '\n')

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._log('GET ' + self.path)
        if SCENARIO == 'forbid':
            self._json(403, {'errcode': 10023,
                             'errmsg': "Missing required scopes: repo-manage:r"})
            return
        # 解析 page / page_size；缺省用平台默认 1 / 10。
        page, size = 1, 10
        if '?' in self.path:
            for kv in self.path.split('?', 1)[1].split('&'):
                if '=' not in kv:
                    continue
                k, v = kv.split('=', 1)
                if k == 'page':
                    page = int(v)
                elif k == 'page_size':
                    size = int(v)
        start = (page - 1) * size
        self._json(200, RECORDS[start:start + size])

    def do_DELETE(self):
        aid = self.path.rstrip('/').split('/')[-1]
        self._log('DELETE ' + aid)
        if SCENARIO == 'del422':
            self._json(422, {'errmsg': 'release和commit附件不能通过该接口删除'})
        elif SCENARIO == 'del500':
            self._json(500, {'errmsg': 'boom'})
        else:
            self.send_response(200)
            self.end_headers()


srv = HTTPServer(('127.0.0.1', PORT), Handler)
if SCENARIO == 'refuse':
    # 什么都不监听：客户端连上来会 ECONNREFUSED。
    srv.server_close()
    while True:
        threading.Event().wait(60)
threading.Thread(target=srv.serve_forever, daemon=True).start()
threading.Event().wait(600)
