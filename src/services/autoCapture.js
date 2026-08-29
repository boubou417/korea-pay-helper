import { Capacitor, registerPlugin } from '@capacitor/core';
import { Directory, Encoding, Filesystem } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';

export const AutoCapture = registerPlugin('AutoCapture');
export const isAutoCaptureAvailable = () => Capacitor.isNativePlatform();
const pad = value => String(value).padStart(2, '0');

export const parseCaptureTime = value => {
  if (!value) return Date.now();
  const text = String(value).trim();
  const m = text.match(/^(\d{4})[\/-](\d{2})[\/-](\d{2})(?:\s+(\d{2}):(\d{2})(?::(\d{2}))?)?/);
  if (!m) {
    const d = new Date(text);
    return Number.isNaN(d.getTime()) ? Date.now() : d.getTime();
  }
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4] || 12), Number(m[5] || 0), Number(m[6] || 0)).getTime();
};

const autoCategory = (shop = '') => /7-?11|統一超商|全家|FamilyMart|萊爾富|OK超商/i.test(shop) ? '超商' : /KFC|肯德基|麥當勞|McDonald|CoCo|悟饕|必勝客|漢堡王|BurgerKing|餐|飯|咖啡/i.test(shop) ? '餐飲' : /MOMO|購物|商場|百貨/i.test(shop) ? '網購' : '未分類';
const walletId = tx => {
  const s = String(tx.source || '').toUpperCase();
  if (s === 'LINE_PAY') return 'line_pay';
  if (s === 'GOOGLE_WALLET') return 'google_pay';
  if (s === 'JKOPAY') return 'jkopay';
  if (s === 'PI_WALLET') return 'pi';
  return '';
};
const paymentDisplay = tx => tx.cardName ? (tx.cardLast4 ? `${tx.cardName} ${tx.cardLast4}` : tx.cardName) : tx.bank ? (tx.cardLast4 ? `${tx.bank} ${tx.cardLast4}` : tx.bank) : tx.paymentAccount || tx.paymentMethod || '付款方式未提供';
const readSettingsMap = () => { try { return JSON.parse(localStorage.getItem('settingsMap') || '{}'); } catch { return {}; } };
const ruleActiveAt = (p, time) => {
  const d = new Date(time);
  if (Number.isNaN(d.getTime())) return true;
  const day = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return (!p.promoStart || day >= p.promoStart) && (!p.promoEnd || day <= p.promoEnd);
};
const digits4 = value => String(value || '').replace(/\D/g, '').slice(-4);
const normalizeText = value => String(value || '').trim().replace(/\s+/g, '').toLowerCase();
const bankMatches = (txBank, payment) => {
  const bank = normalizeText(txBank);
  if (!bank) return false;
  return [payment.bankName, payment.bankShortName, ...(Array.isArray(payment.bankAlias) ? payment.bankAlias : []), ...(Array.isArray(payment.bankAliases) ? payment.bankAliases : [])]
    .filter(Boolean)
    .some(name => {
      const n = normalizeText(name);
      return n && (bank.includes(n) || n.includes(bank));
    });
};

// Wallet membership is only a candidate filter. It is NEVER sufficient evidence to
// identify a credit card. A rule is accepted only when card last4 or bank identity
// uniquely identifies one configured payment.
const findPaymentMatch = (tx, payments, time) => {
  const wid = walletId(tx);
  if (!wid) return { rule: null, evidence: '' };
  const candidates = payments.filter(p => Array.isArray(p.mobileWallets) && p.mobileWallets.includes(wid) && ruleActiveAt(p, time));
  if (!candidates.length) return { rule: null, evidence: '' };

  const last4 = digits4(tx.cardLast4);
  if (last4) {
    const exact = candidates.filter(p => digits4(p.cardLast4) === last4);
    if (exact.length === 1) return { rule: exact[0], evidence: 'card-last4' };
    if (exact.length > 1) return { rule: null, evidence: 'ambiguous-last4' };
  }

  const bank = String(tx.bank || '').trim();
  if (bank) {
    const exact = candidates.filter(p => bankMatches(bank, p));
    if (exact.length === 1) return { rule: exact[0], evidence: 'bank' };
    if (exact.length > 1) return { rule: null, evidence: 'ambiguous-bank' };
  }

  return { rule: null, evidence: 'insufficient-card-evidence' };
};

const txFromHistory = h => ({
  source: h.source || '',
  bank: h.bank || '',
  cardLast4: h.cardLast4 || '',
  paymentAccount: h.paymentAccount || '',
  paymentMethod: h.paymentMethod || '',
  cardName: h.cardName || ''
});

// Revalidate every auto-captured row using the stricter rule. This repairs old rows
// that were guessed from "the only wallet candidate" and also recalculates payment
// usage so a wrong card no longer keeps the old amount in its used total.
const repairAutoCaptureMatches = (historyMap, settingsMap) => {
  const twSettings = settingsMap.TW || { exchangeRate: 1, payments: [] };
  let payments = Array.isArray(twSettings.payments) ? [...twSettings.payments] : [];
  const tw = Array.isArray(historyMap.TW) ? historyMap.TW : [];
  let repaired = 0;

  historyMap.TW = tw.map(h => {
    if (!h?.autoCaptureKey) return h;
    const time = Number(h.time || Date.now());
    const tx = txFromHistory(h);
    const { rule, evidence } = findPaymentMatch(tx, payments, time);
    const sourceLabel = h.sourceLabel || h.source || '自動擷取';
    const wantedName = rule?.name || `${sourceLabel} · ${paymentDisplay(tx)}`;
    const wantedMatched = rule?.name || '';
    const wantedBank = rule ? (rule.bankShortName || rule.bankName || '') : '';
    const changed = h.name !== wantedName || (h.matchedPayment || '') !== wantedMatched || (h.matchedBank || '') !== wantedBank || (h.matchEvidence || '') !== evidence;
    if (changed) repaired++;
    return { ...h, name: wantedName, matchedPayment: wantedMatched, matchedBank: wantedBank, matchEvidence: evidence };
  });

  payments = payments.map(p => {
    const used = historyMap.TW.reduce((sum, h) => {
      const autoMatched = Boolean(h.autoCaptureKey) && h.matchedPayment === p.name;
      const manualMatched = !h.autoCaptureKey && h.name === p.name;
      return sum + (autoMatched || manualMatched ? Number(h.amount || 0) : 0);
    }, 0);
    return { ...p, used };
  });
  settingsMap.TW = { ...twSettings, payments };
  return { repaired, payments };
};

export function mergeTransactionsIntoTaiwanHistory(transactions = []) {
  let historyMap;
  try { historyMap = JSON.parse(localStorage.getItem('historyMap') || '{"KR":[],"TW":[],"JP":[]}'); }
  catch { historyMap = { KR: [], TW: [], JP: [] }; }
  historyMap.KR = Array.isArray(historyMap.KR) ? historyMap.KR : [];
  historyMap.TW = Array.isArray(historyMap.TW) ? historyMap.TW : [];
  historyMap.JP = Array.isArray(historyMap.JP) ? historyMap.JP : [];

  const settingsMap = readSettingsMap();
  const initialRepair = repairAutoCaptureMatches(historyMap, settingsMap);
  let payments = initialRepair.payments;
  const known = new Set(historyMap.TW.map(x => x.autoCaptureKey).filter(Boolean));
  let added = 0, matched = 0, unmatched = 0;

  transactions.forEach(tx => {
    const key = tx.key || `${tx.source}|${tx.date}|${tx.shop}|${tx.amount}`;
    if (!key || known.has(key)) return;
    const amount = Math.abs(Number(String(tx.amount || '0').replace(/,/g, '')));
    if (!Number.isFinite(amount) || amount <= 0 || !tx.shop) return;
    const time = parseCaptureTime(tx.date);
    const { rule, evidence } = findPaymentMatch(tx, payments, time);
    const sourceLabel = tx.sourceLabel || tx.source || '自動擷取';
    const wid = walletId(tx);
    const display = paymentDisplay(tx);

    historyMap.TW.push({
      time,
      name: rule?.name || `${sourceLabel} · ${display}`,
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
      cardName: tx.cardName || '',
      transactionId: tx.transactionId || '',
      detailChecked: Boolean(tx.detailChecked),
      mobileWallet: wid,
      matchedPayment: rule?.name || '',
      matchedBank: rule?.bankShortName || rule?.bankName || '',
      matchEvidence: evidence,
      promoName: rule?.promoName || '',
      sharedBonusGroup: rule?.sharedBonusGroup || ''
    });
    if (rule) matched++; else unmatched++;
    known.add(key);
    added++;
  });

  historyMap.TW.sort((a, b) => Number(b.time || 0) - Number(a.time || 0));
  const finalRepair = repairAutoCaptureMatches(historyMap, settingsMap);
  localStorage.setItem('historyMap', JSON.stringify(historyMap));
  localStorage.setItem('settingsMap', JSON.stringify(settingsMap));
  return { added, total: historyMap.TW.length, matched, unmatched, repaired: initialRepair.repaired + finalRepair.repaired };
}

export async function syncCapturedTransactionsNow() {
  const before = await AutoCapture.getTransactions();
  const first = mergeTransactionsIntoTaiwanHistory(before.transactions || []);
  await AutoCapture.syncAll();
  return first;
}
export async function importCapturedTransactions() {
  const result = await AutoCapture.getTransactions();
  return mergeTransactionsIntoTaiwanHistory(result.transactions || []);
}
export async function ensureNightlySync() {
  if (!Capacitor.isNativePlatform()) return null;
  return AutoCapture.scheduleNightlySync();
}
export async function exportGoogleWalletDiagnostics(captures = []) {
  const now = new Date();
  const fileName = `PayHelper_GoogleWallet_${now.getFullYear()}${pad(now.getMonth()+1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.json`;
  const data = JSON.stringify({ format:'Pay Helper Google Wallet diagnostic', version:1, exportedAt:now.toISOString(), googleWalletDiagnosticCaptures:captures }, null, 2);
  if (!Capacitor.isNativePlatform()) {
    const blob = new Blob([data], { type:'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href=url; a.download=fileName; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    return { fileName };
  }
  const result = await Filesystem.writeFile({ path:fileName, data, directory:Directory.Cache, encoding:Encoding.UTF8, recursive:true });
  try { await Share.share({ title:'Google Wallet 診斷資料', text:'請儲存或分享這份 Google Wallet 交易記錄診斷 JSON。', files:[result.uri], dialogTitle:'匯出 Google Wallet 診斷' }); }
  catch (error) { console.warn('Diagnostic file created but share sheet closed:', error); }
  return { fileName, uri: result.uri };
}
