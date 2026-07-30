import React, { useEffect, useState } from 'react';

export default function PaymentModal({ visible, onClose, onSave, editing, darkMode }) {
  const [name, setName] = useState('');
  const [type, setType] = useState('card');
  const [baseRate, setBaseRate] = useState('');
  const [bonusRate, setBonusRate] = useState('');
  const [bonusLimit, setBonusLimit] = useState('');
  const [spendLimit, setSpendLimit] = useState('');
  const [resetDay, setResetDay] = useState('');
  const [error, setError] = useState('');
  const [fieldError, setFieldError] = useState({ name:false, baseRate:false, bonusRate:false, bonusLimit:false, spendLimit:false, resetDay:false });

  useEffect(() => {
    if (editing) {
      setName(editing.name);
      setType(editing.type);
      setBaseRate(editing.baseRate * 100);
      setBonusRate(editing.bonusRate * 100);
      setBonusLimit(editing.bonusLimit);
      setSpendLimit(editing.spendLimit || '');
      setResetDay(editing.resetDay || '');
    } else {
      setName(''); setBaseRate(''); setBonusRate(''); setBonusLimit(''); setSpendLimit(''); setResetDay(''); setType('card');
    }
  }, [editing]);

  if (!visible) return null;

  return (
    <div className="fixed inset-0 bg-black backdrop-blur-sm flex items-center justify-center z-50">
      <div className={`${darkMode ? 'bg-gray-800 text-white border-gray-700' : 'bg-white text-black border-gray-200'} backdrop-blur rounded-3xl shadow-2xl w-80 p-5 transition-colors`}>
        <div className="text-lg font-bold mb-3 text-center">
          {editing?'編輯':'新增'}支付方式
        </div>

        <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>支付類型（影響篩選：僅刷卡）</div>
        <select value={type} onChange={e=>setType(e.target.value)} className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`}>
          <option value="card">信用卡</option>
          <option value="mobile">行動支付</option>
        </select>
      </div>

        <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>名稱（顯示用，如：icash Pay、星展 eco）</div>
        <input value={name} onChange={e=>{
            setName(e.target.value);
            setError('');
            setFieldError(prev=>({...prev,name:false}));
          }} className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`} />
      </div>
        <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>基本回饋（輸入%數，例如 1 = 1%，通常無上限）</div>
        <input value={baseRate} onChange={e=>{
            const val = e.target.value;
            if (/[^0-9.]/.test(val)) return;
            setBaseRate(val);
            setError('');
            setFieldError(prev=>({...prev,baseRate:false}));
          }} className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`} />
      </div>
        <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>加碼回饋（輸入%數，例如 4 = 4%，有上限）</div>
        <input value={bonusRate} onChange={e=>{
            const val = e.target.value;
            if (/[^0-9.]/.test(val)) return;
            setBonusRate(val);
            setFieldError(prev=>({...prev,bonusRate:false}));
          }} className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`} />
      </div>
        <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>加碼上限（台幣，例如 500 = 最多拿 500 回饋）</div>
        <input value={bonusLimit} onChange={e=>{
            const val = e.target.value;
            if (/[^0-9.]/.test(val)) return;
            setBonusLimit(val);
            setFieldError(prev=>({...prev,bonusLimit:false}));
          }} className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`} />
      </div>
        <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>刷卡上限（台幣，可空；不填會自動依加碼回推）</div>
        <input value={spendLimit} onChange={e=>{
            const val = e.target.value;
            if (/[^0-9.]/.test(val)) return;
            setSpendLimit(val);
            setFieldError(prev=>({...prev,spendLimit:false}));
          }} className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`} />
      </div>

      <div className="mb-2">
        <div className={`${darkMode ? 'text-gray-300' : 'text-gray-600'} text-xs mb-1`}>結帳日（1~31，選填）</div>
        <input
          type="number"
          min="1"
          max="31"
          value={resetDay}
          onChange={e=>{
            const val = e.target.value;
            if (/[^0-9]/.test(val)) return;
            setResetDay(val);
            setFieldError(prev=>({...prev,resetDay:false}));
          }}
          className={`border p-2 w-full rounded-xl focus:ring-2 outline-none transition ${false ? 'border-red-500 focus:ring-red-400' : 'focus:ring-blue-400 focus:border-blue-400'} ${darkMode ? 'bg-gray-700 text-gray-100' : 'bg-white text-black'} ${false ? '' : (darkMode ? 'border-gray-600' : 'border-gray-300')}`}
          placeholder="例如：10"
        />
      </div>

        <div className="flex gap-2 mt-4">
          <button onClick={()=>{ setError(''); onClose(); }} className={`flex-1 p-2 rounded-xl transition font-medium ${darkMode ? 'bg-gray-700 text-gray-200 hover:bg-gray-600' : 'bg-gray-200 text-black hover:bg-gray-300'}`}>取消</button>
          <button onClick={()=>{
            // 驗證
            if (!name) {
              setError('請輸入名稱');
              setFieldError(prev=>({...prev,name:true}));
              return;
            }
            if (baseRate === '' || isNaN(Number(baseRate)) || Number(baseRate) < 0) {
              setError('請輸入正確的基本回饋（例如 1 = 1%）');
              setFieldError(prev=>({...prev,baseRate:true}));
              return;
            }

            setError('');
            onSave({
              name,
              type,
              baseRate:Number(baseRate)/100,
              bonusRate:Number(bonusRate||0)/100,
              bonusLimit:Number(bonusLimit||0),
              spendLimit:spendLimit?Number(spendLimit):undefined,
              resetDay: resetDay ? Number(resetDay) : undefined,
              used: editing?.used || 0
            });
          }} className="flex-1 bg-gradient-to-r from-blue-500 to-indigo-500 text-white p-2 rounded-xl shadow hover:opacity-90 transition font-medium">儲存</button>
        {error && (
          <div className="text-red-500 text-xs mt-2 text-center">
            {error}
          </div>
        )}
      </div>
    </div>
    </div>
  );
}

