# -*- coding: utf-8 -*-
# Зачем этот файл: собрать tools/scripts.rpa и tools/images.rpa
# классом archiver.Archive. Его же вызывает
# distribute.Distributor.archive_files после build.archive / build.classify:
#   af = archiver.Archive(arcpath)
#   name = "/".join(entry.name.split("/")[1:])
#   af.add(name, entry.path)
#   af.close()
# Classify в options.rpy игры не включается: обычная сборка
# не должна прятать сценарий The Question.

from __future__ import print_function

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
QUESTION = os.path.normpath(os.path.join(HERE, ".."))
SDK = os.path.normpath(os.path.join(QUESTION, ".."))
ARCHIVER = os.path.join(SDK, "launcher", "game", "archiver.rpy")
RENPY = os.path.join(SDK, "renpy.py")

LAB_RPY = """\
# Файл внутри scripts.rpa. Метка для экрана Sideload.
label lab_from_rpa:
    "LAB RPA SCRIPT OK"
    return
"""


def _png_bytes(width, height, rgb):
    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    row = b"\x00" + (struct.pack("BBB", rgb[0], rgb[1], rgb[2]) * width)
    raw = row * height
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def _archive_class():
    raw = open(ARCHIVER, "rb").read()
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    text = raw.decode("utf-8")
    body = []
    started = False
    for line in text.splitlines():
        if not started:
            if line.startswith("init python in archiver:"):
                started = True
            continue
        if line.startswith("    "):
            body.append(line[4:])
        elif line.strip() == "":
            body.append("")
        else:
            break
    if not body:
        raise Exception("archiver.Archive was not found")
    source = "from __future__ import unicode_literals\n" + "\n".join(body) + "\n"
    ns = {"_dict": dict, "_list": list, "__name__": "archiver"}
    exec source in ns
    return ns["Archive"]


def _compile_rpyc(game_dir):
    env = os.environ.copy()
    env["SDL_VIDEODRIVER"] = "dummy"
    env["SDL_AUDIODRIVER"] = "dummy"
    project = os.path.dirname(game_dir)
    subprocess.check_call(
        [sys.executable, RENPY, project, "compile"],
        cwd=SDK,
        env=env,
    )


def _pack(archive_cls, arc_path, files):
    if os.path.exists(arc_path):
        os.remove(arc_path)
    archive = archive_cls(arc_path)
    for name, path in files:
        archive.add(name, path)
    archive.close()


def _copy(src, dest):
    parent = os.path.dirname(dest)
    if not os.path.isdir(parent):
        os.makedirs(parent)
    shutil.copyfile(src, dest)


def main():
    sys.path.insert(0, HERE)
    import rpa_extract

    archive_cls = _archive_class()
    temp_root = tempfile.mkdtemp(prefix="tq-lab-rpa-")
    try:
        game = os.path.join(temp_root, "game")
        os.makedirs(game)
        rpy_path = os.path.join(game, "lab_from_rpa.rpy")
        png_path = os.path.join(game, "lab_from_rpa.png")
        rpy_handle = open(rpy_path, "wb")
        try:
            rpy_handle.write(LAB_RPY.encode("utf-8"))
        finally:
            rpy_handle.close()
        png_handle = open(png_path, "wb")
        try:
            png_handle.write(_png_bytes(64, 64, (40, 220, 120)))
        finally:
            png_handle.close()

        _compile_rpyc(game)
        rpyc_path = rpy_path + "c"
        if not os.path.isfile(rpyc_path):
            raise Exception("Ren'Py did not write lab_from_rpa.rpyc")

        built = os.path.join(temp_root, "built")
        os.makedirs(built)
        scripts_rpa = os.path.join(built, "scripts.rpa")
        images_rpa = os.path.join(built, "images.rpa")
        _pack(
            archive_cls,
            scripts_rpa,
            [
                (u"lab_from_rpa.rpy", rpy_path),
                (u"lab_from_rpa.rpyc", rpyc_path),
            ],
        )
        _pack(archive_cls, images_rpa, [(u"lab_from_rpa.png", png_path)])

        check = os.path.join(temp_root, "check")
        count, error = rpa_extract.extract_rpa(scripts_rpa, os.path.join(check, "scripts"))
        if error or count != 2:
            raise Exception("scripts.rpa roundtrip failed: %s count=%s" % (error, count))
        count, error = rpa_extract.extract_rpa(images_rpa, os.path.join(check, "images"))
        if error or count != 1:
            raise Exception("images.rpa roundtrip failed: %s count=%s" % (error, count))
        got = open(os.path.join(check, "scripts", "lab_from_rpa.rpy"), "rb").read()
        if b"LAB RPA SCRIPT OK" not in got:
            raise Exception("unpacked script has no LAB RPA SCRIPT OK")
        got_png = open(os.path.join(check, "images", "lab_from_rpa.png"), "rb").read()
        if got_png != open(png_path, "rb").read():
            raise Exception("unpacked png does not match")

        for folder in (HERE, os.path.join(QUESTION, "incoming")):
            _copy(scripts_rpa, os.path.join(folder, "scripts.rpa"))
            _copy(images_rpa, os.path.join(folder, "images.rpa"))
        print("wrote tools/scripts.rpa tools/images.rpa and incoming copies")
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)


if __name__ == "__main__":
    main()
