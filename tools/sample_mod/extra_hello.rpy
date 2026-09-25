# Зачем этот файл: учебный мод сайдлоада для «The Question».
# Его кладут в папку sideload/ (не в sideload/game). После перезапуска
# Ren'Py видит label extra_hello и картинку extra_hello.png.
# Сюжет новеллы не меняется: метка только показывает карточку и возвращается.

init python:
    # Список модов живёт в store, чтобы игра могла его прочитать.
    items = getattr(store, "sideload_items", None)
    if items is None:
        store.sideload_items = []
        items = store.sideload_items
    if "extra_hello" not in items:
        items.append("extra_hello")


image extra_hello_pic = "extra_hello.png"


label extra_hello:

    scene black
    show extra_hello_pic:
        xalign 0.5
        yalign 0.5

    "Сайдлоад сработал"

    return
