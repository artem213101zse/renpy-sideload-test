# Зачем этот файл: уведомления лаборатории только на Android.
# Канал sideload_lab. «Сейчас» шлёт broadcast в процесс лаунчера.
# «Через 30 сек» ставит AlarmManager. Приёмник не в процессе :game,
# поэтому будильник живёт после killProcess.
# На ПК кнопок нет. Текст без квадратных скобок.

default notify_note = ""


init python:

    def _notify_fail(note):
        store.notify_note = note

    def sideload_notify_now():
        if not renpy.android:
            _notify_fail(u"Уведомления только на Android.")
            return
        try:
            from jnius import autoclass
        except Exception:
            _notify_fail(u"Уведомление не поставлено: нет jnius.")
            return
        try:
            PythonActivity = autoclass("org.renpy.android.PythonSDLActivity")
            activity = PythonActivity.mActivity
            if activity is None:
                _notify_fail(u"Уведомление не поставлено: нет экрана игры.")
                return
            Intent = autoclass("android.content.Intent")
            Receiver = autoclass("com.artemdev.sideloadlab.SideloadAlarmReceiver")
            intent = Intent(activity, Receiver)
            activity.sendBroadcast(intent)
            store.notify_note = u"Уведомление показано."
        except Exception:
            _notify_fail(u"Уведомление не показано: класс не найден.")

    def sideload_notify_later():
        if not renpy.android:
            _notify_fail(u"Уведомления только на Android.")
            return
        try:
            from jnius import autoclass
        except Exception:
            _notify_fail(u"Будильник не поставлен: нет jnius.")
            return
        try:
            PythonActivity = autoclass("org.renpy.android.PythonSDLActivity")
            activity = PythonActivity.mActivity
            if activity is None:
                _notify_fail(u"Будильник не поставлен: нет экрана игры.")
                return
            Context = autoclass("android.content.Context")
            Intent = autoclass("android.content.Intent")
            PendingIntent = autoclass("android.app.PendingIntent")
            AlarmManager = autoclass("android.app.AlarmManager")
            System = autoclass("java.lang.System")
            Version = autoclass("android.os.Build$VERSION")
            Receiver = autoclass("com.artemdev.sideloadlab.SideloadAlarmReceiver")

            intent = Intent(activity, Receiver)
            flags = PendingIntent.FLAG_UPDATE_CURRENT
            if Version.SDK_INT >= 31:
                flags = flags | PendingIntent.FLAG_IMMUTABLE
            pending = PendingIntent.getBroadcast(activity, 71, intent, flags)
            alarm = activity.getSystemService(Context.ALARM_SERVICE)
            when = System.currentTimeMillis() + 30000
            if Version.SDK_INT >= 23:
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending)
            else:
                alarm.setExact(AlarmManager.RTC_WAKEUP, when, pending)
            store.notify_note = u"Будильник на 30 сек. Сработает после закрытия игры."
        except Exception:
            _notify_fail(u"Будильник не поставлен: класс не найден.")
