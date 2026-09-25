# Зачем этот файл: запускает заглушку движка из «The Question».
# На ПК — hello_engine.py через python из lib этого Ren'Py.
# На Android sys.executable не вызывается. Сначала files/hello_engine
# в private dir, если он исполняемый. Иначе /system/bin/sh echo.
# Ошибка пишется в store.last_engine_line без квадратных скобок.
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

    def _plain(data):
        text = _engine_text(data)
        return text.replace(u"[", u"(").replace(u"]", u")")

    def _private_engine():
        # getFilesDir()/hello_engine. Не Documents.
        private = os.environ.get("ANDROID_PRIVATE")
        if not private:
            return None
        path = os.path.join(private, "hello_engine")
        try:
            if os.path.isfile(path) and os.access(path, os.X_OK):
                return path
        except Exception:
            return None
        return None

    def _finish_process(proc):
        timed_out = False
        deadline = time.time() + 3
        while proc.poll() is None:
            if time.time() >= deadline:
                timed_out = True
                try:
                    proc.kill()
                except Exception:
                    pass
                break
            time.sleep(0.05)
        try:
            out, err = proc.communicate()
        except Exception:
            return None, _plain(traceback.format_exc())
        text = _plain(out).replace(u"\r", u"")
        line = u""
        for part in text.split(u"\n"):
            part = part.strip()
            if part:
                line = part
                break
        if line:
            return line, None
        if timed_out:
            return u"таймаут 3 сек", None
        err_text = _plain(err).strip()
        if err_text:
            return None, err_text
        return None, u"движок ничего не напечатал, код %s" % proc.returncode

    def _run_android_native():
        # sys.executable на Android не вызываем.
        if subprocess is None:
            store.last_engine_line = u"нет subprocess"
            return
        binary = _private_engine()
        if binary:
            cmd = [binary]
        else:
            cmd = ["/system/bin/sh", "-c", "echo HELLO ENGINE OK"]
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                )
        except Exception:
            store.last_engine_line = _plain(traceback.format_exc())
            return
        line, err = _finish_process(proc)
        if line:
            store.last_engine_line = line
        else:
            store.last_engine_line = err or u"движок ничего не напечатал"

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
            _run_android_native()
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
            store.last_engine_line = _plain(u"нет subprocess\n" + _engine_text(traceback.format_exc()))
            return

        line, err = _finish_process(proc)
        if line:
            store.last_engine_line = line
            return
        store.last_engine_line = err or u"движок ничего не напечатал"

    def run_hello_engine():
        try:
            _run_hello_engine()
        except Exception:
            store.last_engine_line = _plain(traceback.format_exc())
