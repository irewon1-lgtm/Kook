import type {Interval} from './model';

export interface ChartPreferences {interval:Interval;months:1|3|6|12;candleMode:boolean}
export type ChartPreferenceStorage=Pick<Storage,'getItem'|'setItem'>;
export const DEFAULT_CHART_PREFERENCES:Readonly<ChartPreferences>=Object.freeze({interval:'DAY',months:6,candleMode:true});
const validIssuer=(issuerId:string|undefined):issuerId is string=>typeof issuerId==='string'&&/^[0-9A-Z]{6}$/.test(issuerId);
const storageKey=(issuerId:string)=>`kr4-pc-chart:v1:${issuerId}`;
function validPreferences(value:unknown):value is ChartPreferences {
  if (!value || typeof value!=='object' || Array.isArray(value)) return false;
  const preferences=value as Record<string,unknown>;
  return typeof preferences.interval==='string' && ['DAY','WEEK','MONTH'].includes(preferences.interval) &&
    typeof preferences.months==='number' && [1,3,6,12].includes(preferences.months) && typeof preferences.candleMode==='boolean';
}
/** Browser-local view choices only; never tied to a price payload or source revision. */
export function readChartPreferences(issuerId?:string,storage?:ChartPreferenceStorage):ChartPreferences {
  if (validIssuer(issuerId)) {
    try {
      const value:unknown=JSON.parse((storage??localStorage).getItem(storageKey(issuerId))??'null');
      if (validPreferences(value) && (value as ChartPreferences&{schemaVersion?:unknown}).schemaVersion===1) {
        return {interval:value.interval,months:value.months,candleMode:value.candleMode};
      }
    } catch { /* Missing, malformed or denied storage keeps original chart defaults. */ }
  }
  return {...DEFAULT_CHART_PREFERENCES};
}
export function saveChartPreferences(issuerId:string|undefined,preferences:ChartPreferences,storage?:ChartPreferenceStorage):void {
  if (!validIssuer(issuerId) || !validPreferences(preferences)) return;
  try { (storage??localStorage).setItem(storageKey(issuerId),JSON.stringify({schemaVersion:1,...preferences})); }
  catch { /* Controls remain usable when this browser cannot save preferences. */ }
}
