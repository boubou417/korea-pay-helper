import React, { useEffect, useState } from 'react';
import {
  AutoCapture,
  exportGoogleWalletDiagnostics,
  isAutoCaptureAvailable,
  mergeTransactionsIntoTaiwanHistory
} from '../../services/autoCapture';

const buttonClass = 'w-full rounded-xl px-3 py-2.5 text-sm font-semibold transition active:scale-[0.99]';

export default function AutoCapturePanel({ darkMode }) {
  const native = isAutoCaptureAvailable();
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const refresh = async () => {
    if (!native) return;
    try {
      setStatus(await AutoCapture.getStatus());
    } catch (error) {
      console.warn(error);
    }
  };

  useEffect(() => { refresh(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (label, fn) => {
    setBusy(true);
    setMessage('');
    try {
      await fn();
      setMessage(`${label}已啟動；完成後回到 Pay Helper 再按「匯入自動消費」。`);
    } catch (error) {
      const text = String(error?.message || error || 'UNKNOWN');
      setMessage(text.includes('ACCESSIBILITY') ? '請先開啟「Pay Helper 自動消費同步」無障礙服務。' : `${label}啟動失敗：${text}`);
    } finally {
      setBusy(false);
      setTimeout(refresh, 500);
    }
  };

  const importTransactions = async () => {
    setBusy(true);
    try {
      const result = await AutoCapture.getTransactions();
      const merged = mergeTransactionsIntoTaiwanHistory(result.transactions || []);
      setMessage(`已匯入 ${merged.added} 筆新交易；台灣消費紀錄目前 ${merged.total} 筆。`);
      await refresh();
      if (merged.added > 0) window.setTimeout(() => window.location.reload(), 650);
    } catch (error) {
      setMessage(`匯入失敗：${error?.message || error}`);
    } finally {
      setBusy(false);
    }
  };

  const exportGoogle = async () => {
    setBusy(true);
    try {
      const result = await AutoCapture.getGoogleWalletDiagnostics();
      const captures = result.captures || [];
      if (!captures.length) {
        setMessage('目前沒有 Google Wallet 診斷畫面。請先啟動診斷並進入「查看更多交易」及單筆交易明細。');
        return;
      }
      await exportGoogleWalletDiagnostics(captures);
      setMessage(`已建立 Google Wallet 診斷 JSON，共 ${captures.length} 個畫面。`);
    } catch (error) {
      setMessage(`Google Wallet 診斷匯出失敗：${error?.message || error}`);
    } finally {
      setBusy(false);
    }
  };

  const card = darkMode
    ? 'bg-gray-800 border border-gray-700 text-white rounded-3xl shadow-xl p-4 mb-4'
    : 'bg-white border border-gray-200 text-gray-900 rounded-3xl shadow-xl p-4 mb-4';

  if (!native) {
    return (
      <div className={card}>
        <div className="font-bold mb-1">自動消費同步</div>
        <div className="text-xs opacity-60">此功能只在 Android APK 版啟用；Web / PWA 仍可照常手動記帳。</div>
      </div>
    );
  }

  return (
    <div className={card}>
      <div className="flex items-center justify-between gap-2 mb-2">
        <div>
          <div className="font-bold">自動消費同步（Android）</div>
          <div className="text-[11px] opacity-60">街口 · LINE Pay · Pi 拍錢包 · Google Wallet</div>
        </div>
        <span className={`px-2 py-1 rounded-full text-[10px] ${status?.accessibilityEnabled ? 'bg-green-500 text-white' : 'bg-amber-500 text-white'}`}>
          {status?.accessibilityEnabled ? '無障礙已開啟' : '需開啟無障礙'}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 text-[11px] mb-3 opacity-70">
        <div>街口：{status?.jkoCount ?? 0}</div>
        <div>LINE Pay：{status?.linePayCount ?? 0}</div>
        <div>Pi：{status?.piWalletCount ?? 0}</div>
        <div>Google 診斷：{status?.googleDiagnosticCount ?? 0}</div>
      </div>

      {!status?.accessibilityEnabled && (
        <button disabled={busy} onClick={() => AutoCapture.openAccessibilitySettings()} className={`${buttonClass} mb-2 bg-amber-500 text-white`}>
          開啟 Pay Helper 無障礙服務
        </button>
      )}

      <div className="grid grid-cols-1 gap-2">
        <button disabled={busy} onClick={() => run('街口快速同步', () => AutoCapture.syncJko())} className={`${buttonClass} bg-blue-600 text-white`}>同步街口最新交易</button>
        <button disabled={busy} onClick={() => run('LINE Pay 快速同步', () => AutoCapture.syncLinePay())} className={`${buttonClass} bg-green-600 text-white`}>同步 LINE Pay 最新交易</button>
        <button disabled={busy} onClick={() => run('Pi 拍錢包快速同步', () => AutoCapture.syncPiWallet())} className={`${buttonClass} bg-purple-600 text-white`}>同步 Pi 拍錢包最新交易</button>
        <button disabled={busy} onClick={() => run('Google Wallet 交易紀錄診斷', () => AutoCapture.diagnoseGoogleWallet())} className={`${buttonClass} bg-gray-700 text-white`}>Google Wallet：掃交易記錄（診斷）</button>
        <button disabled={busy} onClick={importTransactions} className={`${buttonClass} bg-cyan-600 text-white`}>匯入自動消費到台灣紀錄</button>
        <button disabled={busy} onClick={exportGoogle} className={`${buttonClass} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-gray-200 text-gray-800'}`}>匯出 Google Wallet 診斷 JSON</button>
      </div>

      {message && <div className={`mt-3 text-xs leading-relaxed ${darkMode ? 'text-gray-300' : 'text-gray-600'}`}>{message}</div>}
      <div className="mt-3 text-[10px] opacity-50 leading-relaxed">
        Google Wallet 不依賴付款通知；這一版直接從 Wallet 的「查看更多交易」與單筆明細取得真實 Accessibility 結構。第一次請跑診斷並匯出 JSON，之後即可升級成正式快速同步。
      </div>
    </div>
  );
}
