# The Question — changelog

## Sideload

Внешняя папка `sideload/` (рядом с `game/`). `game/sideload.rpe` добавляет её в `config.searchpath` до сканирования скриптов, поэтому новые `.rpy` и картинки подхватываются после перезапуска. В меню кнопка **Sideload** / **Сайдлоад** показывает путь и список файлов.

На Android, если каталог уже есть, читается `Documents/the_question_sideload`, иначе `<ANDROID_PUBLIC>/sideload`. Свой путь: переменная `THE_QUESTION_SIDELOAD`.

Исходник хука: `sideload_src/autorun.py`. Сборка: `python sideload_src/build_sideload_rpe.py`.

Экран Sideload: путь пишется в переменную экрана, потому что в Ren'Py 7.4 внутри `text` нельзя вызывать функцию.

Учебный мод: `tools/make_sample_mod.py` собирает `incoming/sample_mod.zip`. Zip распаковывается прямо в `sideload/`. Кнопка «Открыть мод» появляется после перезапуска, когда label `extra_hello` уже загружен.

Заглушка движка: `tools/hello_engine.py` печатает `HELLO ENGINE OK`. Кнопка «Запустить движок» на экране Sideload показывает эту строку.

Хук Android: `game/**.rpe` явно в пакете `all`. autorun всегда берёт `/storage/emulated/0/Documents/the_question_sideload` и пишет `hook.log`. Пустой путь на экране говорит смотреть `hook.log`. На Android `.rpe` из APK читается в `renpy/main.py`, потому что `os.listdir` папку игры на диске не видит.

Движок Android: ошибка пишется в `last_engine_line`. `hello_engine.py` есть и в `game/`. Запуск тем же интерпретатором, что у Ren'Py.

Кнопка движка: `last_engine_line` показывается с `substitute False`, чтобы `[Errno 2]` не был именем. На Android `sys.executable` не вызывается: без нативного бинаря текст «на Android нужен нативный бинарь, не python subprocess».

Нативный процесс: на Android сначала `files/hello_engine` в private dir, иначе `/system/bin/sh -c echo HELLO ENGINE OK`. Исходник `tools/hello_engine.c`, сборка NDK в `README_SIDELOAD.md`. Java копирует ABI в `getFilesDir()/hello_engine` и ставит `chmod 755`, не в Documents.
