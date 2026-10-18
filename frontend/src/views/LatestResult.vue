<script setup lang="ts">
// #/datasets/{ds}/result — the latest finished result of a dataset; the redirect itself is in sync.ts.
import PanelLayout from '@/components/shell/PanelLayout.vue';
</script>

<template>
  <PanelLayout title="Результат" eyebrow="Шаг 4 · Результат">
    <div class="space-y-3"><div class="skeleton h-20" /><div class="skeleton h-40" /></div>
  </PanelLayout>
</template>
