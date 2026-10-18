// Derived layers of a variant: flow changes of the existing network, runs of one DN and their usage of the limit,
// special crossings.

import {natural} from './format';

export function derive(data: any) {
  const feats = data ? data.features : [];
  const flows = feats.filter((f: any) => f.properties.object_type === 'flow_change');
  const runs = feats.filter((f: any) => f.properties.object_type === 'length_run');
  const usageOf: Record<string, any> = {};
  for (const f of runs) {
    let segs = f.properties.segments;
    if (typeof segs === 'string') { try { segs = JSON.parse(segs); } catch (e) { segs = segs.split(','); } }
    for (const id of segs || []) usageOf[id] = f.properties;
  }
  // one existing segment may come in several pieces: one row per segment
  const bySeg = new Map<string, any>();
  for (const f of flows.filter((x: any) => x.properties.reconstructed)) {
    const p = f.properties;
    const was = bySeg.get(p.existing_object_id);
    if (!was || p.calculated_flow_tph > was.properties.calculated_flow_tph) bySeg.set(p.existing_object_id, f);
  }
  const specials = feats.filter((f: any) => f.properties.object_type === 'heat_network' && f.properties.laying_method === 'special');
  const recon = [...bySeg.values()].sort((a, b) => natural(a.properties.existing_object_id, b.properties.existing_object_id));
  return {flows, recon, runs, usageOf, specials};
}

export const COSTS = [
  {key: 'segment_cost', title: 'Новые участки', color: '#f97316'},
  {key: 'chamber_construction_cost', title: 'Новые камеры', color: '#2563eb'},
  {key: 'existing_chamber_tie_in_cost', title: 'Врезки в камеры', color: '#8b5cf6'},
  {key: 'unconnected_penalty', title: 'Штраф за неподключённые', color: '#e11d48'},
];
