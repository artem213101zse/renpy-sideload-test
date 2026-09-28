#!/usr/bin/env python3
# Комната на двоих для того же текстового протокола, что у игры.
# Оба клиента сами делают connect. Хост в телефоне не нужен.
# Строки уходят сопернику как есть. Регистрации и базы нет.

import os
import socket
import threading
import time

PORT = int(os.environ.get("PORT", "7777"))
LOG = threading.Lock()


def log(message):
    with LOG:
        print(message, flush=True)
# Клиент сам шлёт ping раз в 1 с. Порог выше, чтобы живой игрок
# не считался молчуном. Молчуну уходит ping, ещё через две секунды
# без байт — он отвалился.
SILENCE_PING = 1.5
SILENCE_DEAD = 3.5


def _close(sock):
    if sock is None:
        return
    try:
        sock.close()
    except OSError:
        pass


class Seat(object):
    def __init__(self, sock, addr):
        self.sock = sock
        self.addr = addr
        self.buf = b""
        self.pending = []
        self.server_pings = set()
        self.last_rx = time.monotonic()
        self.next_server_ping = self.last_rx + SILENCE_PING
        self.send_lock = threading.Lock()


class Room(object):
    def __init__(self):
        self.seats = []
        self.lock = threading.Lock()
        self.closed = False

    def full(self):
        with self.lock:
            return (not self.closed) and len(self.seats) >= 2

    def add(self, seat):
        queued = []
        with self.lock:
            if self.closed or len(self.seats) >= 2:
                return False
            self.seats.append(seat)
            if len(self.seats) == 2:
                queued = self._take_pending_locked()
        for peer, line in queued:
            if line.startswith("move ") or line.startswith("hello ") or line in ("resign", "quit"):
                log("relay %s" % line)
            self._deliver(peer, line)
        log("accept %s:%s seats=%s" % (seat.addr[0], seat.addr[1], len(self.seats)))
        return True

    def pump(self, seat):
        seat.sock.settimeout(0.3)
        try:
            while not self.closed:
                now = time.monotonic()
                silent = now - seat.last_rx
                if silent >= SILENCE_DEAD:
                    self.fail(seat)
                    return
                if silent >= SILENCE_PING and now >= seat.next_server_ping:
                    stamp = str(int(time.time() * 1000))
                    with self.lock:
                        seat.server_pings.add(stamp)
                        seat.next_server_ping = now + SILENCE_PING
                    if not self._deliver(seat, "ping " + stamp):
                        self.fail(seat)
                        return
                    log("server ping %s -> %s:%s" % (stamp, seat.addr[0], seat.addr[1]))
                try:
                    chunk = seat.sock.recv(4096)
                except socket.timeout:
                    continue
                except OSError:
                    self.fail(seat)
                    return
                if not chunk:
                    self.fail(seat)
                    return
                seat.last_rx = time.monotonic()
                seat.buf += chunk
                while b"\n" in seat.buf:
                    raw, seat.buf = seat.buf.split(b"\n", 1)
                    try:
                        line = raw.decode("utf-8").strip("\r").strip()
                    except UnicodeError:
                        line = raw.decode("utf-8", "replace").strip("\r").strip()
                    if line:
                        self.on_line(seat, line)
        finally:
            _close(seat.sock)

    def on_line(self, seat, line):
        target = None
        payload = None
        local = None
        with self.lock:
            if self.closed:
                return
            peer = self._peer_locked(seat)
            if peer is None:
                if line.startswith("ping "):
                    stamp = line[5:].strip()
                    if stamp:
                        local = "pong " + stamp
                elif line.startswith("pong "):
                    stamp = line[5:].strip()
                    seat.server_pings.discard(stamp)
                else:
                    seat.pending.append(line)
                target = None
            else:
                if line.startswith("pong "):
                    stamp = line[5:].strip()
                    if stamp in seat.server_pings:
                        seat.server_pings.discard(stamp)
                        return
                target = peer
                payload = line
        if local is not None:
            self._deliver(seat, local)
            return
        if target is not None and payload is not None:
            if payload.startswith("move ") or payload.startswith("hello ") or payload in ("resign", "quit"):
                log("relay %s" % payload)
            self._deliver(target, payload)

    def fail(self, seat):
        peer = None
        with self.lock:
            if self.closed:
                return
            self.closed = True
            if len(self.seats) == 2:
                peer = self._peer_locked(seat)
        if peer is not None:
            log("opponent_left -> %s:%s" % (peer.addr[0], peer.addr[1]))
            self._deliver(peer, "opponent_left")
            try:
                peer.sock.shutdown(socket.SHUT_WR)
            except OSError:
                pass
        _close(seat.sock)

    def _peer_locked(self, seat):
        if len(self.seats) < 2:
            return None
        if self.seats[0] is seat:
            return self.seats[1]
        if self.seats[1] is seat:
            return self.seats[0]
        return None

    def _take_pending_locked(self):
        queued = []
        if len(self.seats) < 2:
            return queued
        for seat in self.seats:
            peer = self._peer_locked(seat)
            for line in seat.pending:
                queued.append((peer, line))
            seat.pending = []
        return queued

    def _deliver(self, seat, line):
        data = line.encode("utf-8") + b"\n"
        try:
            with seat.send_lock:
                seat.sock.sendall(data)
            return True
        except OSError:
            return False


def serve(port):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", port))
    listener.listen(8)
    log("listen 0.0.0.0:%s" % port)
    room = None
    while True:
        conn, addr = listener.accept()
        try:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        if room is None or room.closed or room.full():
            if room is not None and room.full() and not room.closed:
                log("reject %s:%s" % (addr[0], addr[1]))
                _close(conn)
                continue
            room = Room()
        seat = Seat(conn, addr)
        if not room.add(seat):
            log("reject %s:%s" % (addr[0], addr[1]))
            _close(conn)
            continue
        threading.Thread(target=room.pump, args=(seat,), daemon=True).start()


if __name__ == "__main__":
    serve(PORT)
