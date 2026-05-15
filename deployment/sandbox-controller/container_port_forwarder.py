#!/usr/bin/env python3
import os
import select
import socket
import socketserver
import threading

LISTEN_HOST = os.environ.get("LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", "9222"))
TARGET_HOST = os.environ.get("TARGET_HOST", "20.20.20.21")
TARGET_PORT = int(os.environ.get("TARGET_PORT", "6090"))


class Proxy(socketserver.BaseRequestHandler):
    def handle(self):
        upstream = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=10)
        sockets = [self.request, upstream]
        try:
            while True:
                readable, _, exceptional = select.select(sockets, [], sockets, 60)
                if exceptional:
                    break
                if not readable:
                    continue
                for sock in readable:
                    data = sock.recv(65536)
                    if not data:
                        return
                    target = upstream if sock is self.request else self.request
                    target.sendall(data)
        finally:
            try:
                upstream.close()
            except Exception:
                pass


class ThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    server = ThreadingTCPServer((LISTEN_HOST, LISTEN_PORT), Proxy)
    print(f"forwarding {LISTEN_HOST}:{LISTEN_PORT} -> {TARGET_HOST}:{TARGET_PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
