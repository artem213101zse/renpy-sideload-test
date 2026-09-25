# Зачем этот файл: на Android возвращает из игры в Sideload BIOS.
# Игра живёт в процессе :game. Кнопка открывает LauncherActivity в обычном
# процессе и убивает процесс игры, чтобы SDL не держал UI.
# На ПК кнопки нет. Если jnius или класс лаунчера недоступны — короткая строка,
# не traceback.

default bios_open_note = ""


init python:

    def open_sideload_bios():
        if not renpy.android:
            store.bios_open_note = u"BIOS только на Android."
            return
        try:
            from jnius import autoclass
        except Exception:
            store.bios_open_note = u"BIOS не открылся: нет jnius."
            return
        try:
            PythonActivity = autoclass("org.renpy.android.PythonSDLActivity")
            Launcher = autoclass("com.artemdev.sideloadlab.LauncherActivity")
            Intent = autoclass("android.content.Intent")
            activity = PythonActivity.mActivity
            if activity is None:
                store.bios_open_note = u"BIOS не открылся: нет PythonSDLActivity."
                return
            intent = Intent(activity, Launcher)
            intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            activity.startActivity(intent)
            Process = autoclass("android.os.Process")
            Process.killProcess(Process.myPid())
        except Exception:
            store.bios_open_note = u"BIOS не открылся: класс лаунчера не найден."
