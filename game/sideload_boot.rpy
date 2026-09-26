# Зачем этот файл: тумблер «Открывать Ren'Py сразу».
# На Android создаёт или удаляет
# Documents/the_question_sideload/flags/boot_renpy
# На ПК кнопка видна, но флаг не пишет и лаунчер не меняет.

default boot_note = ""


init python:
    import os

    _BOOT_FLAG = "/storage/emulated/0/Documents/the_question_sideload/flags/boot_renpy"

    def sideload_boot_on():
        if not renpy.android:
            return False
        try:
            return os.path.isfile(_BOOT_FLAG)
        except Exception:
            return False

    def sideload_toggle_boot():
        if not renpy.android:
            store.boot_note = u"на ПК не влияет"
            return
        folder = os.path.dirname(_BOOT_FLAG)
        try:
            if not os.path.isdir(folder):
                os.makedirs(folder)
            if os.path.isfile(_BOOT_FLAG):
                os.remove(_BOOT_FLAG)
                store.boot_note = u"Открывать Ren'Py сразу: выключено"
            else:
                handle = open(_BOOT_FLAG, "wb")
                try:
                    handle.write("1\n")
                finally:
                    handle.close()
                store.boot_note = u"Открывать Ren'Py сразу: включено"
        except Exception:
            store.boot_note = u"Флаг не записан"
