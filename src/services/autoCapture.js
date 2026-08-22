import { Capacitor, registerPlugin } from '@capacitor/core';
import { Directory, Encoding, Filesystem } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';

export const AutoCapture = registerPlugin('AutoCapture');

export const isAutoCaptureAvailable = () => Capacitor.isNativePlatform();

const pad = (value) => String(value).padStart(2, '0');

export const parseCaptureTime = (value) => {
  if (!value) return Date.now();
  const text = String(value).trim();
  const m = text.match(/^(\d{4})[\/-](\d{2})[\/-](\d{2})(?:\s+(\d{2}):(\d{2})(?::(\d{2}))?)?/);
  if (!m) {
    const d = new Date(text);
    return Number.isNaN(d.getTime()) ? Date.now() : d.getTime();
  }
  return new Date(
    Number(m[1]), Number(m[2]) - 1, Number(m[3]),
    Number(m[4] || 12), Number(m[5] || 0), Number(m[6] || 0)
  ).getTime();
};

const autoCategory = (shop = '') => {
  if (/7-?11|統一超商|全家|FamilyMart|萊爾富|OK超商/i.test(shop)) return '超商';
  if (/KFC|肯德基|麥當勞|McDonald|CoCo|悟饕|必勝客|漢堡王|BurgerKing|餐|飯|咖啡/i.test(shop)) return '餐飲';
  if (/MOMO|購物|商場|百貨/i.test(shop)) return '網購';
  return '未分類';
};

const paymentDisplay = (tx) => {
  if (tx.cardName) return tx.cardLast4 ? `${tx.cardName} ${tx.cardLast4}` : tx.cardName;
  if (tx.bank) return tx.cardLast4 ? `${tx.bank} ${tx.cardLast4}` : tx.bank;
  if (tx.paymentAccount) return tx.paymentAccount;
  return '付款方式未提供';
};

export function mergeTransactionsIntoTaiwanHistory(transactions = []) {
  const saved = localStorage.getItem('historyMap');
  let historyMap;
  try {
    historyMap = saved ? JSON.parse(saved) : { KR: [], TW: [], JP: [] };
  } catch {
    historyMap = { KR: [], TW: [], JP: [] };
  }
  historyMap.KR = Array.isArray(historyMap.KR) ? historyMap.KR : [];
  historyMap.TW = Array.isArray(historyMap.TW) ? historyMap.TW : [];
  historyMap.JP = Array.isArray(historyMap.JP) ? historyMap.JP : [];

  const known = new Set(historyMap.TW.map((item) => item.autoCaptureKey).filter(Boolean));
  let added = 0;

  transactions.forEach((tx) => {
    const key = tx.key || `${tx.source}|${tx.date}|${tx.shop}|${tx.amount}`;
    if (!key || known.has(key)) return;
    const amount = Math.abs(Number(String(tx.amount || '0').replace(/,/g, '')));
    if (!Number.isFinite(amount) || amount <= 0 || !tx.shop) return;

    const sourceLabel = tx.sourceLabel || tx.source || '自動擷取';
    const payment = paymentDisplay(tx);
    historyMap.TW.push({
      time: parseCaptureTime(tx.date),
      name: `${sourceLabel} · ${payment}`,
      amount,
      note: tx.shop,
      category: autoCategory(tx.shop),
      source: tx.source,
      sourceLabel,
      autoCaptureKey: key,
      paymentMethod: tx.paymentMethod || '',
      paymentAccount: tx.paymentAccount || '',
      bank: tx.bank || '',
      cardLast4: tx.cardLast4 || '',
      transactionId: tx.transactionId || '',
      detailChecked: Boolean(tx.detailChecked)
    });
    known.add(key);
    added += 1;
  });

  historyMap.TW.sort((a, b) => Number(b.time || 0) - Number(a.time || 0));
  localStorage.setItem('historyMap', JSON.stringify(historyMap));
  return { added, total: historyMap.TW.length };
}

export async function exportGoogleWalletDiagnostics(captures = []) {
  const now = new Date();
  const fileName = `PayHelper_GoogleWallet_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.json`;
  const data = JSON.stringify({
    format: 'Pay Helper Google Wallet diagnostic',
    version: 1,
    exportedAt: now.toISOString(),
    googleWalletDiagnosticCaptures: captures
  }, null, 2);

  if (!Capacitor.isNativePlatform()) {
    const blob = new Blob([data], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    return { fileName };
  }

  const result = await Filesystem.writeFile({
    path: fileName,
    data,
    directory: Directory.Cache,
    encoding: Encoding.UTF8,
    recursive: true
  });
  try {
    await Share.share({
      title: 'Google Wallet 診斷資料',
      text: '請儲存或分享這份 Google Wallet 交易記錄診斷 JSON。',
      files: [result.uri],
      dialogTitle: '匯出 Google Wallet 診斷'
    });
  } catch (error) {
    console.warn('Diagnostic file created but share sheet closed:', error);
  }
  return { fileName, uri: result.uri };
}
