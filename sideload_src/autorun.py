# -*- coding: utf-8 -*-
# Зачем этот файл: ранний хук сайдлоада для «The Question».
#
# Ren'Py 7.4.11 собирает список .rpy по config.searchpath внутри Script(),
# и это происходит раньше любого init python. Блок init уже не успевает
# подключить новые скрипты. Этот файл лежит в game/sideload.rpe и
# выполняется в main() до сканирования, поэтому папка попадает в
# config.searchpath вовремя. Картинки ищутся тем же списком в момент показа.
#
# Куда класть файлы:
#   ПК: <папка проекта>/sideload
#   Android: Documents/the_question_sideload, если каталог уже есть,
#            иначе <ANDROID_PUBLIC>/sideload
#   Переопределение: переменная окружения THE_QUESTION_SIDELOAD

import os
import renpy


def _pick_sideload_dir():
    override = os.environ.get("THE_QUESTION_SIDELOAD")
    if override:
        return override

    on_android = bool(getattr(renpy, "android", False)) or ("ANDROID_PUBLIC" in os.environ)
    if on_android:
        for root in ("/sdcard", "/storage/emulated/0"):
            documents = os.path.join(root, "Documents", "the_question_sideload")
            if os.path.isdir(documents):
                return documents
        public = os.environ.get("ANDROID_PUBLIC")
        if public:
            return os.path.join(public, "sideload")

    return os.path.join(renpy.config.basedir, "sideload")


path = os.path.normpath(_pick_sideload_dir())
renpy.config.sideload_path = path
renpy.config.sideload_ready = False

if not os.path.isdir(path):
    try:
        os.makedirs(path)
    except Exception:
        pass

if os.path.isdir(path):
    already = False
    for entry in renpy.config.searchpath:
        if os.path.normpath(entry) == path:
            already = True
            break
    if not already:
        renpy.config.searchpath.append(path)
    renpy.config.sideload_ready = True

try:
    shown = path
    if not isinstance(shown, str):
        shown = shown.encode("utf-8")
    print("sideload: " + shown)
except Exception:
    print("sideload: path set")
