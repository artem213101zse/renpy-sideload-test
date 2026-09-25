# -*- coding: utf-8 -*-
# Зачем этот файл: ранний хук сайдлоада для «The Question».
#
# Ren'Py 7.4.11 собирает список .rpy по config.searchpath внутри Script(),
# и это происходит раньше любого init python. Этот файл лежит в
# game/sideload.rpe и выполняется до сканирования.
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
    _log("hook end")


try:
    _run_hook()
except Exception:
    _log("hook exception\n" + traceback.format_exc())
    if _is_android():
        _remember_path(ANDROID_SIDELOAD)
    _log("hook end after exception")
