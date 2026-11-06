import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent, PointerEvent } from 'react';
import type { PriceData } from '../types';
import { latestQuote, visible } from './model';
import type { Candle, Interval } from './model';
import {readChartPreferences,saveChartPreferences,type ChartPreferences} from './preferences';
import './chart.css';

export interface ChartViewProps { data:PriceData|null; error:string|null; loading:boolean; cutoffDate:string; onReload:()=>void; preferenceKey?:string }
const INTERVAL_LABEL: Record<Interval,string> = {DAY:'일봉',WEEK:'주봉',MONTH:'월봉'};
const number = (value:number) => value.toLocaleString('ko-KR',{maximumFractionDigits:2});
const sourceLink = (url:string) => /^https:\/\//.test(url) ? url : null;
function volumeText(candle:Candle):string {
  if (candle.volume !== null) return `${number(candle.volume)}주`;
  if (candle.observedVolume !== null) return `전체 미확보 · 확인된 일부 ${number(candle.observedVolume)}주 (부분)`;
  return '미확보';
}
function PricePlot({bars,candleMode,selected,onSelect,descriptionId}:{bars:Candle[];candleMode:boolean;selected:number;onSelect:(index:number)=>void;descriptionId:string}) {
  const box = useRef<HTMLDivElement>(null);
  const [width,setWidth] = useState(1000);
  const [axisSize,setAxisSize] = useState(18);
  const patternId=useId();
  useEffect(()=>{
    const element=box.current;
    if (!element) return;
    const resize=()=>{
      setWidth(Math.max(320,Math.round(element.getBoundingClientRect().width)));
      setAxisSize(parseFloat(getComputedStyle(document.documentElement).fontSize)*0.9);
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    const preferenceObserver=new MutationObserver(resize);
    preferenceObserver.observe(document.documentElement,{attributes:true,attributeFilter:['style','class']});
    return ()=>{observer.disconnect();preferenceObserver.disconnect();};
  },[]);
  const maxCharacters=Math.max(...bars.map(bar=>Math.max(number(bar.high*1.1).length,number(bar.observedVolume??0).length)));
  const axisMargin=Math.max(128,Math.ceil(axisSize*0.65*maxCharacters+28));
  const left=18, right=width-axisMargin, top=24, bottom=400, volumeTop=430, volumeBottom=504;
  const plotWidth=Math.max(160,right-left);
  const values=bars.flatMap(bar=>candleMode ? [bar.high,bar.low] : [bar.close]);
  const observedHigh=Math.max(...values), observedLow=Math.min(...values);
  const padding=Math.max((observedHigh-observedLow)*0.07,observedHigh*0.005,0.01);
  const high=observedHigh+padding,low=Math.max(0,observedLow-padding),range=high-low;
  const y=(value:number)=>top+(high-value)/range*(bottom-top);
  const step=plotWidth/Math.max(bars.length,1);
  const x=(index:number)=>left+step*(index+0.5);
  const volumeMax=Math.max(1,...bars.map(bar=>bar.observedVolume??0));
  const candleWidth=Math.max(1,Math.min(22,step*0.7));
  const dateLabelCount=Math.max(2,Math.min(4,Math.floor(plotWidth/(axisSize*7))));
  const dateIndices=[...new Set(Array.from({length:dateLabelCount},(_,index)=>Math.round((bars.length-1)*index/(dateLabelCount-1))))];
  function onKeyDown(event:KeyboardEvent<SVGSVGElement>) {
    const index=event.key==='ArrowLeft' ? selected-1 : event.key==='ArrowRight' ? selected+1 : event.key==='Home' ? 0 : event.key==='End' ? bars.length-1 : null;
    if(index!==null){event.preventDefault();onSelect(Math.max(0,Math.min(bars.length-1,index)));}
  }
  function onPointerDown(event:PointerEvent<SVGSVGElement>) {
    const rect=event.currentTarget.getBoundingClientRect();
    const position=(event.clientX-rect.left)*width/rect.width;
    onSelect(Math.max(0,Math.min(bars.length-1,Math.floor((position-left)/step))));
    event.currentTarget.focus();
  }
  return <div className="pc-chart__plot" ref={box}>
    <svg viewBox={`0 0 ${width} 560`} width="100%" height="560" role="group" tabIndex={0}
      aria-label="실제 주가 및 거래량 차트. 왼쪽·오른쪽 방향키로 거래일을 선택합니다. Home은 처음, End는 마지막입니다."
      aria-describedby={descriptionId} onKeyDown={onKeyDown} onPointerDown={onPointerDown} data-testid="price_chart_svg">
      <title>{`실제 관측 OHLCV · ${bars[0].firstTradingDate} ~ ${bars.at(-1)!.lastTradingDate}`}</title>
      <defs><pattern id={patternId} patternUnits="userSpaceOnUse" width="6" height="6"><path d="M0 6L6 0" stroke="#aac8ed" strokeWidth="2"/></pattern></defs>
      {[0,1,2,3,4].map(index=>{
        const value=high-range*index/4,position=y(value);
        return <g key={index}><line x1={left} x2={right} y1={position} y2={position} className="pc-chart__grid"/><text x={right+12} y={position+6} className="pc-chart__axis">{number(value)}</text></g>;
      })}
      {dateIndices.map(index=><line key={index} x1={x(index)} x2={x(index)} y1={top} y2={volumeBottom} className="pc-chart__grid pc-chart__grid--vertical"/>)}
      <line x1={x(selected)} x2={x(selected)} y1={top} y2={volumeBottom} className="pc-chart__crosshair"/>
      {candleMode ? bars.map((bar,index)=><g key={bar.lastTradingDate} className={bar.close>=bar.open ? 'pc-chart__rise' : 'pc-chart__fall'} data-testid="chart_candle">
        <title>{`${bar.firstTradingDate} ~ ${bar.lastTradingDate}: 시가 ${number(bar.open)}, 고가 ${number(bar.high)}, 저가 ${number(bar.low)}, 종가 ${number(bar.close)}, 거래량 ${volumeText(bar)}`}</title>
        <line x1={x(index)} x2={x(index)} y1={y(bar.high)} y2={y(bar.low)} stroke="currentColor" strokeWidth={index===selected ? 2.5 : 1.5}/>
        <rect x={x(index)-candleWidth/2} y={Math.min(y(bar.open),y(bar.close))} width={candleWidth} height={Math.max(1.8,Math.abs(y(bar.open)-y(bar.close)))} fill="currentColor"/>
      </g>) : <><polyline points={bars.map((bar,index)=>`${x(index)},${y(bar.close)}`).join(' ')} fill="none" className="pc-chart__price-line" data-testid="chart_price_line"/><circle cx={x(selected)} cy={y(bars[selected].close)} r="5" className="pc-chart__selected-dot"/></>}
      <text x={left} y={volumeTop-9} className="pc-chart__axis">거래량 (주)</text>
      <line x1={left} x2={right} y1={volumeBottom} y2={volumeBottom} className="pc-chart__grid"/>
      {bars.map((bar,index)=>bar.observedVolume===null ? null : <rect key={bar.lastTradingDate} x={x(index)-candleWidth/2} y={volumeBottom-bar.observedVolume/volumeMax*(volumeBottom-volumeTop)} width={candleWidth} height={Math.max(bar.observedVolume===0 ? 0 : 1,bar.observedVolume/volumeMax*(volumeBottom-volumeTop))} fill={bar.volumeCoverage==='PARTIAL' ? `url(#${patternId})` : bar.close>=bar.open ? '#ff6674' : '#48a9ff'} opacity={bar.volumeCoverage==='PARTIAL' ? 1 : 0.6} data-testid="chart_volume_bar"><title>{`${bar.lastTradingDate} 거래량 ${volumeText(bar)}`}</title></rect>)}
      <text x={right+12} y={volumeTop+17} className="pc-chart__axis">{number(volumeMax)}</text>
      <text x={right+12} y={volumeBottom+2} className="pc-chart__axis">0</text>
      {dateIndices.map((index,order)=><text key={index} x={order===0?left:order===dateIndices.length-1?right:x(index)} y="540" textAnchor={order===0?'start':order===dateIndices.length-1?'end':'middle'} className="pc-chart__axis">{bars[index].lastTradingDate}</text>)}
    </svg>
  </div>;
}

function ChartPanel({data,error,loading,cutoffDate,onReload,preferenceKey}:ChartViewProps) {
  const [preferences,setPreferences]=useState(()=>readChartPreferences(preferenceKey));
  const {months,interval,candleMode}=preferences;
  function updatePreferences(patch:Partial<ChartPreferences>) {
    const next={...preferences,...patch};
    setPreferences(next);
    saveChartPreferences(preferenceKey,next);
  }
  const [selectedDate,setSelectedDate]=useState<string|null>(null);
  const detailId=useId(),titleId=useId();
  const bars=useMemo(()=>visible(data?.bars??[],interval,months),[data,interval,months]);
  const found=selectedDate ? bars.findIndex(bar=>bar.lastTradingDate===selectedDate) : -1;
  const selected=found>=0 ? found : bars.length-1;
  const candle=bars[selected];
  const latest=data?.bars.at(-1);
  const quote=latestQuote(data?.bars??[]);
  const actualLast=latest?.date;
  const windowChange=bars.length ? (bars.at(-1)!.close/bars[0].close-1)*100 : 0;
  const errors=[error, data?.error].filter((value,index,array)=>value && array.indexOf(value)===index);
  function select(index:number) {if(bars[index])setSelectedDate(bars[index].lastTradingDate);}
  return <div className="pc-chart-stack">
    <aside className="pc-chart-quote" aria-label="최근 실제 거래 가격" data-testid="chart_latest_quote">
      {quote && latest ? <>
        <div className="pc-chart-quote__main"><p>최근 실제 거래 가격</p><strong data-testid="chart_latest_close">{`${number(quote.close)}원`}</strong>
          {quote.change!==null && quote.changePct!==null ? <p className={quote.change>=0?'pc-chart-quote__rise':'pc-chart-quote__fall'} data-testid="chart_latest_change">{`${quote.change>0?'▲ ':quote.change<0?'▼ ':''}${quote.change>=0?'+':''}${number(quote.change)}원 (${quote.changePct>=0?'+':''}${quote.changePct.toFixed(2)}%)`}</p> : <p>전 거래일 비교자료 미확보</p>}
        </div>
        <dl className="pc-chart-quote__ohlc">{(['open','high','low','close'] as const).map((key,index)=><div key={key}><dt>{['시가','고가','저가','종가'][index]}</dt><dd>{number(latest[key])}</dd></div>)}</dl>
        <p className="pc-chart-quote__source" data-testid="chart_latest_volume">{`${quote.date} · 거래량 ${latest.volume===null?'미확보':`${number(latest.volume)}주`} · CACHE · 화면 기준 ${cutoffDate}`}</p>
      </> : <><p>최근 실제 거래 가격</p><strong>{data?.source==='MISSING'&&!loading?'가격 자료 미확보':'가격 자료 대기'}</strong><p className="pc-chart-quote__source">화면 기준 {cutoffDate} · {data?.source==='MISSING'&&!loading?'실제 일봉을 확보하지 못했습니다.':'수집된 실제 가격을 기다립니다.'}</p></>}
    </aside>
    <section className="pc-chart" aria-labelledby={titleId} data-testid="price_chart_panel" data-interval={interval} data-months={months} data-chart-mode={candleMode?'candle':'line'}>
    <header className="pc-chart__heading"><h2 id={titleId}>주가 차트</h2><span className="pc-chart__warning">⚠ 수정주가 미확인</span></header>
    <div className="pc-chart__controls">
      <div className="pc-chart__choices" role="group" aria-label="봉 간격">{(['DAY','WEEK','MONTH'] as const).map(value=><button key={value} type="button" aria-pressed={interval===value} onClick={()=>updatePreferences({interval:value})} data-testid={`chart_interval_${value}`}>{INTERVAL_LABEL[value]}</button>)}</div>
      <div className="pc-chart__choices" role="group" aria-label="조회 기간">{([1,3,6,12] as const).map(value=><button key={value} type="button" aria-pressed={months===value} onClick={()=>updatePreferences({months:value})} data-testid={`chart_period_${value}`}>{value===12?'1년':`${value}개월`}</button>)}</div>
      <button className="pc-chart__switch" type="button" role="switch" aria-checked={candleMode} aria-label="캔들차트" onClick={()=>updatePreferences({candleMode:!candleMode})} data-testid="chart_candle_toggle"><span>{candleMode?'캔들차트':'선차트'}</span><span aria-hidden="true" className="pc-chart__switch-track"><span/></span></button>
    </div>
    {!!errors.length && <div role="alert" className="pc-chart__error">가격 수신 오류: {errors.join(' · ')}{data?.source==='CACHE' && data.bars.length>0 && ' · 저장된 검증 가격 CACHE를 표시합니다.'}</div>}
    {data && data.requestedCutoffDate!==cutoffDate && <p role="alert" className="pc-chart__error">요청 기준일 불일치: 화면 {cutoffDate} · 가격 파일 {data.requestedCutoffDate}. 화면 기준 이후 가격은 제외했습니다.</p>}
    {data && actualLast && data.lastTradingDate!==actualLast && <p role="alert" className="pc-chart__error">최근 거래일 표시 불일치: 가격 파일 {data.lastTradingDate} · 표시 가능한 실제 거래일 {actualLast}</p>}
    {loading && <p role="status" className="pc-chart__status">실제 일별 주가 불러오는 중…</p>}
    {candle ? <>
      <p className="pc-chart__caption">{months===12?'1년':`${months}개월`} · {INTERVAL_LABEL[interval]} · {candleMode?'캔들차트':'선차트'} · {bars.length}개 관측 봉</p>
      <PricePlot bars={bars} candleMode={candleMode} selected={selected} onSelect={select} descriptionId={detailId}/>
      <div className="pc-chart__selection" id={detailId} aria-live="polite" aria-atomic="true">
        <p className="pc-chart__selected-date">선택 {candle.firstTradingDate}{candle.firstTradingDate!==candle.lastTradingDate?` ~ ${candle.lastTradingDate}`:''} · 실제 {candle.tradingDays}거래일</p>
        <dl className="pc-chart__ohlc" data-testid="chart_last_ohlc">{(['open','high','low','close'] as const).map((key,index)=><div key={key}><dt>{['시가','고가','저가','종가'][index]}</dt><dd>{number(candle[key])}<span>원</span></dd></div>)}</dl>
        <p data-testid="chart_last_volume">거래량 {volumeText(candle)}</p>
        <p className="pc-chart__muted">{INTERVAL_LABEL[interval]} · 제공 원본 OHLC{interval==='DAY'?'':' 집계'} · 실제 거래일 기준</p>
      </div>
      <div className="pc-chart__navigation"><button type="button" onClick={()=>select(selected-1)} disabled={selected<=0} aria-label="이전 봉 선택">← 이전 봉</button><span>{selected+1} / {bars.length}</span><button type="button" onClick={()=>select(selected+1)} disabled={selected>=bars.length-1} aria-label="다음 봉 선택">다음 봉 →</button></div>
      <p className="pc-chart__muted">차트를 누르거나 방향키 ← → 로 봉을 선택하세요. 거래량 사선은 일부만 확인된 값입니다.</p>
      <p className="pc-chart__muted" data-testid="chart_bar_count">표시 범위 {bars[0].firstTradingDate} ~ {bars.at(-1)!.lastTradingDate} · 최저 {number(Math.min(...bars.map(bar=>candleMode?bar.low:bar.close)))}원 · 최고 {number(Math.max(...bars.map(bar=>candleMode?bar.high:bar.close)))}원</p>
      <p className={windowChange>=0?'pc-chart__rise':'pc-chart__fall'} data-testid="chart_window_change">{`선택 기간 원본 가격 변동 ${windowChange>=0?'+':''}${windowChange.toFixed(2)}%`}</p>
      {interval!=='DAY' && <p className="pc-chart__muted" data-testid="chart_aggregation_note">시작 {INTERVAL_LABEL[interval]}은 첫 거래일부터 집계합니다. {bars.at(-1)!.periodEnd>cutoffDate ? `마지막 ${INTERVAL_LABEL[interval]}은 기준일까지의 부분 봉입니다.` : ''}</p>}
    </> : !loading && <div className="pc-chart__empty">과거 종가 자료 미확보 · 수집된 실제 일봉이 없습니다.</div>}
    <footer className="pc-chart__source" data-testid="chart_source">
      <p><strong>{data?.source??'MISSING'}</strong> · 네이버증권 · 공개된 수집 가격</p>
      <dl><div><dt>화면 요청 기준일</dt><dd>{cutoffDate}</dd></div><div><dt>가격 파일 기준일</dt><dd>{data?.requestedCutoffDate||'미확보'}</dd></div><div><dt>실제 최근 거래일</dt><dd>{actualLast||'미확보'}</dd></div><div><dt>원천 수집 시각</dt><dd>{data?.collectedAt||'미확보'}</dd></div><div><dt>최근 수집 시도</dt><dd>{data?.lastAttemptAt||'미확보'}</dd></div></dl>
      {data?.sourceUrl && sourceLink(data.sourceUrl) && <a href={sourceLink(data.sourceUrl)!} target="_blank" rel="noreferrer">네이버증권 원문 출처 ↗</a>}
      <p className="pc-chart__muted">공식 기업행사별 적용일·조정계수와 제공 OHLC의 기수정 여부를 확인하지 못했습니다. 제공 원본 OHLC 유지 · 추가 조정 없음.</p>
      <p className="pc-chart__muted">분할·병합 전후의 가격 변동에는 기업행사 영향이 포함될 수 있습니다. 결측 거래일은 임의로 채우지 않습니다. 4지표 순위와 별도 참고자료입니다.</p>
      <button type="button" className="pc-chart__retry" onClick={onReload} disabled={loading}>{loading?'불러오는 중…':'다시 불러오기'}</button>
    </footer>
  </section></div>;
}

// The issuer is known before prices load. Keying the stateful panel also prevents
// choices from the previous stock appearing if this component changes issuers.
export default function ChartView(props:ChartViewProps) {
  return <ChartPanel key={props.preferenceKey??''} {...props}/>;
}
