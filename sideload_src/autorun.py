# -*- coding: utf-8 -*-
# Зачем этот файл: ранний хук сайдлоада для «The Question».
#
# Ren'Py 7.4.11 собирает список .rpy по config.searchpath внутри Script(),
# и это происходит раньше любого init python. Этот файл лежит в
# game/sideload.rpe и выполняется до сканирования.
# Скрипты и картинки в .rpa: main() после этого хука сам добавляет
# каждый *.rpa с searchpath в config.archives и вызывает index_archives.
# Это тот же путь, что у game/archive.rpa. Хук только не даёт битому
# sideload-архиву оборвать старт и не грузит scripts.rpa повторно,
# если lab_from_rpa.rpy уже лежит рядом россыпью.
#
# Куда класть файлы:
#   ПК: <папка проекта>/sideload
#   Android: всегда /storage/emulated/0/Documents/the_question_sideload
#   Переопределение: переменная окружения THE_QUESTION_SIDELOAD
#
# На Android ход хука пишется в
# Documents/the_question_sideload/hook.log

import os
import traceback

HOOK_LOG = "/storage/emulated/0/Documents/the_question_sideload/hook.log"
ANDROID_SIDELOAD = "/storage/emulated/0/Documents/the_question_sideload"


def _as_bytes(value):
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    try:
        return value.encode("utf-8")
    except Exception:
        return repr(value)


def _log(message):
    line = _as_bytes(message)
    try:
        print("hook: " + line)
    except Exception:
        print("hook: log line")
    try:
        folder = os.path.dirname(HOOK_LOG)
        if not os.path.isdir(folder):
            os.makedirs(folder)
        handle = open(HOOK_LOG, "ab")
        try:
            handle.write(line + "\n")
        finally:
            handle.close()
    except Exception:
        err = traceback.format_exc()
        try:
            print("hook.log write failed")
            print(err)
        except Exception:
            pass


def _is_android():
    if "ANDROID_PRIVATE" in os.environ or "ANDROID_PUBLIC" in os.environ:
        return True
    try:
        import renpy
        return bool(getattr(renpy, "android", False))
    except Exception:
        _log("android check failed\n" + traceback.format_exc())
        return False


def _remember_path(path):
    try:
        import renpy
        renpy.config.sideload_path = path
        if not hasattr(renpy.config, "sideload_ready"):
            renpy.config.sideload_ready = False
    except Exception:
        _log("could not set sideload_path\n" + traceback.format_exc())


def _install_rpa_guard(sideload_path):
    # renpy/main.py после .rpe делает то же для archive.rpa:
    #   renpy.config.archives.append(base)
    #   renpy.config.archives.reverse()
    #   renpy.loader.index_archives()
    # base — имя без .rpa. index_archives открывает файл через transfn
    # и RPAv3ArchiveHandler.read_index, без распаковки.
    import renpy
    import renpy.loader as loader

    if getattr(loader.index_archives, "_sideload_rpa_guard", False):
        return

    orig = loader.index_archives
    orig_transfn = loader.transfn

    def guarded_index_archives():
        if not hasattr(renpy.config, "sideload_rpa_trace"):
            renpy.config.sideload_rpa_trace = {}
        root = os.path.normcase(os.path.normpath(sideload_path))
        loose = os.path.isfile(os.path.join(sideload_path, "lab_from_rpa.rpy"))
        for base in ("scripts", "images"):
            full = os.path.join(sideload_path, base + ".rpa")
            if os.path.isfile(full) and base not in renpy.config.archives:
                renpy.config.archives.append(base)
        if loose:
            renpy.config.archives = [
                name for name in renpy.config.archives if name != "scripts"
            ]

        last = {"path": None, "name": None}

        def tracking(name):
            last["name"] = name
            path = orig_transfn(name)
            last["path"] = path
            return path

        loader.transfn = tracking
        removed = set()
        try:
            tries = 0
            while tries < 6:
                tries += 1
                last["path"] = None
                last["name"] = None
                loader.old_config_archives = None
                try:
                    orig()
                    break
                except Exception:
                    tb = traceback.format_exc()
                    path = last["path"]
                    name = last["name"] or ""
                    base, ext = os.path.splitext(name)
                    inside = False
                    if path:
                        norm = os.path.normcase(os.path.normpath(path))
                        inside = norm.startswith(root + os.sep)
                    if inside and base and base not in removed:
                        renpy.config.sideload_rpa_trace[base + ext] = tb
                        _log("rpa fail " + base + ext + "\n" + tb)
                        renpy.config.archives = [
                            item for item in renpy.config.archives if item != base
                        ]
                        removed.add(base)
                        continue
                    raise
            else:
                raise Exception("sideload rpa index retries exceeded")
        finally:
            loader.transfn = orig_transfn

        found = []
        for item in loader.archives:
            if item and item[0] in ("scripts.rpa", "images.rpa"):
                found.append(item[0])
        _log("rpa indexed " + ",".join(found))

    guarded_index_archives._sideload_rpa_guard = True
    loader.index_archives = guarded_index_archives


def _run_hook():
    _log("hook start")
    import renpy

    override = os.environ.get("THE_QUESTION_SIDELOAD")
    if override:
        path = override
        _log("path from THE_QUESTION_SIDELOAD")
    elif _is_android():
        # Всегда этот путь, даже если каталога ещё нет.
        path = ANDROID_SIDELOAD
        _log("path from android documents")
    else:
        path = os.path.join(renpy.config.basedir, "sideload")
        _log("path from project sideload")

    path = os.path.normpath(path)
    renpy.config.sideload_path = path
    renpy.config.sideload_ready = False
    _log("chosen path " + _as_bytes(path))

    try:
        if not os.path.isdir(path):
            os.makedirs(path)
        _log("mkdir ok")
    except Exception:
        _log("mkdir failed\n" + traceback.format_exc())

    try:
        if os.path.isdir(path):
            already = False
            for entry in renpy.config.searchpath:
                try:
                    if os.path.normpath(entry) == path:
                        already = True
                        break
                except Exception:
                    _log("searchpath entry failed\n" + traceback.format_exc())
            if not already:
                renpy.config.searchpath.append(path)
            renpy.config.sideload_ready = True
        else:
            _log("directory missing, sideload_path is still set")
    except Exception:
        _log("searchpath update failed\n" + traceback.format_exc())

    try:
        parts = []
        for entry in renpy.config.searchpath:
            parts.append(_as_bytes(entry))
        _log("searchpath " + " | ".join(parts))
    except Exception:
        _log("searchpath dump failed\n" + traceback.format_exc())

    _log("ready " + str(bool(renpy.config.sideload_ready)))
    try:
        _install_rpa_guard(path)
        _log("rpa guard installed")
    except Exception:
        _log("rpa guard failed\n" + traceback.format_exc())
    _log("hook end")


try:
    _run_hook()
except Exception:
    _log("hook exception\n" + traceback.format_exc())
    if _is_android():
        _remember_path(ANDROID_SIDELOAD)
    _log("hook end after exception")
