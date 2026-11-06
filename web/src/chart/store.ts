import type { PriceData } from '../types';
import { normalizeDaily } from './model';

export interface PriceState { data: PriceData | null; error: string | null; loading: boolean }
interface Entry { state: PriceState; attempted: boolean; pending?: Promise<void>; listeners: Set<() => void> }

/** One verified reception per publication price hash, shared by quote and chart. */
export function createPriceStore(loader: (issuerId: string, cutoffDate: string) => Promise<PriceData>) {
  const entries = new Map<string, Entry>();
  function entry(issuerId: string, cutoffDate: string, revision = ''): Entry {
    const key = JSON.stringify([issuerId,cutoffDate,revision]);
    let value = entries.get(key);
    if (!value) {
      value = {state:{data:null,error:null,loading:false}, attempted:false,listeners:new Set()};
      entries.set(key, value);
    }
    return value;
  }
  function update(value: Entry, state: PriceState) {
    value.state = state;
    value.listeners.forEach(listener => listener());
  }
  return {
    get: (issuerId: string, cutoffDate: string, revision = '') => entry(issuerId, cutoffDate,revision).state,
    subscribe(issuerId: string, cutoffDate: string, listener: () => void, revision = '') {
      const value = entry(issuerId,cutoffDate,revision);
      value.listeners.add(listener);
      return () => {value.listeners.delete(listener);};
    },
    load(issuerId: string, cutoffDate: string, force = false, revision = ''): Promise<void> {
      const value = entry(issuerId,cutoffDate,revision);
      if (value.pending) return value.pending;
      if (value.attempted && !force) return Promise.resolve();
      value.attempted = true;
      update(value, {...value.state,loading:true});
      value.pending = Promise.resolve().then(() => loader(issuerId,cutoffDate)).then(data => {
        if (data.issuerId !== issuerId) throw new Error('Price issuer mismatch');
        if (data.schemaVersion !== 1 || !['CACHE','MISSING'].includes(data.source) || !Array.isArray(data.bars)) throw new Error('Invalid published price data');
        const bars = normalizeDaily(data.bars,cutoffDate);
        if (!bars.length) throw new Error(data.error || '과거 종가 자료 미확보');
        update(value,{data:{...data,source:'CACHE',bars},loading:false,error:data.error});
      }).catch(error => {
        update(value,{data:value.state.data ? {...value.state.data,source:'CACHE'} : null,
          loading:false,error:error instanceof Error ? error.message : String(error)});
      }).finally(() => {value.pending=undefined;});
      return value.pending;
    },
  };
}
