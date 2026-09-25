# Зачем этот файл: удаляет файлы сабмода из папки сайдлоада.
# Стирает только extra_hello.rpy, extra_hello.png и extra_hello.rpyc.
# incoming и остальные файлы не трогает. Метка extra_inject остаётся
# до перезапуска, потому что Ren'Py уже загрузил скрипт в этот процесс.

default sideload_remove_note = ""


init python:
    import os

    def sideload_remove_mod():
        path = getattr(renpy.config, "sideload_path", None)
        if not path:
            path = "/storage/emulated/0/Documents/the_question_sideload"

        names = ("extra_hello.rpy", "extra_hello.png", "extra_hello.rpyc")
        lines = []
        for name in names:
            full = os.path.join(path, name)
            try:
                if os.path.isfile(full):
                    os.remove(full)
                    lines.append(u"удалён " + name)
                else:
                    lines.append(u"нет " + name)
            except Exception:
                lines.append(u"не удалился " + name)

        lines.append(u"Перезапусти игру, метки снимутся после рестарта.")
        lines.append(u"Пока процесс жив, метка extra_inject останется.")
        store.sideload_remove_note = u"\n".join(lines)
