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

Если RAPT собирает проект с update_always, шаблон
rapt/templates/app-AndroidManifest.xml снова затирает манифест.
Тогда фрагмент нужно вставить ещё раз. Сам Java-класс шаблон не затирает.
