Зачем эта папка: копия Java-лаунчера, который открывается до Ren'Py.
В git the_question попадает только она. Дерево rapt/ не коммитится.

Куда вставлялось

1. Java
   Источник копии: rapt-overlay/java/com/artemdev/sideloadlab/LauncherActivity.java
   Куда положен класс:
   rapt/project/renpyandroid/src/main/java/com/artemdev/sideloadlab/LauncherActivity.java
   Рядом с найденным
   rapt/project/renpyandroid/src/main/java/org/renpy/android/PythonSDLActivity.java

2. Манифест
   Фрагмент: rapt-overlay/AndroidManifest.fragment.xml
   Вставлен в:
   rapt/project/app/src/main/AndroidManifest.xml

   Что изменилось:
   - MAIN / LAUNCHER перенесён на com.artemdev.sideloadlab.LauncherActivity
   - org.renpy.android.PythonSDLActivity оставлен в манифесте без MAIN и без LAUNCHER
   - на application добавлен android:requestLegacyExternalStorage="true"
     (чтобы на Android 10 можно было создать общую папку Documents)
   - WRITE_EXTERNAL_STORAGE уже был
   - добавлены READ_EXTERNAL_STORAGE и MANAGE_EXTERNAL_STORAGE

Лаунчер создаёт
/storage/emulated/0/Documents/the_question_sideload
и incoming внутри неё, плюс launcher.log.
Это тот же путь, который sideload.rpe ищет на Android.
Zip из incoming распаковывается прямо в the_question_sideload,
как sample_mod.zip на ПК распаковывается в the_question/sideload/.

3. Тёмный BIOS
   rapt-overlay/res/layout/activity_launcher.xml
   rapt-overlay/res/drawable/sideload_button.xml
   rapt-overlay/res/drawable/sideload_button_start.xml
   rapt-overlay/res/values/sideload_bios.xml
   Куда:
   rapt/project/renpyandroid/src/main/res/layout/activity_launcher.xml
   rapt/project/renpyandroid/src/main/res/drawable/
   rapt/project/renpyandroid/src/main/res/values/sideload_bios.xml
   У activity лаунчера в манифесте стоит android:theme="@style/SideloadBiosTheme".

4. FileProvider
   rapt-overlay/res/xml/file_paths.xml
   Куда: rapt/project/renpyandroid/src/main/res/xml/file_paths.xml
   Открывает только Documents/the_question_sideload/backups
   и getFilesDir() для update.apk. Вся Documents не входит.
   Authority: ${applicationId}.fileprovider
   Класс: android.support.v4.content.FileProvider из appcompat-v7.

5. Офлайн WebView
   Исходник оболочки: rapt-overlay/assets/www/index.html, styles.css, app.js
   Скрипт: tools/sync_rapt_overlay.py
   Он копирует те же байты в четыре места, если дерево rapt есть:

   - rapt/project/app/src/main/assets/www/
     без префикса x-. Это android_asset/www. Если папки в APK нет,
     WebView с file:///android_asset/www/index.html даёт код -1.
   - rapt/project/renpyandroid/src/main/res/raw/
     bios_index.html, bios_styles.css, bios_app.js
     Это R.raw модуля org.renpy.android, не app. Иначе лаунчер не соберётся.
   - rapt-overlay/res/raw/ — та же копия, она в git.
   - the_question/game/bios_www/ — Ren'Py кладёт это в APK как файлы игры
     (x-game/x-bios_www/...). Java эти x-assets не распаковывает.

   При старте лаунчер создаёт getFilesDir()/bios_www/ и пишет туда три файла.
   Откуда байты, по очереди, в launcher.log поле source=:
   a) android_asset/www, если открывается
   b) Documents/the_question_sideload/bios_www, если положили руками
   c) R.raw (bios_index, bios_styles, bios_app)
   WebView открывает file:// + getFilesDir()/bios_www/index.html.
   Если index.html на диске нет, loadUrl не вызывается, сразу экран ошибки.
   В логе полный путь и exists=true/false.

   Без копии в assets/www и без res/raw установленный APK не содержит
   оболочку, и лаунчер остаётся на старом xml.
   Java из rapt-overlay/java/.../LauncherActivity.java копируется в
   rapt/project/renpyandroid/src/main/java/com/artemdev/sideloadlab/LauncherActivity.java
   Тем же скриптом, если каталог java уже есть.

Если RAPT собирает проект с update_always, шаблон
rapt/templates/app-AndroidManifest.xml снова затирает манифест.
Тогда фрагмент нужно вставить ещё раз. Сам Java-класс шаблон не затирает.
