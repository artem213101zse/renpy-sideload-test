# Зачем этот файл: два способа прочитать RPA в одной сборке.
# Способ 1 не распаковывает: Ren'Py сам кладёт basename в config.archives
# (renpy/main.py, тот же список, что у archive.rpa) и вызывает
# renpy.loader.index_archives(). .rpy из архива движок не исполняет,
# поэтому в scripts.rpa рядом с исходником лежит .rpyc этой же сборки.
# Способ 2 — кнопка. Она вызывает tools/rpa_extract.py и пишет файлы
# рядом с README, даже если способ 1 архив не открыл.

default rpa_extract_note = ""


init python:
    import os
    import sys
    import traceback

    def _rpa_text(value):
        if value is None:
            return u""
        if isinstance(value, unicode):
            return value
        if isinstance(value, str):
            for enc in ("utf-8", "cp1251", "latin-1"):
                try:
                    return value.decode(enc)
                except Exception:
                    continue
            return value.decode("latin-1", "replace")
        try:
            return unicode(value)
        except Exception:
            return u""

    def _rpa_loader():
        return sys.modules.get("renpy.loader")

    def _rpa_extract_mod():
        # Кэш на функции, не в store: модуль нельзя класть в откат и сейв.
        cached = getattr(_rpa_extract_mod, "_mod", None)
        if cached is not None:
            return cached
        import types
        loader = _rpa_loader()
        tools_path = os.path.join(renpy.config.basedir, "tools", "rpa_extract.py")
        if os.path.isfile(tools_path):
            handle = open(tools_path, "rb")
            try:
                source = handle.read()
            finally:
                handle.close()
            filename = tools_path
        else:
            if loader is None:
                raise Exception("rpa_extract.py is not loadable")
            source = loader.load("rpa_extract.py").read()
            filename = "rpa_extract.py"
        if source.startswith(b"\xef\xbb\xbf"):
            source = source[3:]
        # init python включает unicode_literals, а ModuleType ждёт байтовое имя.
        module = types.ModuleType(b"rpa_extract_sideload")
        module.__file__ = filename
        module.__dict__["__file__"] = filename
        exec compile(source, filename, b"exec") in module.__dict__
        _rpa_extract_mod._mod = module
        return module

    def rpa_archive_line(filename):
        try:
            return _rpa_archive_line(filename)
        except Exception:
            return _rpa_text(filename) + u":\n" + _rpa_text(traceback.format_exc())

    def _rpa_archive_line(filename):
        name = _rpa_text(filename)
        trace = getattr(renpy.config, "sideload_rpa_trace", None) or {}
        if name in trace or filename in trace:
            body = trace.get(name, trace.get(filename, u""))
            return name + u":\n" + _rpa_text(body)
        loader = _rpa_loader()
        if loader is not None:
            for item in loader.archives:
                if item and item[0] == filename:
                    return name + u": opened"
        path = getattr(renpy.config, "sideload_path", None)
        if not path:
            return name + u": нет файла"
        full = os.path.join(path, filename)
        if not os.path.isfile(full):
            return name + u": нет файла"
        index, error = _rpa_extract_mod().read_rpa_index(full)
        if error or index is None:
            return name + u":\n" + _rpa_text(error)
        return name + u": opened"

    def _rpa_log(path, text):
        try:
            handle = open(os.path.join(path, "rpa_extract.log"), "ab")
            try:
                handle.write(_rpa_text(text).encode("utf-8") + b"\n")
            finally:
                handle.close()
        except Exception:
            pass

    def rpa_extract_sideload():
        path = getattr(renpy.config, "sideload_path", None)
        if not path:
            path = "/storage/emulated/0/Documents/the_question_sideload"
        lines = []
        total = 0
        try:
            module = _rpa_extract_mod()
            found = False
            for filename in ("scripts.rpa", "images.rpa"):
                full = os.path.join(path, filename)
                if not os.path.isfile(full):
                    lines.append(_rpa_text(filename) + u": нет файла")
                    continue
                found = True
                count, error = module.extract_rpa(full, path)
                total += count
                if error:
                    lines.append(_rpa_text(filename) + u": " + _rpa_text(error))
                else:
                    lines.append(_rpa_text(filename) + u": " + _rpa_text(count))
            if found:
                lines.append(u"файлов " + _rpa_text(total))
            else:
                lines.append(u"положи scripts.rpa и images.rpa рядом с README")
        except Exception:
            lines.append(_rpa_text(traceback.format_exc()))
        note = u"\n".join(lines)
        store.rpa_extract_note = note
        _rpa_log(path, note)
