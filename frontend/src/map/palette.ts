// Colours of the data on the map. They belong to the data, not to the theme of the panels: only the base (background,
// buildings, the existing network) follows the light or dark theme so the data stays readable on both.

export const DN_STOPS: (number | string)[] = [50, '#fde68a', 100, '#fbbf24', 200, '#f97316', 400, '#dc2626', 700, '#9f1239', 1200, '#4c0519'];
export const DN_STOPS_DARK: (number | string)[] = [50, '#fef3c7', 100, '#fcd34d', 200, '#fb923c', 400, '#f43f5e', 700, '#e11d48', 1200, '#be123c'];

export const RESTRICTION_COLORS: Record<string, string> = {
  road: '#b8bcc2', tram_tracks: '#a78bfa', railway: '#8b6f47', water: '#7dd3fc', park: '#86efac',
  social_area: '#f9a8d4', prohibited_site: '#fca5a5', gas_pipeline: '#eab308', power_cable: '#7c3aed',
  heat_network: '#64748b', oks: '#d6d0c4', oks_existing: '#d6d0c4', building: '#d6d0c4',
};

export interface BasePalette {
  bg: string;
  building: string;
  buildingLine: string;
  network: string;
  chamberFill: string;
  casing: string;
  restrictionOpacity: number;
  osmOpacity: number;
  halo: string;
  dn: (number | string)[];
}

export const BASE: Record<'light' | 'dark', BasePalette> = {
  light: {
    bg: '#eef0f3', building: '#d3d6dc', buildingLine: '#b9bec7', network: '#334155', chamberFill: '#ffffff',
    casing: '#1d2327', restrictionOpacity: 0.45, osmOpacity: 0.55, halo: '#111827', dn: DN_STOPS,
  },
  dark: {
    bg: '#0f1318', building: '#272d37', buildingLine: '#343c48', network: '#9aa7ba', chamberFill: '#0f1318',
    casing: '#05070a', restrictionOpacity: 0.3, osmOpacity: 0.35, halo: '#ffffff', dn: DN_STOPS_DARK,
  },
};
