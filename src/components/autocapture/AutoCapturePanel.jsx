import React, { useEffect, useRef, useState } from 'react';
import { AutoCapture, ensureNightlySync, exportGoogleWalletDiagnostics, importCapturedTransactions, isAutoCaptureAvailable, syncCapturedTransactionsNow } from '../../services/autoCapture';
const buttonClass='w-full rounded-xl px-3 py-2.5 text-sm font-semibold transition active:scale-[0.99]';
export default function AutoCapturePanel({darkMode}){
 const native=isAutoCaptureAvailable();const[status,setStatus]=useState(null);const[busy,setBusy]=useState(false);const[message,setMessage]=useState('');const importingRef=useRef(false);const wasUnifiedRunningRef=useRef(false);
 const refresh=async()=>{if(!native)return null;try{const s=await AutoCapture.getStatus();setStatus(s);return s;}catch(error){console.warn(error);return null;}};
 const autoImport=async(showMessage=false)=>{if(!native||importingRef.current)return null;importingRef.current=true;try{
   // IMPORTANT: every legacy collector returns Pay Helper to foreground when it
   // finishes. During a four-source unified run that intermediate focus event must
   // NOT import/reload the web UI, otherwise it races with the native orchestrator
   // that is about to launch LINE/Pi/Google and keeps Pay Helper in front.
   const s=await AutoCapture.getStatus();setStatus(s);
   if(s?.unifiedSyncRunning){wasUnifiedRunningRef.current=true;return null;}
   const merged=await importCapturedTransactions();
   if(showMessage||merged.added>0){const m=merged.added>0?`；已配對優惠 ${merged.matched} 筆${merged.unmatched?`，待確認 ${merged.unmatched} 筆`:''}`:'';setMessage(`已寫入 ${merged.added} 筆新交易到台灣消費紀錄${m}。`);}
   // Reload only after the complete unified run has ended. Never reload between wallets.
   const finishedUnified=wasUnifiedRunningRef.current;wasUnifiedRunningRef.current=false;
   if(merged.added>0||finishedUnified)window.setTimeout(()=>window.location.reload(),500);
   return merged;
 }catch(error){if(showMessage)setMessage(`同步寫入失敗：${error?.message||error}`);return null;}finally{importingRef.current=false;}};
 useEffect(()=>{if(!native)return;refresh();ensureNightlySync().catch(console.warn);const onVisible=()=>{if(!document.hidden)autoImport(false);};const onFocus=()=>autoImport(false);document.addEventListener('visibilitychange',onVisible);window.addEventListener('focus',onFocus);const timer=window.setInterval(async()=>{if(document.hidden)return;const s=await refresh();if(s?.unifiedSyncRunning){wasUnifiedRunningRef.current=true;return;}if(wasUnifiedRunningRef.current)autoImport(false);},1800);return()=>{document.removeEventListener('visibilitychange',onVisible);window.removeEventListener('focus',onFocus);window.clearInterval(timer);};},[native]);
 const instantSync=async()=>{setBusy(true);wasUnifiedRunningRef.current=true;setMessage('即時同步已啟動：街口 → LINE Pay → Pi 拍錢包 → Google Pay。同步途中 Pay Helper 不會再自動 reload，全部完成後才一次寫入。');try{const merged=await syncCapturedTransactionsNow();if(merged.added>0)setMessage(`已先整理 ${merged.added} 筆既有交易；現在開始依序更新四個支付來源。完成後才會一次寫入/更新畫面。`);}catch(error){wasUnifiedRunningRef.current=false;const text=String(error?.message||error||'UNKNOWN');setMessage(text.includes('ACCESSIBILITY')?'請先開啟「Pay Helper 自動消費同步」無障礙服務。':text.includes('SYNC_ALREADY_RUNNING')?'目前已有同步正在進行中。':`即時同步啟動失敗：${text}`);}finally{setBusy(false);setTimeout(refresh,700);}};
 const exportGoogle=async()=>{setBusy(true);try{const result=await AutoCapture.getGoogleWalletDiagnostics();const captures=result.captures||[];if(!captures.length){setMessage('目前沒有 Google Wallet 診斷畫面。');return;}await exportGoogleWalletDiagnostics(captures);setMessage(`已建立 Google Wallet 診斷 JSON，共 ${captures.length} 個畫面。`);}catch(error){setMessage(`Google Wallet 診斷匯出失敗：${error?.message||error}`);}finally{setBusy(false);}};
 const card=darkMode?'bg-gray-800 border border-gray-700 text-white rounded-3xl shadow-xl p-4 mb-4':'bg-white border border-gray-200 text-gray-900 rounded-3xl shadow-xl p-4 mb-4';if(!native)return <div className={card}><div className="font-bold mb-1">自動消費同步</div><div className="text-xs opacity-60">此功能只在 Android APK 版啟用。</div></div>;
 const stages=['街口','LINE Pay','Pi 拍錢包','Google Pay','完成'];
 return <div className={card}><div className="flex items-center justify-between gap-2 mb-2"><div><div className="font-bold">即時同步（Android）</div><div className="text-[11px] opacity-60">四種支付交易記錄 → 比對信用卡優惠 → 自動寫入</div></div><span className={`px-2 py-1 rounded-full text-[10px] ${status?.accessibilityEnabled?'bg-green-500':'bg-amber-500'} text-white`}>{status?.accessibilityEnabled?'同步服務已開啟':'需開啟無障礙'}</span></div>
 <div className="grid grid-cols-2 gap-2 text-[11px] mb-3 opacity-70"><div>街口：{status?.jkoCount??0}</div><div>LINE Pay：{status?.linePayCount??0}</div><div>Pi：{status?.piWalletCount??0}</div><div>Google Pay：{status?.googleWalletCount??0}</div></div>
 {status?.unifiedSyncRunning&&<div className={`${darkMode?'bg-blue-950':'bg-blue-50'} rounded-xl p-3 text-xs mb-3`}>同步進行中：{stages[status?.unifiedSyncStage]||'處理中'}。同步途中即使 Pay Helper 暫時回前景，也不會重新整理頁面。</div>}
 {status?.unifiedSyncDiagnostic&&<div className={`${darkMode?'bg-gray-900':'bg-gray-100'} rounded-xl px-3 py-2 text-[10px] font-mono mb-3 break-all`}>native: {status.unifiedSyncDiagnostic}</div>}
 <div className={`${darkMode?'bg-gray-700/60':'bg-gray-50'} rounded-xl p-3 text-xs mb-3`}><b>每日自動同步：02:00</b><div className="opacity-70 mt-1">已自動排程。Android 省電/Doze 可能讓執行時間稍微延後；同步完成會回 Pay Helper 首頁，之後由手機原本的螢幕逾時自行熄屏。</div></div>
 {!status?.accessibilityEnabled&&<button disabled={busy} onClick={()=>AutoCapture.openAccessibilitySettings()} className={`${buttonClass} mb-2 bg-amber-500 text-white`}>開啟 Pay Helper 無障礙服務</button>}
 <button disabled={busy||!status?.accessibilityEnabled||status?.unifiedSyncRunning} onClick={instantSync} className={`${buttonClass} bg-gradient-to-r from-cyan-600 to-blue-600 text-white text-base py-3`}>{status?.unifiedSyncRunning?'同步進行中…':busy?'啟動中…':'立即同步並寫入消費紀錄'}</button>
 <details className="mt-3"><summary className="text-xs opacity-60 cursor-pointer">診斷 / 手動整理工具</summary><div className="grid grid-cols-1 gap-2 mt-2"><button disabled={busy||status?.unifiedSyncRunning} onClick={()=>autoImport(true)} className={`${buttonClass} ${darkMode?'bg-gray-700':'bg-gray-100'}`}>只整理目前已擷取的交易</button><button disabled={busy} onClick={()=>AutoCapture.diagnoseGoogleWallet()} className={`${buttonClass} ${darkMode?'bg-gray-700':'bg-gray-200'}`}>Google Wallet 診斷</button><button disabled={busy} onClick={exportGoogle} className={`${buttonClass} ${darkMode?'bg-gray-700':'bg-gray-200'}`}>匯出 Google Wallet 診斷 JSON</button></div></details>
 {message&&<div className={`mt-3 text-xs leading-relaxed ${darkMode?'text-gray-300':'text-gray-600'}`}>{message}</div>}<div className="mt-3 text-[10px] opacity-50 leading-relaxed">測試版流程：街口 → LINE Pay → Pi 拍錢包 → Google Pay。四個來源全部結束前禁止 Web 層自動 reload；最後回 Pay Helper 後才一次去重、配對優惠並寫入台灣消費紀錄。</div></div>;
}
