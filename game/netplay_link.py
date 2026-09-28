# -*- coding: utf-8 -*-
# Зачем этот файл: LAN-ходы без доски. Сокет живёт только в этом потоке.
# Ren'Py store отсюда не трогаем: наружу выходят уже готовые строки,
# их забирает главный поток. hello_engine здесь не вызывается.

from __future__ import print_function

import socket
import threading
import time

PORT = 7777


def lan_ip():
    """Локальный IPv4 маршрута по умолчанию. Пакет на 8.8.8.8 не уходит:
    UDP connect только выбирает адрес исходящего интерфейса.
    """
    probe = None
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.settimeout(0.3)
        probe.connect(("8.8.8.8", 80))
        ip = probe.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    finally:
        _quiet_close(probe)
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None)
    except Exception:
        infos = []
    for info in infos:
        ip = info[4][0]
        if ":" in ip or ip.startswith("127."):
            continue
        return ip
    return "127.0.0.1"


def _quiet_close(sock):
    if sock is None:
        return
    try:
        sock.close()
    except socket.error:
        pass


class NetplayLink(object):
    """Один TCP-сеанс. Кадр протокола: строка UTF-8 и \\n.

    ping <ms> / pong <тот же ms> / hello host|guest / move <текст> /
    resign / quit. Сервер комнаты может прислать opponent_left.
    Легальность хода не проверяется.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._events = []
        self._outbox = []
        self._stop = False
        self._said_goodbye = False
        self._gen = 0
        self._thread = None
        self._server = None
        self._conn = None
        self._buf = ""
        self._last_pong = 0.0
        self._next_ping = 0.0
        self._timed_out = False

    def drain(self):
        with self._lock:
            found = self._events
            self._events = []
        return found

    def discard_events(self):
        with self._lock:
            self._events = []

    def running(self):
        with self._lock:
            thread = self._thread
        return thread is not None and thread.is_alive()

    def host(self, port=PORT):
        gen = self._begin()
        thread = threading.Thread(target=self._host_main, args=(int(port), gen))
        thread.daemon = True
        with self._lock:
            self._thread = thread
        thread.start()

    def connect(self, ip, port=PORT):
        if isinstance(ip, unicode):
            ip = ip.encode("utf-8")
        ip = (ip or "").strip()
        gen = self._begin()
        if not ip:
            self._emit("note empty ip")
            self._emit("status timeout")
            return
        thread = threading.Thread(target=self._guest_main, args=(ip, int(port), gen))
        thread.daemon = True
        with self._lock:
            self._thread = thread
        thread.start()

    def submit(self, line):
        if isinstance(line, str):
            try:
                line = line.decode("utf-8")
            except Exception:
                line = unicode(line, "utf-8", "replace")
        line = line.replace(u"\r", u" ").replace(u"\n", u" ").strip()
        if not line:
            return
        with self._lock:
            self._outbox.append(line)

    def close(self, goodbye=None):
        thread = None
        server = None
        with self._lock:
            if goodbye and not self._said_goodbye:
                self._outbox.append(goodbye)
                self._said_goodbye = True
            self._stop = True
            self._gen += 1
            server = self._server
            thread = self._thread
        # Слушающий сокет закрываем сразу, чтобы accept не ждал таймаут.
        # Клиентский сокет не трогаем, пока поток не допишет goodbye.
        _quiet_close(server)
        if thread is not None and thread is not threading.current_thread():
            thread.join(3.0)
        conn = None
        with self._lock:
            conn = self._conn
            if self._thread is thread:
                self._thread = None
            self._server = None
            self._conn = None
        _quiet_close(conn)

    def _begin(self):
        self.close(goodbye=u"quit")
        with self._lock:
            self._events = []
            self._outbox = []
            self._stop = False
            self._said_goodbye = False
            self._gen += 1
            self._buf = ""
            return self._gen

    def _emit(self, line):
        with self._lock:
            self._events.append(line)

    def _stopped(self):
        with self._lock:
            return self._stop

    def _same_gen(self, gen):
        with self._lock:
            return self._gen == gen

    def _stop_now(self):
        with self._lock:
            self._stop = True

    def _send_line(self, text):
        with self._lock:
            conn = self._conn
        if conn is None:
            return False
        if isinstance(text, unicode):
            data = text.encode("utf-8")
        else:
            data = text
        try:
            conn.sendall(data + "\n")
            return True
        except socket.error:
            return False

    def _flush_outbox(self):
        with self._lock:
            batch = self._outbox
            self._outbox = []
        for line in batch:
            if not self._send_line(line):
                if not self._stopped():
                    self._emit("status opponent left")
                self._stop_now()
                return

    def _host_main(self, port, gen):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("0.0.0.0", port))
            server.listen(1)
        except socket.error as exc:
            self._emit("note " + str(exc))
            self._emit("status timeout")
            _quiet_close(server)
            return
        server.settimeout(0.5)
        with self._lock:
            if self._gen != gen:
                _quiet_close(server)
                return
            self._server = server
        self._emit("status waiting")
        conn = None
        while self._same_gen(gen) and not self._stopped():
            try:
                conn, _addr = server.accept()
            except socket.timeout:
                continue
            except socket.error:
                conn = None
                break
            else:
                break
        _quiet_close(server)
        with self._lock:
            if self._server is server:
                self._server = None
        if conn is None or not self._same_gen(gen) or self._stopped():
            _quiet_close(conn)
            return
        self._run_conn(conn, u"host", gen)

    def _guest_main(self, ip, port, gen):
        try:
            conn = socket.create_connection((ip, port), 5)
        except socket.error as exc:
            self._emit("note " + str(exc))
            self._emit("status timeout")
            return
        if not self._same_gen(gen) or self._stopped():
            _quiet_close(conn)
            return
        self._run_conn(conn, u"guest", gen)

    def _run_conn(self, conn, role, gen):
        conn.settimeout(0.3)
        with self._lock:
            if self._gen != gen:
                _quiet_close(conn)
                return
            self._conn = conn
            self._buf = ""
        try:
            if not self._send_line(u"hello " + role):
                self._emit("status opponent left")
                return
            self._emit("status connected")
            now = time.time()
            self._last_pong = now
            self._next_ping = now
            self._timed_out = False
            while self._same_gen(gen):
                self._flush_outbox()
                if self._stopped() or not self._same_gen(gen):
                    break
                now = time.time()
                if now - self._last_pong >= 3.0 and not self._timed_out:
                    self._timed_out = True
                    self._emit("status timeout")
                if now >= self._next_ping:
                    stamp = int(now * 1000)
                    if not self._send_line(u"ping %d" % stamp):
                        if not self._stopped():
                            self._emit("status opponent left")
                        break
                    self._next_ping = now + 1.0
                try:
                    chunk = conn.recv(4096)
                except socket.timeout:
                    continue
                except socket.error:
                    if not self._stopped():
                        self._emit("status opponent left")
                    break
                if not chunk:
                    if not self._stopped():
                        self._emit("status opponent left")
                    break
                self._take(chunk)
        finally:
            with self._lock:
                if self._conn is conn:
                    self._conn = None
            _quiet_close(conn)

    def _take(self, chunk):
        self._buf += chunk
        while "\n" in self._buf:
            raw, self._buf = self._buf.split("\n", 1)
            try:
                line = raw.decode("utf-8")
            except Exception:
                line = raw.decode("utf-8", "replace")
            line = line.strip(u"\r").strip()
            if line:
                self._handle(line)

    def _handle(self, line):
        if line.startswith(u"ping "):
            stamp = line[5:].strip()
            if stamp:
                self._send_line(u"pong " + stamp)
            return
        if line.startswith(u"pong "):
            stamp = line[5:].strip()
            try:
                sent = int(stamp)
            except ValueError:
                return
            rtt = int(time.time() * 1000) - sent
            if rtt < 0:
                rtt = 0
            self._emit("rtt %d" % rtt)
            self._last_pong = time.time()
            if self._timed_out:
                self._timed_out = False
                self._emit("status connected")
            return
        if line == u"hello host" or line == u"hello guest":
            self._emit("status connected")
            return
        if line.startswith(u"move "):
            move = line[5:].strip()
            if move:
                self._emit(u"move " + move)
            return
        if line == u"resign" or line == u"quit" or line == u"opponent_left":
            self._emit("status opponent left")
            self._stop_now()
            return


_LINK = None


def get_link():
    global _LINK
    if _LINK is None:
        _LINK = NetplayLink()
    return _LINK
