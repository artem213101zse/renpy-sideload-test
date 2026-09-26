# -*- coding: utf-8 -*-
# Зачем этот файл: кладёт одну и ту же оболочку BIOS в места, откуда
# её реально забирает сборка. WebView android_asset сам по себе пустой,
# если www не скопирован в app/assets без префикса x-.
# R.raw живёт в модуле renpyandroid: лаунчер импортирует org.renpy.android.R.
# game/bios_www Ren'Py пакует как файлы игры; Java эти x-assets не читает.

from __future__ import print_function

import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
QUESTION = os.path.normpath(os.path.join(HERE, ".."))
SDK = os.path.normpath(os.path.join(QUESTION, ".."))

WWW_SRC = os.path.join(QUESTION, "rapt-overlay", "assets", "www")
# Имя в www -> имя в res/raw. Расширение отрезает aapt, поле R.raw остаётся.
RAW_NAMES = (
    ("index.html", "bios_index.html"),
    ("styles.css", "bios_styles.css"),
    ("app.js", "bios_app.js"),
)

JAVA_SRC = os.path.join(
    QUESTION, "rapt-overlay", "java", "com", "artemdev", "sideloadlab", "LauncherActivity.java")


def copy_bytes(src, dst):
    folder = os.path.dirname(dst)
    if not os.path.isdir(folder):
        os.makedirs(folder)
    shutil.copyfile(src, dst)
    print("copied " + src + " -> " + dst)


def copy_www_tree(dest_dir):
    for name, _raw in RAW_NAMES:
        src = os.path.join(WWW_SRC, name)
        if not os.path.isfile(src):
            raise Exception("missing " + src)
        copy_bytes(src, os.path.join(dest_dir, name))


def copy_raw_tree(dest_dir):
    for name, raw_name in RAW_NAMES:
        src = os.path.join(WWW_SRC, name)
        if not os.path.isfile(src):
            raise Exception("missing " + src)
        copy_bytes(src, os.path.join(dest_dir, raw_name))


def main():
    copy_www_tree(os.path.join(QUESTION, "game", "bios_www"))
    copy_raw_tree(os.path.join(QUESTION, "rapt-overlay", "res", "raw"))

    rapt = os.path.join(SDK, "rapt", "project")
    if not os.path.isdir(rapt):
        print("rapt tree missing, skip " + rapt)
        return

    copy_www_tree(os.path.join(rapt, "app", "src", "main", "assets", "www"))
    copy_raw_tree(os.path.join(rapt, "renpyandroid", "src", "main", "res", "raw"))

    java_dst = os.path.join(
        rapt, "renpyandroid", "src", "main", "java",
        "com", "artemdev", "sideloadlab", "LauncherActivity.java")
    if os.path.isdir(os.path.dirname(java_dst)) and os.path.isfile(JAVA_SRC):
        copy_bytes(JAVA_SRC, java_dst)
    else:
        print("java dest missing, skip " + java_dst)


if __name__ == "__main__":
    main()
