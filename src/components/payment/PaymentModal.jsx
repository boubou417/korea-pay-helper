import React, { useEffect, useMemo, useState } from 'react';

const MOBILE_WALLETS = [
  ['line_pay','LINE Pay'], ['google_pay','Google Pay'], ['apple_pay','Apple Pay'],
  ['jkopay','街口支付'], ['pi','Pi 拍錢包'], ['taiwan_pay','台灣 Pay'],
  ['easy_wallet','悠遊付'], ['pxpay_plus','全支付'], ['plus_pay','全盈+PAY'],
  ['icash_pay','icash Pay'], ['open_wallet','OPEN錢包'], ['samsung_pay','Samsung Pay']
];

const BANKS = [
  ['CHB','彰化銀行','彰銀'], ['CTBC','中國信託','中信'], ['CATHAY','國泰世華','國泰'],
  ['TAISHIN','台新銀行','台新'], ['E.SUN','玉山銀行','玉山'], ['FUBON','台北富邦','北富銀'],
  ['SINOPAC','永豐銀行','永豐'], ['FIRST','第一銀行','一銀'], ['MEGA','兆豐銀行','兆豐'],
  ['HNCB','華南銀行','華南'], ['KGI','凱基銀行','凱基'], ['DBS','星展銀行','星展'],
  ['ESUN_OTHER','其他銀行','其他']
];

export default function PaymentModal({ visible, onClose, onSave, editing, darkMode }) {
  const [name, setName] = useState('');
  const [type, setType] = useState('card');
  const [bankCode, setBankCode] = useState('');
  const [bankName, setBankName] = useState('');
  const [bankShortName, setBankShortName] = useState('');
  const [cardLast4, setCardLast4] = useState('');
  const [baseRate, setBaseRate] = useState('');
  const [bonusRate, setBonusRate] = useState('');
  const [bonusLimit, setBonusLimit] = useState('');
  const [spendLimit, setSpendLimit] = useState('');
  const [resetDay, setResetDay] = useState('');
  const [wallets, setWallets] = useState([]);
  const [otherWallet, setOtherWallet] = useState('');
  const [limitCycle, setLimitCycle] = useState('monthly');
  const [minAmount, setMinAmount] = useState('');
  const [promoName, setPromoName] = useState('');
  const [promoStart, setPromoStart] = useState('');
  const [promoEnd, setPromoEnd] = useState('');
  const [registrationRequired, setRegistrationRequired] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (editing) {
      setName(editing.name || ''); setType(editing.type || 'card');
      setBankCode(editing.bankCode || ''); setBankName(editing.bankName || ''); setBankShortName(editing.bankShortName || '');
      setCardLast4(editing.cardLast4 || ''); setBaseRate(Number(editing.baseRate || 0) * 100);
      setBonusRate(Number(editing.bonusRate || 0) * 100); setBonusLimit(editing.bonusLimit || '');
      setSpendLimit(editing.spendLimit || ''); setResetDay(editing.resetDay || '');
      setWallets(editing.mobileWallets || []); setOtherWallet(editing.otherWallet || '');
      setLimitCycle(editing.limitCycle || 'monthly'); setMinAmount(editing.minAmount || '');
      setPromoName(editing.promoName || ''); setPromoStart(editing.promoStart || ''); setPromoEnd(editing.promoEnd || '');
      setRegistrationRequired(Boolean(editing.registrationRequired));
    } else {
      setName(''); setType('card'); setBankCode(''); setBankName(''); setBankShortName(''); setCardLast4('');
      setBaseRate(''); setBonusRate(''); setBonusLimit(''); setSpendLimit(''); setResetDay(''); setWallets([]); setOtherWallet('');
      setLimitCycle('monthly'); setMinAmount(''); setPromoName(''); setPromoStart(''); setPromoEnd(''); setRegistrationRequired(false);
    }
    setError('');
  }, [editing, visible]);

  const calculatedSpend = useMemo(() => {
    const r = Number(bonusRate || 0) / 100, l = Number(bonusLimit || 0);
    return r > 0 && l > 0 ? Math.floor((l / r) * 100) / 100 : 0;
  }, [bonusRate, bonusLimit]);

  if (!visible) return null;
  const cls = `border p-2 w-full rounded-xl outline-none ${darkMode ? 'bg-gray-700 text-gray-100 border-gray-600' : 'bg-white text-black border-gray-300'}`;
  const label = `${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`;
  const toggleWallet = id => setWallets(prev => prev.includes(id) ? prev.filter(x=>x!==id) : [...prev,id]);
  const chooseBank = code => {
    const b = BANKS.find(x=>x[0]===code); setBankCode(code);
    if (b) { setBankName(b[1]); setBankShortName(b[2]); }
  };

  return <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-3">
    <div className={`${darkMode?'bg-gray-800 text-white':'bg-white text-black'} rounded-3xl shadow-2xl w-full max-w-md p-5 max-h-[92vh] overflow-y-auto`}>
      <div className="text-lg font-bold mb-4 text-center">{editing?'編輯':'新增'}支付方式</div>
      <div className="mb-3"><div className={label}>類型</div><select className={cls} value={type} onChange={e=>setType(e.target.value)}><option value="card">信用卡</option><option value="mobile">行動支付優惠</option></select></div>

      <div className="grid grid-cols-2 gap-2 mb-3">
        <div><div className={label}>銀行</div><select className={cls} value={bankCode} onChange={e=>chooseBank(e.target.value)}><option value="">選擇銀行</option>{BANKS.map(b=><option key={b[0]} value={b[0]}>{b[1]}（{b[2]}）</option>)}</select></div>
        <div><div className={label}>卡片末四碼</div><input className={cls} maxLength={4} value={cardLast4} onChange={e=>setCardLast4(e.target.value.replace(/\D/g,'').slice(0,4))} placeholder="例如 0102" /></div>
      </div>
      <div className="mb-3"><div className={label}>卡片／支付方案名稱</div><input className={cls} value={name} onChange={e=>setName(e.target.value)} placeholder="例如 My樂卡" /></div>

      {type === 'mobile' && <>
        <div className="mb-3"><div className={label}>適用行動支付（可多選）</div><div className="grid grid-cols-2 gap-2">{MOBILE_WALLETS.map(([id,n])=><label key={id} className={`${darkMode?'bg-gray-700':'bg-gray-100'} rounded-xl px-3 py-2 text-sm flex gap-2 items-center`}><input type="checkbox" checked={wallets.includes(id)} onChange={()=>toggleWallet(id)} />{n}</label>)}</div></div>
        <div className="mb-3"><div className={label}>其他行動支付（選填）</div><input className={cls} value={otherWallet} onChange={e=>setOtherWallet(e.target.value)} placeholder="例如指定銀行錢包" /></div>
      </>}

      <div className="grid grid-cols-2 gap-2 mb-3">
        <div><div className={label}>基本回饋 %</div><input className={cls} inputMode="decimal" value={baseRate} onChange={e=>/^\d*\.?\d*$/.test(e.target.value)&&setBaseRate(e.target.value)} /></div>
        <div><div className={label}>加碼回饋 %</div><input className={cls} inputMode="decimal" value={bonusRate} onChange={e=>/^\d*\.?\d*$/.test(e.target.value)&&setBonusRate(e.target.value)} /></div>
        <div><div className={label}>加碼回饋上限 NT$</div><input className={cls} inputMode="decimal" value={bonusLimit} onChange={e=>/^\d*\.?\d*$/.test(e.target.value)&&setBonusLimit(e.target.value)} /></div>
        <div><div className={label}>最低單筆金額</div><input className={cls} inputMode="decimal" value={minAmount} onChange={e=>/^\d*\.?\d*$/.test(e.target.value)&&setMinAmount(e.target.value)} /></div>
      </div>
      {calculatedSpend>0 && <div className={`${darkMode?'bg-blue-950':'bg-blue-50'} rounded-xl p-3 text-sm mb-3`}>依加碼 {bonusRate}% / 上限 NT${bonusLimit}，可加碼消費約 <b>NT${calculatedSpend.toLocaleString()}</b>。多個已勾選行動支付共用此上限。</div>}
      <div className="grid grid-cols-2 gap-2 mb-3">
        <div><div className={label}>消費上限（可空白自動計算）</div><input className={cls} value={spendLimit} onChange={e=>/^\d*\.?\d*$/.test(e.target.value)&&setSpendLimit(e.target.value)} /></div>
        <div><div className={label}>上限週期</div><select className={cls} value={limitCycle} onChange={e=>setLimitCycle(e.target.value)}><option value="monthly">每月</option><option value="billing">每期帳單</option><option value="campaign">活動期間</option><option value="none">無上限週期</option></select></div>
      </div>
      <div className="grid grid-cols-2 gap-2 mb-3"><div><div className={label}>活動開始</div><input type="date" className={cls} value={promoStart} onChange={e=>setPromoStart(e.target.value)} /></div><div><div className={label}>活動結束</div><input type="date" className={cls} value={promoEnd} onChange={e=>setPromoEnd(e.target.value)} /></div></div>
      <div className="mb-3"><div className={label}>優惠群組名稱</div><input className={cls} value={promoName} onChange={e=>setPromoName(e.target.value)} placeholder="例如 2026 H2 6Pay；勾選支付共用額度" /></div>
      <label className="flex items-center gap-2 text-sm mb-3"><input type="checkbox" checked={registrationRequired} onChange={e=>setRegistrationRequired(e.target.checked)} />此優惠需要登錄</label>
      <div className="mb-3"><div className={label}>結帳／重置日（1~31，選填）</div><input type="number" min="1" max="31" className={cls} value={resetDay} onChange={e=>setResetDay(e.target.value)} /></div>

      {error && <div className="text-red-500 text-sm mb-2">{error}</div>}
      <div className="flex gap-2"><button className={`${darkMode?'bg-gray-700':'bg-gray-200'} flex-1 p-2 rounded-xl`} onClick={onClose}>取消</button><button className="flex-1 bg-gradient-to-r from-blue-500 to-indigo-500 text-white p-2 rounded-xl" onClick={()=>{
        if (!name.trim()) return setError('請輸入卡片／方案名稱');
        if (baseRate==='' || Number.isNaN(Number(baseRate))) return setError('請輸入基本回饋');
        if (type==='mobile' && wallets.length===0 && !otherWallet.trim()) return setError('請至少選擇一個行動支付');
        const finalSpend = spendLimit ? Number(spendLimit) : (calculatedSpend || undefined);
        onSave({ ...editing, name:name.trim(), type, bankCode, bankName, bankShortName, cardLast4,
          baseRate:Number(baseRate||0)/100, bonusRate:Number(bonusRate||0)/100, bonusLimit:Number(bonusLimit||0),
          spendLimit:finalSpend, resetDay:resetDay?Number(resetDay):undefined, mobileWallets:wallets, otherWallet:otherWallet.trim(),
          limitCycle, minAmount:minAmount?Number(minAmount):undefined, promoName:promoName.trim(), promoStart, promoEnd,
          registrationRequired, sharedBonusGroup: promoName.trim() || undefined, used:editing?.used||0 });
        setError('');
      }}>儲存</button></div>
    </div>
  </div>;
}
