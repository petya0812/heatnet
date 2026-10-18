<script setup lang="ts">
import {ref} from 'vue';
import {TabsContent, TabsList, TabsRoot, TabsTrigger} from 'reka-ui';
import {Code, ExternalLink, FileText, History} from 'lucide-vue-next';
import {TERMS} from '@/lib/terms';
import UiDialog from '@/components/ui/UiDialog.vue';
import UiButton from '@/components/ui/UiButton.vue';

defineProps<{open: boolean}>();
defineEmits<{'update:open': [boolean]}>();
const tab = ref('flow');
const TABS = [['flow', 'Порядок работы'], ['method', 'Метод расчёта'], ['terms', 'Термины'], ['api', 'API']];
const FLOW = [
  ['Данные', 'Загрузите GeoJSON (до 3 ГБ) или откройте набор из библиотеки. Рабочие наборы стоят первыми, примеры — ниже.'],
  ['Проверка', 'Что распознано, какие значения сервис восполнил, есть ли ошибки. Каждое замечание можно показать на карте. Ограничение, которого нет в данных, рисуется на карте и сохраняется новой версией набора — исходный файл не меняется.'],
  ['Расчёт', 'Настройки трассировки, недостающие данные и способы применения правил — всё, что можно изменить, собрано сверху; неизменяемые технические требования — в конце, свёрнуты. Построение видно на карте по ходу расчёта; все расчёты остаются в истории.'],
  ['Результат', 'Два лучших варианта с оценкой S, стоимость и её структура, проверка правил, каждый ОКС с местом врезки, нагрузка на существующую сеть. Отсюда — сравнение двух вариантов (в том числе разных расчётов и версий) и выгрузка файлов.'],
];
</script>

<template>
  <UiDialog :open="open" title="Справка" size="lg" @update:open="$emit('update:open', $event)">
    <TabsRoot v-model="tab">
      <TabsList class="mb-4 inline-flex flex-wrap gap-0.5 rounded-xl bg-seg-track p-[3px]" aria-label="Разделы справки">
        <TabsTrigger v-for="[id, title] in TABS" :key="id" :value="id"
          class="h-8 rounded-[9px] px-3 text-[13px] font-medium text-ink-2 hover:text-ink data-[state=active]:bg-seg-on data-[state=active]:text-ink data-[state=active]:shadow-soft">{{ title }}</TabsTrigger>
      </TabsList>
      <TabsContent value="flow">
        <ol class="space-y-3">
          <li v-for="([t, d], i) in FLOW" :key="t" class="flex gap-3">
            <span class="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent-soft text-[13px] font-semibold text-accent-ink">{{ i + 1 }}</span>
            <div><div class="font-semibold">{{ t }}</div><p class="text-[13.5px] text-ink-2">{{ d }}</p></div>
          </li>
        </ol>
        <div class="mt-5 rounded-2xl bg-surface-2 p-4 text-[13px] text-ink-2">
          <div class="mb-1 font-semibold text-ink">Клавиши</div>
          Показ построения: <span class="kbd">Пробел</span> пуск и пауза, <span class="kbd">←</span> <span class="kbd">→</span> шаг.
          Рисование ограничения: <span class="kbd">Enter</span> готово, <span class="kbd">Backspace</span> убрать вершину, <span class="kbd">Esc</span> отмена.
          Объект на карте: щелчок — карточка справа, <span class="kbd">Esc</span> — закрыть. Справка — <span class="kbd">?</span>.
        </div>
      </TabsContent>
      <TabsContent value="method" class="space-y-2.5 text-[13.5px] text-ink-2">
        <p><b class="text-ink">Трасса.</b> Ломаная по графу видимости: точки поворота — углы зон отступа вокруг зданий и ограничений. Поиск выбирает путь с наименьшим вкладом в оценку S; лишние повороты отсекает настройка «Штраф за поворот» — она влияет только на выбор трассы.</p>
        <p><b class="text-ink">Ограничения.</b> Дороги и трамвайные пути пересекаются спецпроходом под углом не меньше 45°; газопровод и кабель — спецпроходом; парки, вода, железная дорога, запретные территории обходятся с отступом.</p>
        <p><b class="text-ink">Врезка.</b> Каждое место присоединения оценивается целиком: трасса плюс новая камера на участке или врезка в существующую камеру.</p>
        <p><b class="text-ink">Совместное подключение.</b> ОКС присоединяются по одному в нескольких порядках, затем каждый по очереди отключается и подключается заново, пока оценка улучшается. Раздельное подключение считается всегда.</p>
        <p><b class="text-ink">Расходы и ДУ.</b> На общем участке расходы складываются; ДУ — минимальный, который проходит и по расходу, и по предельной длине.</p>
        <p><b class="text-ink">Проверка.</b> Результат перечитывается из файла выгрузки и проверяется по обязательным правилам независимо от расчёта.</p>
      </TabsContent>
      <TabsContent value="terms">
        <dl class="grid gap-x-4 gap-y-2.5 text-[13.5px] sm:grid-cols-[170px_1fr]">
          <template v-for="(v, k) in TERMS" :key="k">
            <dt class="font-semibold text-ink">{{ k }}</dt>
            <dd class="text-ink-2">{{ v }}</dd>
          </template>
        </dl>
      </TabsContent>
      <TabsContent value="api" class="space-y-3 text-[13.5px] text-ink-2">
        <p>Всё, что делает интерфейс, доступно через HTTP API. Сценарий из пяти запросов: загрузить набор → дождаться READY → запустить расчёт → дождаться DONE → забрать result.geojson.</p>
        <div class="flex flex-wrap gap-2">
          <UiButton :icon="Code" href="swagger-ui.html" target="_blank">Swagger UI</UiButton>
          <UiButton :icon="FileText" href="v3/api-docs" target="_blank">Спецификация OpenAPI</UiButton>
          <UiButton :icon="History" :icon-right="ExternalLink" variant="ghost" href="classic/index.html" target="_blank">Прежний интерфейс</UiButton>
        </div>
      </TabsContent>
    </TabsRoot>
  </UiDialog>
</template>
