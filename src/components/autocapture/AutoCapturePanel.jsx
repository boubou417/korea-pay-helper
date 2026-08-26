import React, { useEffect, useRef, useState } from 'react';
import {
  AutoCapture,
  exportGoogleWalletDiagnostics,
  importCapturedTransactions,
  isAutoCaptureAvailable,
  syncCapturedTransactionsNow
} from '../../services/autoCapture';

const buttonClass = 'w-full rounded-xl px-3 py-2.5 text-sm font-semibold transition active:scale-[0.99]';

export default function AutoCapturePanel({ darkMode }) {
  const native = isAutoCaptureAvailable();
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const importingRef = useRef(false);

  const refresh = async () => {
    if (!native) return;
    try { setStatus(await AutoCapture.getStatus()); } catch (error) { console.warn(error); }
  };

  const autoImport = async (showMessage = false) => {
    if (!native || importingRef.current) return null;
    importingRef.current = true;
    try {
      const merged = await importCapturedTransactions();
      if (showMessage || merged.added > 0) {
        const matchText = merged.added > 0 ? `；已配對優惠 ${merged.matched} 筆${merged.unmatched ? `，待確認 ${merged.unmatched} 筆` : ''}` : '';
        setMessage(`已寫入 ${merged.added} 筆新交易到台灣消費紀錄${matchText}。`);
      }
      await refresh();
      if (merged.added > 0) window.setTimeout(() => window.location.reload(), 500);
      return merged;
    } catch (error) {
      if (showMessage) setMessage(`同步寫入失敗：${error?.message || error}`);
      return null;
    } finally { importingRef.current = false; }
  };

  useEffect(() => {
    if (!native) return;
    refresh();
    const onVisible = () => { if (!document.hidden) autoImport(false); };
    const onFocus = () => autoImport(false);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onFocus);
    const timer = window.setInterval(() => { if (!document.hidden) autoImport(false); }, 5000);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onFocus);
      window.clearInterval(timer);
    };
  }, [native]);

  const instantSync = async () => {
    setBusy(true); setMessage('正在整理已擷取交易並更新 Google Pay…');
    try {
      const merged = await syncCapturedTransactionsNow({ refreshGoogleWallet: true });
      const first = merged.added > 0 ? `已先寫入 ${merged.added} 筆現有新交易。` : '目前 native store 沒有尚未寫入的交易。';
      setMessage(`${first} Google Wallet 已啟動更新；完成回到 Pay Helper 後會自動寫入，不用再按匯入。`);
    } catch (error) {
      const text = String(error?.message || error || 'UNKNOWN');
      setMessage(text.includes('ACCESSIBILITY') ? '請先開啟「Pay Helper 自動消費同步」無障礙服務。' : `即時同步啟動失敗：${text}`);
    } finally { setBusy(false); setTimeout(refresh, 500); }
  };

  const exportGoogle = async () => {
    setBusy(true);
    try {
      const result = await AutoCapture.getGoogleWalletDiagnostics();
      const captures = result.captures || [];
      if (!captures.length) { setMessage('目前沒有 Google Wallet 診斷畫面。'); return; }
      await exportGoogleWalletDiagnostics(captures);
      setMessage(`已建立 Google Wallet 診斷 JSON，共 ${captures.length} 個畫面。`);
    } catch (error) { setMessage(`Google Wallet 診斷匯出失敗：${error?.message || error}`); }
    finally { setBusy(false); }
  };

  const card = darkMode ? 'bg-gray-800 border border-gray-700 text-white rounded-3xl shadow-xl p-4 mb-4' : 'bg-white border border-gray-200 text-gray-900 rounded-3xl shadow-xl p-4 mb-4';
  if (!native) return <div className={card}><div className="font-bold mb-1">自動消費同步</div><div className="text-xs opacity-60">此功能只在 Android APK 版啟用；Web / PWA 仍可照常手動記帳。</div></div>;

  return <div className={card}>
    <div className="flex items-center justify-between gap-2 mb-2">
      <div><div className="font-bold">即時同步（Android）</div><div className="text-[11px] opacity-60">街口 · LINE Pay · Pi 拍錢包 · Google Pay → 自動寫入台灣紀錄</div></div>
      <span className={`px-2 py-1 rounded-full text-[10px] ${status?.accessibilityEnabled ? 'bg-green-500 text-white' : 'bg-amber-500 text-white'}`}>{status?.accessibilityEnabled ? '同步服務已開啟' : '需開啟無障礙'}</span>
    </div>

    <div className="grid grid-cols-2 gap-2 text-[11px] mb-3 opacity-70">
      <div>街口：{status?.jkoCount ?? 0}</div><div>LINE Pay：{status?.linePayCount ?? 0}</div>
      <div>Pi：{status?.piWalletCount ?? 0}</div><div>Google Pay：{status?.googleWalletCount ?? 0}</div>
    </div>

    {!status?.accessibilityEnabled && <button disabled={busy} onClick={() => AutoCapture.openAccessibilitySettings()} className={`${buttonClass} mb-2 bg-amber-500 text-white`}>開啟 Pay Helper 無障礙服務</button>}

    <button disabled={busy || !status?.accessibilityEnabled} onClick={instantSync} className={`${buttonClass} bg-gradient-to-r from-cyan-600 to-blue-600 text-white text-base py-3`}>
      {busy ? '同步處理中…' : '立即同步並寫入消費紀錄'}
    </button>

    <button disabled={busy} onClick={() => autoImport(true)} className={`${buttonClass} mt-2 ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-gray-100 text-gray-700'}`}>只整理目前已擷取的交易</button>

    <details className="mt-3">
      <summary className="text-xs opacity-60 cursor-pointer">診斷工具</summary>
      <div className="grid grid-cols-1 gap-2 mt-2">
        <button disabled={busy} onClick={() => AutoCapture.diagnoseGoogleWallet()} className={`${buttonClass} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-gray-200 text-gray-800'}`}>Google Wallet 診斷</button>
        <button disabled={busy} onClick={exportGoogle} className={`${buttonClass} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-gray-200 text-gray-800'}`}>匯出 Google Wallet 診斷 JSON</button>
      </div>
    </details>

    {message && <div className={`mt-3 text-xs leading-relaxed ${darkMode ? 'text-gray-300' : 'text-gray-600'}`}>{message}</div>}
    <div className="mt-3 text-[10px] opacity-50 leading-relaxed">街口、LINE Pay、Pi 在平常使用時由無障礙服務累積交易；Pay Helper 回到前景會自動整理寫入。Google Pay 因需讀取 Wallet 交易記錄，「立即同步」會開啟 Google Wallet 更新，完成回來後自動寫入。若能唯一配對到已設定的銀行卡片與行動支付優惠，會直接累計到該優惠額度；有多個可能方案時不會亂猜。</div>
  </div>;
}
