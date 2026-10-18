<script setup lang="ts">
import {Layers, Minus, Plus, Scan, Map as MapIcon} from 'lucide-vue-next';
import {basemap, fitAll, setBasemap, zoomBy} from '@/map/engine';
import UiIconButton from '@/components/ui/UiIconButton.vue';

defineProps<{layersOpen: boolean}>();
defineEmits<{toggleLayers: []}>();
</script>

<template>
  <div class="flex flex-col gap-2">
    <div class="panel flex flex-col gap-0.5 rounded-[18px] p-1">
      <UiIconButton :icon="Layers" label="Слои карты" side="left" :active="layersOpen" @click="$emit('toggleLayers')" />
      <UiIconButton :icon="Scan" label="Показать всё" side="left" @click="fitAll()" />
      <UiIconButton :icon="MapIcon" :label="basemap ? 'Скрыть подложку OpenStreetMap' : 'Подложка OpenStreetMap (нужен интернет)'" side="left" :active="basemap" @click="setBasemap(!basemap)" />
    </div>
    <div class="panel flex flex-col gap-0.5 rounded-[18px] p-1">
      <UiIconButton :icon="Plus" label="Приблизить" side="left" @click="zoomBy(1)" />
      <UiIconButton :icon="Minus" label="Отдалить" side="left" @click="zoomBy(-1)" />
    </div>
  </div>
</template>
