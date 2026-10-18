// Address → data → map. What belongs to one place does not follow to the next: the picked object, the highlight
// and the replay are closed when the place changes; the variant is on the map only on the result and download.

import {watch} from 'vue';
import {useRoute, useRouter} from 'vue-router';
import {useApp} from '@/stores/app';
import * as engine from '@/map/engine';
import * as player from '@/map/player';
import {anim} from '@/map/anim';
import {syncFlow} from '@/map/flow';
import {toast} from '@/lib/toast';
import {STEPS} from '@/lib/steps';

export function installSync() {
  const app = useApp();
  const route = useRoute();
  const router = useRouter();
  let currentDataset: string | null = null;
  let currentView: string | null = null;

  async function onRoute() {
    const ds = app.datasetId;
    if (ds !== currentDataset) {
      currentDataset = ds;
      app.resetDataset();
      if (player.isOpen()) player.close(false);
      engine.clearVariant();
      engine.clearPicked();
      engine.clearHighlight();
      engine.showDataset(null);
      if (ds) {
        app.loadOverview(ds).catch((e) => toast(e.message, true));
        app.loadRuns(ds).catch(() => {});
      }
    }
    if (app.view === 'latest' && ds) {
      const runs = app.runsFor === ds ? app.runs : await app.loadRuns(ds);
      const done = (runs || []).find((x: any) => x.status === 'DONE');
      if (done) router.replace({name: 'result', params: {ds, run: done.id, v: '1'}});
      else router.replace({name: 'calc', params: {ds}});
      return;
    }
    if (app.runId && (!app.run || app.run.id !== app.runId)) app.loadRun(app.runId).catch((e) => toast(e.message, true));
    if (currentView !== app.view) {
      const was = currentView;
      currentView = app.view;
      engine.clearPicked();
      engine.clearHighlight();
      // the live construction follows the user between the calculation and its result
      const keep = (was === 'calc' && app.view === 'result') || (was === 'result' && app.view === 'calc');
      if (player.isOpen() && !keep) player.close(false);
    }
    syncVariant();
    const step = STEPS.find((s) => s.id === app.step);
    const tab = {compare: ' · Сравнение', export: ' · Выгрузка'}[app.view as 'compare' | 'export'] || '';
    document.title = `${step ? step.long + tab + ' — ' : ''}Трассы подключения к тепловой сети`;
  }

  async function syncVariant() {
    const want = (app.view === 'result' || app.view === 'export') && app.currentRun && app.currentRun.status === 'DONE';
    if (!want) {
      if (app.view !== 'result' && app.view !== 'export') {
        engine.clearVariant();
        app.variantKey = null;
        app.variantData = null;
      }
      return;
    }
    const v = app.variantId;
    const key = app.runId + '|' + v;
    if (app.variantKey === key) return;
    app.variantKey = key;
    try {
      const data = await app.loadVariant(app.runId!, v);
      if (app.variantKey !== key) return;
      const had = engine.hasVariant();
      await engine.showVariant(key, data, {transitionMs: had && anim.transitions ? anim.transitionMs : 0});
      app.variantData = data;
      syncFlow();
      if (!had) {
        const b = engine.bboxOf(data.features.filter((f: any) => f.properties.object_type !== 'variant_summary'));
        if (b) engine.fit(b);
      }
    } catch (e: any) {
      toast('Не удалось загрузить вариант: ' + e.message, true);
    }
  }

  watch(() => route.fullPath, () => onRoute().catch((e) => console.error(e)), {immediate: true});
  watch(() => app.currentRun && app.currentRun.status, () => syncVariant());
  // the layers of a dataset are shown once it is ready (tiles of a dataset being imported do not exist yet)
  watch(() => app.currentOverview && app.currentOverview.status + '|' + (app.currentOverview.finished_at || ''), () => {
    const o = app.currentOverview;
    if (o && o.status === 'READY') engine.showDataset(o.id, {fitTo: !engine.hasVariant(), stamp: o.finished_at || ''});
  });
  watch(() => anim.flow, () => syncFlow());
}
