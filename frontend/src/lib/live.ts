// A calculation followed while it runs: its journal is replayed on the map as it grows; when it ends, a notice
// with a way to its result. The following does not stop when the user goes to another place in the workflow.

import {ref} from 'vue';
import {api} from '@/api/client';
import * as player from '@/map/player';
import {anim} from '@/map/anim';
import {toast} from './toast';
import {router} from '@/router';

export const live = ref<{run: string; dataset: string; name: string; done?: string; closed?: boolean} | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined;
let next = 0;
let opened = false;

export function watchRun(run: string, dataset: string, name: string, onEnd?: () => void) {
  stopWatching();
  live.value = {run, dataset, name};
  next = 0;
  opened = false;
  const tick = async () => {
    const l = live.value;
    if (!l || l.run !== run) return;
    try {
      const j: any = await api.journal(run, '1', next);
      if (!live.value || live.value.run !== run) return;
      if (j.live) {
        if (anim.live && !l.closed) {
          if (!opened && j.events.length) {
            player.openBuild(j.events, {title: 'Построение во время расчёта', subtitle: name, live: true, onClose: () => { if (live.value) live.value.closed = true; }});
            opened = true;
          } else if (opened) {
            player.appendLive(j.events, false);
          }
        }
        next = j.next;
        timer = setTimeout(tick, 1000);
      } else if (j.status === 'QUEUED' || j.status === 'RUNNING') {
        timer = setTimeout(tick, 1000);
      } else {
        if (opened && !l.closed) player.appendLive([], true);
        live.value = {...l, done: j.status};
        const open = () => router.push({name: 'result', params: {ds: dataset, run, v: '1'}});
        if (j.status === 'DONE') toast(`Расчёт «${name}» готов`, {tone: 'ok', action: {label: 'Открыть', run: open}});
        else toast(j.status === 'CANCELLED' ? 'Расчёт отменён' : 'Расчёт завершился с ошибкой', j.status !== 'CANCELLED');
        if (onEnd) onEnd();
      }
    } catch (e) {
      timer = setTimeout(tick, 2000);
    }
  };
  tick();
}

export function stopWatching() {
  clearTimeout(timer);
  live.value = null;
}
