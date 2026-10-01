# -*- coding: utf-8 -*-
# Зачем этот файл: распаковать RPA-3.0 без EXE.
# Индекс читается так же, как renpy.loader.RPAv3ArchiveHandler:
# заголовок "RPA-3.0 ", смещение и ключ в hex, zlib+pickle,
# запись (offset ^ key, length ^ key, start). Байты файла не XOR:
# в 7.4.11 start — это префикс, остальное лежит в архиве как есть.

from __future__ import print_function

import os
import sys
import zlib

try:
    from cPickle import loads
except ImportError:
    from pickle import loads


def _as_text(value):
    if value is None:
        return u""
    if isinstance(value, unicode):
        return value
    if isinstance(value, str):
        for enc in ("utf-8", "cp1251", "latin-1"):
            try:
                return value.decode(enc)
            except Exception:
                pass
        return value.decode("latin-1", "replace")
    try:
        return unicode(value)
    except Exception:
        return u""


def _as_bytes(value):
    if value is None:
        return b""
    if isinstance(value, unicode):
        return value.encode("latin-1", "replace")
    if isinstance(value, str):
        return value
    return bytes(value)


def _not_rpa(detail):
    if detail:
        return u"не RPA: " + _as_text(detail)
    return u"не RPA"


def read_rpa_index(path):
    """
    Return (index, None) for an RPA-3.0 file.
    index maps a filename to a list of (offset, length, start) parts.
    On failure return (None, error text).
    """
    handle = open(path, "rb")
    try:
        header = handle.read(40)
        if not header.startswith(b"RPA-3.0 "):
            return None, _not_rpa(u"")
        try:
            offset = int(header[8:24], 16)
            key = int(header[25:33], 16)
        except Exception:
            return None, _not_rpa(u"заголовок")
        handle.seek(offset)
        try:
            index = loads(zlib.decompress(handle.read()))
        except Exception as exc:
            return None, _not_rpa(exc)
    finally:
        handle.close()

    clean = {}
    try:
        keys = list(index.keys())
    except Exception as exc:
        return None, _not_rpa(exc)
    for name in keys:
        parts = []
        for entry in index[name]:
            if len(entry) == 2:
                part_off, part_len = entry
                start = b""
            else:
                part_off, part_len, start = entry
            parts.append((part_off ^ key, part_len ^ key, _as_bytes(start)))
        clean[_as_text(name).replace(u"\\", u"/")] = parts
    return clean, None


def _part_bytes(handle, offset, length, start):
    if length < 0:
        raise ValueError("bad length")
    take = len(start)
    if take > length:
        take = length
    rest = length - take
    handle.seek(offset)
    body = handle.read(rest) if rest else b""
    if len(body) != rest:
        raise ValueError("short read")
    return start[:take] + body


def _safe_parts(name):
    text = _as_text(name).replace(u"\\", u"/").lstrip(u"/")
    if not text or u":" in text.split(u"/")[0]:
        raise ValueError("bad name")
    parts = text.split(u"/")
    if any(part in (u"", u".", u"..") for part in parts):
        raise ValueError("bad name")
    return parts


def _inside(root, path):
    root_norm = os.path.normcase(os.path.abspath(root))
    path_norm = os.path.normcase(os.path.abspath(path))
    if path_norm == root_norm:
        return True
    prefix = root_norm + os.sep
    return path_norm.startswith(prefix)


def extract_rpa(archive_path, dest_dir):
    """
    Unpack one RPA-3.0 archive into dest_dir.
    Returns (count, None) or (0, error text).
    """
    index, error = read_rpa_index(archive_path)
    if error:
        return 0, error
    if not os.path.isdir(dest_dir):
        os.makedirs(dest_dir)
    handle = open(archive_path, "rb")
    count = 0
    try:
        for name in sorted(index.keys()):
            parts = _safe_parts(name)
            target = dest_dir
            for part in parts:
                target = os.path.join(target, part)
            if not _inside(dest_dir, target):
                raise ValueError("bad name")
            parent = os.path.dirname(target)
            if parent and not os.path.isdir(parent):
                os.makedirs(parent)
            blob = b""
            for offset, length, start in index[name]:
                blob += _part_bytes(handle, offset, length, start)
            out = open(target, "wb")
            try:
                out.write(blob)
            finally:
                out.close()
            count += 1
    except Exception as exc:
        return count, _as_text(exc)
    finally:
        handle.close()
    return count, None


def main(argv):
    if len(argv) != 3:
        print("usage: rpa_extract.py archive.rpa dest_dir")
        return 2
    count, error = extract_rpa(argv[1], argv[2])
    if error:
        print(_as_text(error).encode("utf-8"))
        return 1
    print("files %d" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
