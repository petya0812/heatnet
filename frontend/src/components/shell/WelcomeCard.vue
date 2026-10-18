<script setup lang="ts">
import {Calculator, Database, Download, ShieldCheck} from 'lucide-vue-next';
import BrandMark from './BrandMark.vue';

defineEmits<{help: []}>();
const STEPS = [
  {icon: Database, title: 'Данные', text: 'Загрузите GeoJSON или выберите набор слева'},
  {icon: ShieldCheck, title: 'Проверка', text: 'Ошибки, замечания и что сервис восполнил сам — с показом на карте'},
  {icon: Calculator, title: 'Расчёт', text: 'Настройки трассировки, запуск и ход построения на карте'},
  {icon: Download, title: 'Результат', text: 'Два лучших варианта, разбор каждого ОКС, сравнение и выгрузка'},
];
</script>

<template>
  <section class="panel w-[460px] p-6" aria-labelledby="welcome-h">
    <div class="flex items-center gap-3">
      <BrandMark />
      <div>
        <h2 id="welcome-h" class="text-[18px] font-semibold tracking-[-0.01em]">Трассы подключения к тепловой сети</h2>
        <p class="text-[13px] text-ink-3">Варианты подключения перспективных ОКС к существующей сети</p>
      </div>
    </div>
    <ol class="mt-5 space-y-3">
      <li v-for="(s, i) in STEPS" :key="s.title" class="flex items-start gap-3">
        <span class="flex size-9 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-ink-2"><component :is="s.icon" class="size-[18px]" aria-hidden="true" /></span>
        <div class="min-w-0">
          <div class="text-[13.5px] font-semibold"><span class="text-ink-3">{{ i + 1 }}.</span> {{ s.title }}</div>
          <div class="text-[13px] text-ink-3">{{ s.text }}</div>
        </div>
      </li>
    </ol>
    <button type="button" class="mt-5 text-[13px] font-medium text-accent-ink hover:underline" @click="$emit('help')">Подробнее о методе и терминах</button>
  </section>
</template>
