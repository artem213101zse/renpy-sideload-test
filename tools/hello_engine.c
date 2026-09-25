/* Зачем этот файл: нативная заглушка движка для Android.
   Печатает HELLO ENGINE OK и выходит с кодом 0.
   Сборка и путь установки — в README_SIDELOAD.md.
   Кладётся только в app files dir, не в Documents. */

#include <stdio.h>

int main(void) {
    puts("HELLO ENGINE OK");
    return 0;
}
