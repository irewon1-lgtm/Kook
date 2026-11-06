import { createRoot } from 'react-dom/client';
import '@fontsource-variable/noto-sans-kr';
import App from './App';
import { MarketReceiver, configureReceiver } from './data/receiver';
import bundled from './generated/bundled.json';
import type { PublicationManifest } from './types';

async function boot() {
  const bytes = Uint8Array.from(atob(bundled.stocksGzip), char => char.charCodeAt(0));
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  const stocksText = await new Response(stream).text();
  const receiver = new MarketReceiver({ bundled: { manifest: bundled.manifest as PublicationManifest, stocksText } });
  configureReceiver(receiver);
  createRoot(document.getElementById('root')!).render(<App receiver={receiver}/>);
}
void boot().catch(error => {
  const root = document.getElementById('root')!;
  const message = document.createElement('p'); message.textContent = '초기 자료를 열지 못했습니다. Chrome/Edge 최신 버전에서 새로고침해 주세요. ' + (error instanceof Error ? error.message : String(error));
  message.style.cssText = 'font:22px sans-serif;color:white;background:#061321;padding:32px'; root.replaceChildren(message);
});
