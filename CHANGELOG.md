# The Question — changelog

## Sideload

Внешняя папка `sideload/` (рядом с `game/`). `game/sideload.rpe` добавляет её в `config.searchpath` до сканирования скриптов, поэтому новые `.rpy` и картинки подхватываются после перезапуска. В меню кнопка **Sideload** / **Сайдлоад** показывает путь и список файлов.

На Android, если каталог уже есть, читается `Documents/the_question_sideload`, иначе `<ANDROID_PUBLIC>/sideload`. Свой путь: переменная `THE_QUESTION_SIDELOAD`.

Исходник хука: `sideload_src/autorun.py`. Сборка: `python sideload_src/build_sideload_rpe.py`.

Экран Sideload: путь пишется в переменную экрана, потому что в Ren'Py 7.4 внутри `text` нельзя вызывать функцию.

Учебный мод: `tools/make_sample_mod.py` собирает `incoming/sample_mod.zip`. Zip распаковывается прямо в `sideload/`. Кнопка «Открыть мод» появляется после перезапуска, когда label `extra_hello` уже загружен.
