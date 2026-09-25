Зачем эта папка: внешний сайдлоад новеллы «The Question» / «Вопрос».

Сюда кладут новые файлы .rpy и картинки (png, jpg, webp, gif).
Игра добавляет эту папку в config.searchpath до чтения сценария,
поэтому скрипт и картинка подхватываются после перезапуска.

Папка лежит рядом с game/, не внутри неё.
На Android, если уже есть Documents/the_question_sideload,
игра читает её. Иначе на Android используется <ANDROID_PUBLIC>/sideload.
Свой путь можно задать переменной THE_QUESTION_SIDELOAD.

Имена файлов не должны совпадать с файлами самой новеллы:
при одинаковом относительном пути побеждает файл из game/.

Подключение делает game/sideload.rpe.
Исходник хука: sideload_src/autorun.py
Собрать rpe заново: python sideload_src/build_sideload_rpe.py
