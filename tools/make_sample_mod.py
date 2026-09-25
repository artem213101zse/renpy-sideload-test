# -*- coding: utf-8 -*-
# Зачем этот файл: собирает incoming/sample_mod.zip из tools/sample_mod.
# В архиве файлы лежат в корне. Распаковывать zip нужно в папку sideload/,
# потому что config.searchpath указывает на неё, а не на sideload/game.

from __future__ import print_function

import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "sample_mod")
# Этот zip лежит в git: tools/sample_mod.zip
DST = os.path.join(HERE, "sample_mod.zip")
INCOMING = os.path.normpath(os.path.join(HERE, "..", "incoming", "sample_mod.zip"))
NAMES = ("extra_hello.rpy", "extra_hello.png")


def main():
    folder = os.path.dirname(DST)
    if not os.path.isdir(folder):
        os.makedirs(folder)

    archive = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
    try:
        for name in NAMES:
            path = os.path.join(SRC, name)
            if not os.path.isfile(path):
                raise Exception("missing " + path)
            # Имя в архиве без каталога: после распаковки в sideload/
            # файл оказывается прямо там.
            archive.write(path, name)
    finally:
        archive.close()

    print("wrote " + DST)
    incoming_dir = os.path.dirname(INCOMING)
    if not os.path.isdir(incoming_dir):
        os.makedirs(incoming_dir)
    incoming = open(INCOMING, "wb")
    try:
        src = open(DST, "rb")
        try:
            incoming.write(src.read())
        finally:
            src.close()
    finally:
        incoming.close()
    print("copied " + INCOMING)
    print("unpack into the_question/sideload/")


if __name__ == "__main__":
    main()
