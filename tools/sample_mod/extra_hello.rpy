# Зачем этот файл: сабмод сайдлоада для «The Question».
# Его кладут в папку sideload/. Он не заменяет label start.
# Если хук подхватил файл, начало истории делает call extra_inject,
# показывает картинку и строки мода, потом возвращается в обычный сюжет.

init python:
    store.extra_mod_active = True

    items = getattr(store, "sideload_items", None)
    if items is None:
        store.sideload_items = []
        items = store.sideload_items
    if "extra_hello" not in items:
        items.append("extra_hello")


image extra_hello_pic = "extra_hello.png"


label extra_inject:

    scene black
    show extra_hello_pic:
        xalign 0.5
        yalign 0.5

    "Это строка из сабмода."

    "Картинка и эти реплики приехали из папки сайдлоада, не из script.rpy."

    "Дальше снова обычная история The Question."

    return


label extra_hello:

    call extra_inject
    return
