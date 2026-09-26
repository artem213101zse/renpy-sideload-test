# -*- coding: utf-8 -*-
# Зачем этот файл: собирает tools/content_pack.zip и его sha256.
# Пакет не кладётся в game/, его скачивают отдельно.

from __future__ import print_function

import hashlib
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "content_pack")
DST = os.path.join(HERE, "content_pack.zip")
NAMES = ("extra_pack.rpy", "extra_pack.png")


def main():
    archive = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
    try:
        for name in NAMES:
            path = os.path.join(SRC, name)
            if not os.path.isfile(path):
                raise Exception("missing " + path)
            archive.write(path, name)
    finally:
        archive.close()
    raw = open(DST, "rb").read()
    digest = hashlib.sha256(raw).hexdigest()
    out = open(DST + ".sha256", "wb")
    try:
        out.write(digest + "\n")
    finally:
        out.close()
    print("wrote " + DST)
    print(digest)


if __name__ == "__main__":
    main()
