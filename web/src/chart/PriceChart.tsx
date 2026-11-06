import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { getPriceRevision, loadPrice, subscribePublication } from '../data/receiver';
import { createPriceStore } from './store';
import ChartView from './ChartView';
export { latestQuote } from './model';

const prices=createPriceStore(loadPrice);
export function usePriceData(issuerId:string,cutoffDate:string) {
  const getRevision=useCallback(()=>getPriceRevision(issuerId),[issuerId]);
  // React compares the price SHA snapshot, so receive attempts with unchanged
  // published prices do not reload either the quote or the chart.
  const revision=useSyncExternalStore(subscribePublication,getRevision,getRevision);
  const subscribe=useCallback((listener:()=>void)=>prices.subscribe(issuerId,cutoffDate,listener,revision),[issuerId,cutoffDate,revision]);
  const getSnapshot=useCallback(()=>prices.get(issuerId,cutoffDate,revision),[issuerId,cutoffDate,revision]);
  const state=useSyncExternalStore(subscribe,getSnapshot,getSnapshot);
  useEffect(()=>{void prices.load(issuerId,cutoffDate,false,revision);},[issuerId,cutoffDate,revision]);
  const reload=useCallback(()=>{void prices.load(issuerId,cutoffDate,true,revision);},[issuerId,cutoffDate,revision]);
  return {...state,reload};
}
export default function PriceChart({issuerId,cutoffDate}:{issuerId:string;cutoffDate:string}) {
  const {data,error,loading,reload}=usePriceData(issuerId,cutoffDate);
  return <ChartView data={data} error={error} loading={loading} onReload={reload} cutoffDate={cutoffDate} preferenceKey={issuerId}/>;
}
