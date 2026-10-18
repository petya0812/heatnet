<script setup lang="ts">
import {computed, onMounted, ref, watch, watchEffect} from 'vue';
import {RouterView} from 'vue-router';
import {TooltipProvider} from 'reka-ui';
import {ChevronDown, ChevronUp} from 'lucide-vue-next';
import {useBreakpoints, useElementSize} from '@vueuse/core';
import {useApp} from '@/stores/app';
import {installSync} from '@/sync';
import {installTheme} from '@/lib/theme';
import {toast} from '@/lib/toast';
import * as engine from '@/map/engine';
import * as player from '@/map/player';
import TopBar from '@/components/shell/TopBar.vue';
import MapTools from '@/components/shell/MapTools.vue';
import LayersPanel from '@/components/shell/LayersPanel.vue';
import Inspector from '@/components/shell/Inspector.vue';
import PlayerBar from '@/components/shell/PlayerBar.vue';
import HelpDialog from '@/components/shell/HelpDialog.vue';
import GlobalDialogs from '@/components/shell/GlobalDialogs.vue';
import Toaster from '@/components/shell/Toaster.vue';
import WelcomeCard from '@/components/shell/WelcomeCard.vue';
import {prefs} from '@/lib/prefs';

const app = useApp();
const mapEl = ref<HTMLElement | null>(null);
const panelEl = ref<HTMLElement | null>(null);
const playerEl = ref<HTMLElement | null>(null);
const helpOpen = ref(false);
const layersOpen = ref(prefs.get('layersOpen', false));
watch(layersOpen, (v) => prefs.set('layersOpen', v));
const sheetCollapsed = ref(false);
const bp = useBreakpoints({md: 768});
const narrow = bp.smaller('md');

// the home screen is a library: a wider panel
const wide = computed(() => app.view === 'data');

installTheme();
installSync();

onMounted(() => {
  engine.createMap(mapEl.value!);
  app.loadCatalog().then((c) => {
    const titles: Record<string, string> = {};
    for (const t of c.restriction_types) titles[t.type] = t.title;
    titles.railway = 'железная дорога';
    engine.setRestrictionTitles(Object.assign({oks: 'существующее здание'}, titles));
  }).catch((e) => toast('Не удалось загрузить параметры расчёта: ' + e.message, true));
  app.loadDatasets().catch(() => {});
  app.loadStatus();
  window.addEventListener('resize', () => engine.map.resize());
});

// fitting keeps the data in the part of the map that panels do not cover
const {width: panelW, height: panelH} = useElementSize(panelEl);
const {height: playerH} = useElementSize(playerEl);
watchEffect(() => {
  engine.insets.left = narrow.value ? 0 : panelW.value + 12;
  engine.insets.right = narrow.value ? 0 : (layersOpen.value ? 300 + 70 : 64) + (engine.picked.value ? 0 : 0);
  engine.insets.bottom = narrow.value ? panelH.value + 16 : player.player.open ? playerH.value + 16 : 0;
});

// hooks for end-to-end checks: read-only state and navigation; not used by the page itself
(window as any).heatnet = {
  get map() { return engine.map; },
  engine,
  store: app,
  player: player.snapshot,
  snapshot: () => ({route: {view: app.view, dataset: app.datasetId, run: app.runId, variant: app.variantId, oks: app.oksId}, step: app.step,
    dataset: app.currentOverview?.id || null, run: app.currentRun?.id || null, variantShown: app.variantKey, player: player.snapshot(), title: document.title}),
};
</script>

<template>
  <TooltipProvider :delay-duration="250" :skip-delay-duration="150">
    <div class="fixed inset-0 overflow-hidden bg-canvas">
      <a href="#panel" class="sr-only z-[300] rounded-xl bg-accent px-4 py-2 text-on-accent focus:not-sr-only focus:absolute focus:left-3 focus:top-3">К содержимому</a>
      <div class="absolute inset-0"><div ref="mapEl" class="h-full w-full" role="region" aria-label="Карта" /></div>

      <TopBar @help="helpOpen = true" />

      <main id="panel" ref="panelEl" tabindex="-1"
        class="panel absolute z-20 flex flex-col overflow-hidden transition-[width] duration-200 focus:outline-none md:bottom-3 md:left-3 md:top-[72px]"
        :class="[narrow ? 'inset-x-2 bottom-2 rounded-b-[22px]' : wide ? 'w-[520px]' : 'w-[420px]',
          narrow ? (sheetCollapsed ? 'h-[132px]' : 'h-[62dvh]') : '']">
        <button v-if="narrow" type="button" class="flex h-6 shrink-0 items-center justify-center text-ink-3" :aria-label="sheetCollapsed ? 'Развернуть панель' : 'Свернуть панель'"
          @click="sheetCollapsed = !sheetCollapsed">
          <component :is="sheetCollapsed ? ChevronUp : ChevronDown" class="size-5" aria-hidden="true" />
        </button>
        <RouterView v-slot="{Component, route}">
          <Transition name="view" mode="out-in">
            <component :is="Component" :key="(route.meta.view as string) + '|' + (route.params.ds || '')" class="min-h-0 flex-1" />
          </Transition>
        </RouterView>
      </main>

      <div class="pointer-events-none absolute bottom-3 right-3 top-[72px] z-20 flex items-start gap-2 max-md:hidden">
        <div class="pointer-events-none flex h-full flex-col items-end gap-2">
          <LayersPanel v-if="layersOpen" class="pointer-events-auto" @close="layersOpen = false" />
          <div class="flex-1" />
          <Inspector class="pointer-events-auto" />
        </div>
        <MapTools class="pointer-events-auto" :layers-open="layersOpen" @toggle-layers="layersOpen = !layersOpen" />
      </div>

      <div class="pointer-events-none absolute bottom-3 z-20 flex justify-center max-md:hidden"
        :style="{left: (panelW + 24) + 'px', right: (layersOpen || engine.picked.value ? 400 : 76) + 'px'}">
        <div ref="playerEl" class="pointer-events-auto w-full max-w-[640px]"><PlayerBar /></div>
      </div>

      <Transition enter-from-class="opacity-0 -translate-y-1" leave-to-class="opacity-0" enter-active-class="transition" leave-active-class="transition">
        <div v-if="engine.hintText.value" class="pointer-events-none absolute left-1/2 top-[76px] z-30 -translate-x-1/2 rounded-full bg-inverse px-4 py-2 text-[13px] text-on-inverse shadow-pop max-md:top-[64px] max-md:w-[calc(100vw-24px)] max-md:rounded-2xl" role="status">
          {{ engine.hintText.value }}
        </div>
      </Transition>

      <Transition enter-from-class="opacity-0 translate-y-2" leave-to-class="opacity-0" enter-active-class="transition duration-300" leave-active-class="transition duration-150">
        <div v-if="app.view === 'data' && !narrow" class="pointer-events-none absolute inset-y-0 right-[76px] z-10 flex items-center justify-center" :style="{left: panelW + 24 + 'px'}">
          <WelcomeCard class="pointer-events-auto" @help="helpOpen = true" />
        </div>
      </Transition>

      <HelpDialog v-model:open="helpOpen" />
      <GlobalDialogs />
      <Toaster />
    </div>
  </TooltipProvider>
</template>
