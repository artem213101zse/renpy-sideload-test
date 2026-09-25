# Зачем этот файл: запускает hello_engine.py из «The Question».
# На ПК — python из lib этого Ren'Py. На Android subprocess python не зовём:
# своего бинаря нет, sys.executable даёт Errno 2.
# Ошибка пишется в store.last_engine_line, игра не падает.
# Python 2.7, subprocess.Popen, таймаут 3 секунды.

default last_engine_line = ""


init python:
    import os
    import sys
    import time
    import traceback

    try:
        import subprocess
    except Exception:
        subprocess = None

    _ANDROID_ENGINE = "/storage/emulated/0/Documents/the_question_sideload/hello_engine.py"

    def _engine_text(data):
        if data is None:
            return u""
        if isinstance(data, unicode):
            return data
        if isinstance(data, str):
            try:
                return data.decode("utf-8")
            except Exception:
                return data.decode("latin-1", "replace")
        try:
            return unicode(data)
        except Exception:
            return u""

    def _on_android():
        if "ANDROID_PRIVATE" in os.environ or "ANDROID_PUBLIC" in os.environ:
            return True
        try:
            return bool(renpy.android)
        except Exception:
            return False

    def _native_binary():
        # Нативный файл движка, не hello_engine.py и не sys.executable.
        candidates = (
            "/storage/emulated/0/Documents/the_question_sideload/hello_engine",
            os.path.join(renpy.config.gamedir, "hello_engine"),
            os.path.join(renpy.config.basedir, "tools", "hello_engine"),
        )
        for path in candidates:
            try:
                if path and os.path.isfile(path):
                    return path
            except Exception:
                continue
        return None

    def _engine_python():
        # Только ПК. На Android sys.executable — само приложение, его не вызываем.
        if _on_android():
            return None

        base = renpy.config.renpy_base or ""
        bundled = [
            os.path.join(base, "lib", "windows-x86_64", "python.exe"),
            os.path.join(base, "lib", "windows-i686", "python.exe"),
            os.path.join(base, "lib", "linux-x86_64", "python"),
            os.path.join(base, "lib", "linux-i686", "python"),
            os.path.join(base, "lib", "mac-x86_64", "python"),
        ]
        for path in bundled:
            if os.path.isfile(path):
                return path

        exe = getattr(sys, "executable", None) or ""
        if exe and os.path.isfile(exe):
            return exe
        return None

    def _engine_from_apk():
        # game/hello_engine.py в APK не виден как обычный файл.
        pkg = sys.modules.get("renpy")
        if pkg is None:
            return None, u"нет модуля renpy"
        try:
            stream = pkg.loader.load("hello_engine.py")
            data = stream.read()
            try:
                stream.close()
            except Exception:
                pass
        except Exception:
            return None, u"нет файла hello_engine.py\n" + _engine_text(traceback.format_exc())

        folder = os.environ.get("ANDROID_PRIVATE") or renpy.config.gamedir
        try:
            if folder and (not os.path.isdir(folder)):
                os.makedirs(folder)
            dest = os.path.join(folder, "hello_engine_from_apk.py")
            handle = open(dest, "wb")
            try:
                handle.write(data)
            finally:
                handle.close()
        except Exception:
            return None, u"не удалось вынуть hello_engine.py из APK\n" + _engine_text(traceback.format_exc())
        return dest, None

    def _find_engine_script():
        game_py = os.path.join(renpy.config.gamedir, "hello_engine.py")
        tools_py = os.path.join(renpy.config.basedir, "tools", "hello_engine.py")
        for path in (_ANDROID_ENGINE, game_py, tools_py):
            try:
                if path and os.path.isfile(path):
                    return path, None
            except Exception:
                return None, u"нет файла hello_engine.py\n" + _engine_text(traceback.format_exc())
        if _on_android():
            return _engine_from_apk()
        return None, u"нет файла hello_engine.py"

    def _run_hello_engine():
        if _on_android():
            # sys.executable здесь — само приложение. Без нативного бинаря
            # Popen не вызываем, в тексте нет квадратных скобок.
            if not _native_binary():
                store.last_engine_line = u"на Android нужен нативный бинарь, не python subprocess"
                return
            store.last_engine_line = u"на Android нужен нативный бинарь, не python subprocess"
            return

        if subprocess is None:
            store.last_engine_line = u"нет subprocess"
            return

        script, problem = _find_engine_script()
        if not script:
            store.last_engine_line = problem or u"нет файла hello_engine.py"
            return

        python = _engine_python()
        if not python:
            store.last_engine_line = u"нет интерпретатора Ren'Py"
            return

        startupinfo = None
        if os.name == "nt":
            try:
                startupinfo = subprocess.STARTUPINFO()
                startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            except Exception:
                startupinfo = None

        try:
            proc = subprocess.Popen(
                [python, script],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=os.path.dirname(script) or None,
                startupinfo=startupinfo,
                )
        except Exception:
            store.last_engine_line = u"нет subprocess\n" + _engine_text(traceback.format_exc())
            return

        deadline = time.time() + 3
        while proc.poll() is None:
            if time.time() >= deadline:
                try:
                    proc.kill()
                except Exception:
                    pass
                store.last_engine_line = u"таймаут 3 сек"
                break
            time.sleep(0.05)

        try:
            out, err = proc.communicate()
        except Exception:
            store.last_engine_line = u"нет subprocess\n" + _engine_text(traceback.format_exc())
            return

        text = _engine_text(out).replace(u"\r", u"")
        line = u""
        for part in text.split(u"\n"):
            part = part.strip()
            if part:
                line = part
                break
        if line:
            store.last_engine_line = line
            return

        err_text = _engine_text(err).strip()
        if err_text:
            store.last_engine_line = err_text
            return
        if store.last_engine_line == u"таймаут 3 сек":
            return
        store.last_engine_line = u"движок ничего не напечатал, код %s" % proc.returncode

    def run_hello_engine():
        try:
            _run_hello_engine()
        except Exception:
            store.last_engine_line = _engine_text(traceback.format_exc())
