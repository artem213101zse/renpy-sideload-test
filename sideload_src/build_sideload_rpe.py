# -*- coding: utf-8 -*-
# Зачем этот файл: собирает game/sideload.rpe из autorun.py.
# .rpe — это zip с autorun.py. Ren'Py запускает его до загрузки сценария.

from __future__ import print_function

import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "autorun.py")
DST = os.path.normpath(os.path.join(HERE, "..", "game", "sideload.rpe"))


def main():
    if not os.path.isfile(SRC):
        raise Exception("missing " + SRC)
    archive = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
    try:
        archive.write(SRC, "autorun.py")
    finally:
        archive.close()
    print("wrote " + DST)


if __name__ == "__main__":
    main()
