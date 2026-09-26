# Зачем этот файл: маленький пакет, которого нет внутри APK.
# Его скачивают в Documents/the_question_sideload и подхватывает сайдлоад.
# label start не заменяет. История зовёт extra_pack только если метка уже есть.

image extra_pack_pic = "extra_pack.png"


label extra_pack:

    scene black
    show extra_pack_pic:
        xalign 0.5
        yalign 0.5

    "этот контент скачали, его не было в APK"

    return
