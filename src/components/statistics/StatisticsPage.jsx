import React, { useState } from 'react';

const formatLocal = (num, symbol) => `${symbol}${Math.floor(num).toLocaleString()}`;
const pad = n => String(n).padStart(2, '0');
const dateText = d => `${d.getFullYear()}/${pad(d.getMonth()+1)}/${pad(d.getDate())}`;
const paymentLabel = p => {
  const name=String(p?.name||'').trim();
  const bank=String(p?.bankShortName||p?.bankName||'').trim();
  if(!bank||!name||name.includes(bank)||bank.includes(name)) return name;
  return `${bank} ${name}`;
};

function Card({ children, darkMode }) {
  return <div className={darkMode ? 'bg-gray-800 border-gray-700 text-white backdrop-blur rounded-3xl shadow-xl p-4 mb-4 transition-colors' : 'bg-white border-gray-200 backdrop-blur rounded-3xl shadow-xl p-4 mb-4 transition-colors'}>{children}</div>;
}

function CycleStats({ history, payments, currencySymbol, darkMode }) {
  const [openKey, setOpenKey] = useState(null);
  const [filter, setFilter] = useState('本期');

  const findPayment = h => {
    const candidates = [h?.matchedPayment, h?.name].filter(Boolean);
    return payments.find(p => candidates.includes(p.name)) || null;
  };

  const getCycle = (time, resetDay) => {
    const tx = new Date(time);
    if (!resetDay || Number.isNaN(tx.getTime())) return null;
    const day = Number(resetDay);
    let start, end;
    if (tx.getDate() > day) {
      start = new Date(tx.getFullYear(), tx.getMonth(), day + 1, 0, 0, 0, 0);
      end = new Date(tx.getFullYear(), tx.getMonth() + 1, day, 23, 59, 59, 999);
    } else {
      start = new Date(tx.getFullYear(), tx.getMonth() - 1, day + 1, 0, 0, 0, 0);
      end = new Date(tx.getFullYear(), tx.getMonth(), day, 23, 59, 59, 999);
    }
    return { start, end };
  };

  const currentCycle = resetDay => getCycle(new Date().toISOString(), resetDay);
  const cycleLabel = (cycle, resetDay) => {
    const current = currentCycle(resetDay);
    if (!cycle || !current) return '未判定';
    if (cycle.start.getTime() === current.start.getTime()) return '本期';
    const prevProbe = new Date(current.start.getTime() - 24 * 60 * 60 * 1000);
    const prev = getCycle(prevProbe.toISOString(), resetDay);
    if (prev && cycle.start.getTime() === prev.start.getTime()) return '上期';
    return '更早';
  };

  const walletName = h => h?.wallet || h?.source || h?.paymentMethod || h?.paymentAccount || '其他';
  const groups = {};
  const unresolved = { total:0, items:[], byWallet:{} };

  history.forEach(h => {
    const payment = findPayment(h);
    const resetDay = Number(payment?.resetDay || 0);
    if (!payment || !resetDay) {
      unresolved.total += Number(h.amount || 0);
      unresolved.items.push(h);
      const wallet = walletName(h);
      unresolved.byWallet[wallet] = (unresolved.byWallet[wallet] || 0) + Number(h.amount || 0);
      return;
    }
    const cycle = getCycle(h.time, resetDay);
    if (!cycle) return;
    const key = `${payment.name}|${cycle.start.toISOString().slice(0,10)}`;
    if (!groups[key]) groups[key] = { payment, resetDay, cycle, total:0, items:[], byWallet:{} };
    const g = groups[key];
    g.total += Number(h.amount || 0);
    g.items.push(h);
    const wallet = walletName(h);
    g.byWallet[wallet] = (g.byWallet[wallet] || 0) + Number(h.amount || 0);
  });

  const rank = label => label === '本期' ? 0 : label === '上期' ? 1 : 2;
  const allGroups = Object.entries(groups).map(([key,g]) => ({ key, ...g, label:cycleLabel(g.cycle,g.resetDay) }))
    .sort((a,b) => rank(a.label)-rank(b.label) || b.cycle.start-a.cycle.start || paymentLabel(a.payment).localeCompare(paymentLabel(b.payment)));
  const list = filter === '全部' ? allGroups : allGroups.filter(g => g.label === filter);
  const showUnresolved = filter === '未判定' || filter === '全部';
  const filterCounts = {
    本期: allGroups.filter(g=>g.label==='本期').length,
    上期: allGroups.filter(g=>g.label==='上期').length,
    更早: allGroups.filter(g=>g.label==='更早').length,
    未判定: unresolved.items.length ? 1 : 0,
    全部: allGroups.length + (unresolved.items.length ? 1 : 0)
  };

  const renderWallets = (byWallet, total) => <div className="space-y-2 mt-3">{Object.entries(byWallet).sort((a,b)=>b[1]-a[1]).map(([name,amt])=>{
    const percent=total?Math.round(amt/total*100):0;
    return <div key={name}><div className="flex justify-between text-xs mb-1"><span className={darkMode?'text-gray-300':'text-gray-700'}>{name}</span><span className={darkMode?'text-gray-300':'text-gray-700'}>{formatLocal(amt,currencySymbol)} ({percent}%)</span></div><div className={`w-full h-2 rounded-full overflow-hidden ${darkMode?'bg-gray-700':'bg-gray-200'}`}><div className="h-2 rounded-full bg-blue-500 transition-all duration-500" style={{width:`${percent}%`}} /></div></div>;
  })}</div>;

  const filters=['本期','上期','未判定','更早','全部'];
  return <div className="mb-4">
    <div className="font-bold mb-1 text-lg">📊 帳期統計</div>
    <div className={`text-xs mb-3 ${darkMode?'text-gray-400':'text-gray-500'}`}>預設只顯示本期，避免歷史帳期愈多頁面愈長。需要時再切換查看上期或全部。</div>
    <div className="flex gap-2 overflow-x-auto pb-2 mb-2">{filters.map(x=><button key={x} onClick={()=>{setFilter(x);setOpenKey(null);}} className={`shrink-0 rounded-full px-3 py-1.5 text-xs font-semibold border ${filter===x?'bg-blue-500 border-blue-500 text-white':darkMode?'border-gray-600 bg-gray-800 text-gray-300':'border-gray-200 bg-white text-gray-600'}`}>{x}{filterCounts[x] ? ` ${filterCounts[x]}` : ''}</button>)}</div>
    {list.length===0 && !showUnresolved && <Card darkMode={darkMode}><div className="text-xs text-gray-400">此篩選條件目前沒有資料</div></Card>}
    {list.map(g=>{const isOpen=openKey===g.key;return <div key={g.key} className={`mb-3 p-4 rounded-2xl shadow-lg transition cursor-pointer ${darkMode?'bg-gray-800 border border-gray-700':'bg-white border border-gray-200'}`} onClick={()=>setOpenKey(isOpen?null:g.key)}>
      <div className="flex justify-between gap-3 items-start"><div className="min-w-0"><div className="font-bold text-base break-words">💳 {paymentLabel(g.payment)}</div><div className="flex items-center gap-2 mt-1 flex-wrap"><span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${g.label==='本期'?'bg-purple-500 text-white':g.label==='上期'?'bg-indigo-500 text-white':darkMode?'bg-gray-600 text-gray-200':'bg-gray-200 text-gray-700'}`}>{g.label}</span><span className={`text-xs ${darkMode?'text-gray-400':'text-gray-500'}`}>結帳日：每月 {g.resetDay} 日</span></div></div><div className="text-right shrink-0"><div className="font-bold text-green-500 text-lg">{formatLocal(g.total,currencySymbol)}</div><div className={`text-[11px] ${darkMode?'text-gray-400':'text-gray-500'}`}>{g.items.length} 筆</div></div></div>
      <div className={`mt-3 px-3 py-2 rounded-xl text-sm ${darkMode?'bg-gray-700 text-gray-200':'bg-gray-50 text-gray-700'}`}><span className="font-semibold">帳期：</span>{dateText(g.cycle.start)} ～ {dateText(g.cycle.end)}</div>
      {renderWallets(g.byWallet,g.total)}
      <div className={`text-center text-[11px] mt-3 ${darkMode?'text-gray-500':'text-gray-400'}`}>{isOpen?'▲ 收合交易明細':'▼ 點擊查看交易明細'}</div>
      {isOpen&&<div className={`mt-2 pt-2 border-t text-xs space-y-2 ${darkMode?'border-gray-700':'border-gray-200'}`}>{g.items.slice().sort((a,b)=>new Date(b.time)-new Date(a.time)).map((h,i)=><div key={`${h.time}-${i}`} className="flex justify-between gap-3"><span className="min-w-0 break-words">{new Date(h.time).toLocaleDateString()}　{h.note||walletName(h)}</span><span className="shrink-0">{formatLocal(h.amount,currencySymbol)}</span></div>)}</div>}
    </div>})}
    {showUnresolved&&unresolved.items.length>0&&<div className={`mb-3 p-4 rounded-2xl shadow-lg border ${darkMode?'bg-amber-950/30 border-amber-800 text-white':'bg-amber-50 border-amber-200'}`}>
      <div className="flex justify-between gap-3"><div><div className="font-bold">⚠️ 未判定信用卡</div><div className={`text-xs mt-1 ${darkMode?'text-amber-300':'text-amber-700'}`}>無法配對信用卡或尚未設定結帳日，因此不列入本期／上期。</div></div><div className="text-right shrink-0"><div className="font-bold text-amber-500">{formatLocal(unresolved.total,currencySymbol)}</div><div className="text-[11px]">{unresolved.items.length} 筆</div></div></div>
      {renderWallets(unresolved.byWallet,unresolved.total)}
    </div>}
  </div>;
}

function CategoryStats({ history, currencySymbol, darkMode }) {
  const grouped={};history.forEach(h=>{const key=h.category||'未分類';grouped[key]=(grouped[key]||0)+Number(h.amount||0);});
  const list=Object.entries(grouped).sort((a,b)=>b[1]-a[1]);if(!list.length)return null;const total=list.reduce((s,[,v])=>s+v,0)||1;
  return <Card darkMode={darkMode}><div className="font-bold mb-2">📊 消費分類統計</div><div className="space-y-3">{list.map(([name,amt])=>{const percent=Math.round(amt/total*100);return <div key={name}><div className="flex justify-between text-xs mb-1"><span>{name}</span><span>{formatLocal(amt,currencySymbol)} ({percent}%)</span></div><div className={`w-full h-3 rounded-full overflow-hidden ${darkMode?'bg-gray-700':'bg-gray-200'}`}><div className="h-3 rounded-full bg-blue-500 transition-all duration-500" style={{width:`${percent}%`}} /></div></div>;})}</div></Card>;
}

export default function StatisticsPage({ history, payments, currencySymbol, darkMode }) {
  return <><CycleStats history={history} payments={payments} currencySymbol={currencySymbol} darkMode={darkMode}/><CategoryStats history={history} currencySymbol={currencySymbol} darkMode={darkMode}/></>;
}
