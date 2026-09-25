# Зачем этот файл: запускает заглушку tools/hello_engine.py из «The Question».
# subprocess.Popen, Python 2.7, таймаут 3 секунды. Строка stdout попадает
# в store.last_engine_line. Ошибка движка игру не роняет.

default last_engine_line = ""


init python:
    import os
    import subprocess
    import time

    def _engine_text(data):
        if data is None:
            return u""
        if isinstance(data, unicode):
            return data
        try:
            return data.decode("utf-8")
        except Exception:
            return data.decode("latin-1", "replace")

    def _engine_python():
        base = renpy.config.renpy_base
        candidates = [
            os.path.join(base, "lib", "windows-x86_64", "python.exe"),
            os.path.join(base, "lib", "windows-i686", "python.exe"),
            os.path.join(base, "lib", "linux-x86_64", "python"),
            os.path.join(base, "lib", "linux-i686", "python"),
            os.path.join(base, "lib", "mac-x86_64", "python"),
        ]
        for path in candidates:
            if os.path.isfile(path):
                return path
        return None

    def run_hello_engine():
        try:
            _run_hello_engine()
        except Exception:
            store.last_engine_line = u""

    def _run_hello_engine():
        tools = os.path.join(renpy.config.basedir, "tools")
        script = os.path.join(tools, "hello_engine.py")
        python = _engine_python()
        if (not python) or (not os.path.isfile(script)):
            store.last_engine_line = u""
            return

        startupinfo = None
        if os.name == "nt":
            try:
                startupinfo = subprocess.STARTUPINFO()
                startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            except Exception:
                startupinfo = None

        proc = subprocess.Popen(
            [python, script],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=tools,
            startupinfo=startupinfo,
            )

        deadline = time.time() + 3
        while proc.poll() is None:
            if time.time() >= deadline:
                try:
                    proc.kill()
                except Exception:
                    pass
                break
            time.sleep(0.05)

        try:
            out, err = proc.communicate()
        except Exception:
            out = ""

        text = _engine_text(out).replace(u"\r", u"")
        line = u""
        for part in text.split(u"\n"):
            part = part.strip()
            if part:
                line = part
                break

        store.last_engine_line = line
