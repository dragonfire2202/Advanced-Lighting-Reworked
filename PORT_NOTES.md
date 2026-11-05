# Заметки о порте на Mindustry v8 (build 160.x)

Порт сделан по исходникам версии для v146 и Javadoc актуальной игры. **Проект не компилировался и не запускался**
(в среде, где он делался, не было ни компилятора с библиотеками игры, ни самой игры). Проверен только синтаксис Java.

## Что изменено

**Код**
- `AdvanceLighting.java`: убран вызов `FloorRenderer.endDraw()`. В v8 этого метода нет, это была гарантированная ошибка компиляции.
- `AdvanceLighting.java`: рефлексия по `BlockRenderer.tileview` и `LightRenderer.lights/circleIndex` теперь проверяет тип поля.
  Если поле исчезло или изменилось, мод переходит на обычный `renderer.blocks.drawBlocks()` и пишет в лог `[Advance Lighting] block drawing path: ...`.
- `AdvanceLighting.java` и `AltLightBatch.abort()`: проход освещения обёрнут в защиту. При любом исключении мод отключает себя до
  перезапуска игры, возвращает состояние рендера и пишет ошибку в лог, а игра не падает.

**Сборка**
- `gradle.properties`: `mindustryVersion = v160.5`. Arc и EntityAnno больше не нужны.
- `build.gradle`: Mindustry и Arc берутся из `Mindustry.jar` релиза на GitHub, без JitPack. Целевая версия байткода Java 17, без
  Java 8 downgrader (как в актуальном официальном шаблоне модов). Скачивание спрайтов переписано без классов Arc.
- Новый флаг `gradlew jar -PskipGen` собирает мод без генератора спрайтов ванильных юнитов.
- `mod.json`: `minGameVersion = 160`, версия `v1.6.0`.
- Android: SDK 37.0 / build-tools 37.0.0 / min API 21 (как в шаблоне); CI обновлён на `actions/*@v4`.

## Что НЕ проверено и может потребовать правок
1. **Новый рендер блоков в v8** (кэширование блоков и зданий: `BuildingCacheLayer`, `drawCached`, `cacheChunk`). Исходник нового `BlockRenderer`
   мне получить не удалось. Если `tileview` в нём нет, свечение закэшированных блоков может отсутствовать или отображаться неверно.
2. **Генератор спрайтов** (`proc/`) запускает загрузку контента игры в headless-режиме; в v8 она могла измениться. Если `genSprites` падает, используйте `-PskipGen`.
3. Классы, которых я не мог сверить с актуальным API: `RegionPart`/`DrawPart`-поля, `StaticWall.large`, `PowerNode`/`RepairBeamWeapon` (поля лазеров),
   настройки в `loadSettings()` (`SettingsMenuDialog`). Совместимость подтверждена для `Batch`/`SpriteBatch`, `Shaders`, `Layer`, `FogRenderer`, `UnitType`, `DrawBlock`.
4. Визуальный результат в игре.

## Если что-то не работает
Пришлите полный вывод `gradlew jar` (при ошибках компиляции) или `last_log.txt` из папки данных игры (при проблемах в игре; ищите строки `[Advance Lighting]`).

## Исправления по build_log.txt (первая реальная сборка)
- `AltLightBatch`: в новом Arc у `Batch` нет поля `Color color` и методов `setColor(...)`. Цвет теперь берётся из `colorPacked`
  через `curColor()`, переопределения `setColor` удалены (остался `setPackedColor`).
- `AltLightBatch`: методы `expandRequests/sortRequests/sortRequestsThreaded` совпали с protected-методами нового `SpriteBatch`;
  переименованы в `expandLightRequests/sortLightRequests/sortLightRequestsThreaded`.
- `AltLightBatch`: у `IntIntMap` нет `getOrPut`, добавлен статический помощник `getOrPut(map, key, def)`.
- `AdvanceLighting`: `Groups.puddle` удалён из игры; лужи ищутся через рефлексию, запасной путь: `Groups.draw` + `instanceof Puddle`.
  В логе будет строка `[Advance Lighting] puddle source: ...`.
