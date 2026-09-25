# Нативный hello_engine

Зачем: на Android кнопка «Запустить движок» не вызывает Python.
Если в приватной папке приложения есть исполняемый `hello_engine`,
Ren'Py запускает его. Иначе запускается `/system/bin/sh -c "echo HELLO ENGINE OK"`.

Исходник: `tools/hello_engine.c`.

NDK в `rapt/Sdk` этого комплекта нет, готовые бинарники не собраны.
Когда появится NDK 21 (в RAPT указан `21.3.6528147`), из каталога `tools`:

```bat
set NDK=C:\path\to\ndk\21.3.6528147
set CLANG=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin
%CLANG%\aarch64-linux-android21-clang.cmd -O2 -o hello_engine-arm64 hello_engine.c
%CLANG%\x86_64-linux-android21-clang.cmd -O2 -o hello_engine-x86_64 hello_engine.c
```

Куда положить результат:

- `the_question/rapt-overlay/bin/hello_engine-arm64`
- `the_question/rapt-overlay/bin/hello_engine-x86_64`

И те же два файла в assets проекта, чтобы `AssetManager` их увидел
без переименования `x-`:

- `rapt/project/app/src/main/assets/hello_engine-arm64`
- `rapt/project/app/src/main/assets/hello_engine-x86_64`

`LauncherActivity` при старте выбирает ABI (`arm64-v8a` или `x86_64`),
копирует файл в `getFilesDir()/hello_engine` и делает `chmod 755`.

Это только private dir приложения (`/data/user/0/<package>/files/hello_engine`).
В `Documents` бинарь не копируется.
