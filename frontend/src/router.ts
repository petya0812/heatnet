// The address is the place in the workflow: reload or a link sent to a colleague opens the same place.
// The addresses are those of the previous interface, so old links keep working.
//   #/                                                     step 1 — data
//   #/datasets/{ds}                                        step 2 — check
//   #/datasets/{ds}/calculate[?param=key]                  step 3 — calculation (?tab=history)
//   #/datasets/{ds}/runs/{run}/variants/{v}[/oks/{oks}]    step 4 — result (an OKS card)
//   #/datasets/{ds}/compare/{runA}:{vA}/{runB}:{vB}        step 4, tab «Сравнение»
//   #/datasets/{ds}/runs/{run}/variants/{v}/export         step 4, tab «Выгрузка»

import {createRouter, createWebHashHistory} from 'vue-router';
import DataView from '@/views/DataView.vue';
import CheckView from '@/views/CheckView.vue';
import CalcView from '@/views/CalcView.vue';
import ResultView from '@/views/ResultView.vue';
import CompareView from '@/views/CompareView.vue';
import ExportView from '@/views/ExportView.vue';
import LatestResult from '@/views/LatestResult.vue';


export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {path: '/', name: 'data', component: DataView, meta: {step: 'data', view: 'data'}},
    {path: '/datasets/:ds', name: 'check', component: CheckView, meta: {step: 'check', view: 'check'}},
    {path: '/datasets/:ds/calculate', name: 'calc', component: CalcView, meta: {step: 'calc', view: 'calc'}},
    {path: '/datasets/:ds/result', name: 'latest', component: LatestResult, meta: {step: 'result', view: 'latest'}},
    {path: '/datasets/:ds/runs/:run/variants/:v', name: 'result', component: ResultView, meta: {step: 'result', view: 'result'}},
    {path: '/datasets/:ds/runs/:run/variants/:v/oks/:oks', name: 'oks', component: ResultView, meta: {step: 'result', view: 'result'}},
    {path: '/datasets/:ds/runs/:run/variants/:v/export', name: 'export', component: ExportView, meta: {step: 'result', view: 'export'}},
    {path: '/datasets/:ds/compare/:a?/:b?', name: 'compare', component: CompareView, meta: {step: 'result', view: 'compare'}},
    {path: '/datasets/:ds/runs/:run', redirect: (to) => ({name: 'result', params: {ds: to.params.ds, run: to.params.run, v: '1'}})},
    {path: '/:rest(.*)*', redirect: '/'},
  ],
});
