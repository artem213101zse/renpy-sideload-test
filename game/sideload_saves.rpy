# Зачем этот файл: сейвы и persistent лежат рядом с сайдлоадом.
# Android: /storage/emulated/0/Documents/the_question_sideload/saves
# ПК: the_question/sideload/saves
#
# В 7.4.11 main() после load_script смотрит config.savedir.
# Если он ещё None, вызывается path_to_saves, и только потом
# читается persistent. Обычный init выполняется позже, поэтому
# папка задаётся здесь, в python early.

python early:
    import os

    _android = ("ANDROID_PRIVATE" in os.environ) or ("ANDROID_PUBLIC" in os.environ)
    try:
        if renpy.android:
            _android = True
    except Exception:
        pass

    if _android:
        _saves = "/storage/emulated/0/Documents/the_question_sideload/saves"
    else:
        _saves = os.path.join(renpy.config.basedir, "sideload", "saves")

    try:
        if not os.path.isdir(_saves):
            os.makedirs(_saves)
    except Exception:
        pass

    renpy.config.savedir = _saves
    print("sideload saves: " + _saves)
