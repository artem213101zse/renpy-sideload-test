# Зачем этот файл: экран «Sideload» в меню «The Question».
# Показывает внешнюю папку и файлы .rpy и картинки, которые в ней лежат.
# Саму папку в config.searchpath добавляет game/sideload.rpe
# (исходник: sideload_src/autorun.py). Сюжет новеллы этот файл не меняет.

init python:
    import os

    # renpy здесь уже есть: это renpy.exports. Повторный import renpy
    # подменяет его пакетом движка, и ломает renpy.pure в общих скриптах.

    _SIDELOAD_SCRIPT_EXT = (".rpy", ".rpym")
    _SIDELOAD_IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")

    def _sideload_text(value):
        if value is None:
            return u""
        if isinstance(value, unicode):
            return value
        if isinstance(value, str):
            try:
                return value.decode("utf-8")
            except Exception:
                return value.decode("latin-1", "replace")
        return unicode(value)

    def sideload_directory():
        path = getattr(renpy.config, "sideload_path", None)
        return _sideload_text(path)

    def sideload_is_ready():
        return bool(getattr(renpy.config, "sideload_ready", False))

    def sideload_entries():
        path = getattr(renpy.config, "sideload_path", None)
        if not path or not os.path.isdir(path):
            return []

        found = []
        for root, dirs, files in os.walk(path):
            dirs[:] = [name for name in dirs if not name.startswith(".")]
            for name in files:
                if name.startswith("."):
                    continue
                low = name.lower()
                if not (low.endswith(_SIDELOAD_SCRIPT_EXT) or low.endswith(_SIDELOAD_IMAGE_EXT)):
                    continue
                full = os.path.join(root, name)
                rel = os.path.relpath(full, path)
                found.append(_sideload_text(rel).replace(u"\\", u"/"))
        found.sort()
        return found


screen sideload_status():

    tag menu

    default entries = sideload_entries()
    $ sideload_path_text = sideload_directory()

    use game_menu(_("Sideload"), scroll="viewport"):

        vbox:
            spacing 12

            text _("External folder. New .rpy scripts and images placed here are loaded through config.searchpath. Restart the game after adding files.")

            text _("Path: [sideload_path_text]")

            if not sideload_path_text:
                text _("смотри hook.log")

            if sideload_is_ready():
                text _("This folder is on config.searchpath.")
            else:
                text _("This folder is not on config.searchpath. The early hook did not run.")

            if entries:
                text _("Found:")
                for name in entries:
                    text "[name]"
            else:
                text _("Nothing here yet. Drop a .rpy or an image into the folder and restart.")

            if renpy.has_label("extra_hello"):
                textbutton _("Открыть мод") action Start("extra_hello")
            else:
                text _("Мод ещё не загружен. Распакуйте zip в папку sideload и перезапустите игру.")

            textbutton _("Запустить движок") action Function(run_hello_engine)
            text last_engine_line substitute False


translate russian strings:

    old "Sideload"
    new "Сайдлоад"

    old "External folder. New .rpy scripts and images placed here are loaded through config.searchpath. Restart the game after adding files."
    new "Внешняя папка. Новые .rpy и картинки отсюда подхватываются через config.searchpath. После добавления файлов игру нужно перезапустить."

    old "Path: [sideload_path_text]"
    new "Путь: [sideload_path_text]"

    old "This folder is on config.searchpath."
    new "Эта папка стоит в config.searchpath."

    old "This folder is not on config.searchpath. The early hook did not run."
    new "Этой папки нет в config.searchpath. Ранний хук не сработал."

    old "Found:"
    new "Найдено:"

    old "Nothing here yet. Drop a .rpy or an image into the folder and restart."
    new "Пока пусто. Положите сюда .rpy или картинку и перезапустите игру."
