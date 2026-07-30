import React from 'react';
import SurfaceCard from '../common/SurfaceCard';
import SectionHeading from '../common/SectionHeading';

function Segmented({ value, onChange, darkMode }) {
  return (
    <div className={darkMode ? 'grid grid-cols-3 gap-2 mb-3 bg-gray-800 p-1 rounded-2xl' : 'grid grid-cols-3 gap-2 mb-3'}>
      {[
        { key: 'all', label: '全部支付', active: 'from-blue-500 to-indigo-500' },
        { key: 'card', label: '僅刷卡', active: 'from-blue-500 to-indigo-500' },
        { key: 'cash', label: '現金', active: 'from-green-500 to-emerald-500' }
      ].map(item => (
        <button
          key={item.key}
          onClick={() => onChange(item.key)}
          className={value === item.key
            ? `py-3 rounded-2xl transition bg-gradient-to-r ${item.active} text-white`
            : darkMode
              ? 'py-3 rounded-2xl transition bg-gray-700 text-gray-200 hover:bg-gray-600'
              : 'py-3 rounded-2xl transition bg-gray-200 text-black'}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}

export default function AmountInputCard({ amount, setAmount, note, setNote, category, setCategory, categories, setCategories, mode, setMode, inputRef, currencySymbol, darkMode }) {
  return (
    <SurfaceCard darkMode={darkMode} accent>
      <SectionHeading icon="💰" title="消費金額" subtitle="輸入當地幣別金額並選擇付款模式" />
      <Segmented value={mode} onChange={setMode} darkMode={darkMode} />
      <input
        ref={inputRef}
        type="number"
        placeholder={`輸入金額 ${currencySymbol}`}
        value={amount}
        onChange={e => setAmount(e.target.value)}
        className={`w-full border rounded-2xl p-3 text-xl focus:ring-2 focus:ring-blue-400 focus:border-blue-400 outline-none transition shadow-inner ${darkMode ? 'border-gray-600 bg-gray-700 text-gray-100 placeholder-gray-400' : 'border-gray-300 bg-white text-black'}`}
      />
          {/* 類別選擇 */}
      <div className="mt-3 flex gap-2">
        <select
          value={category}
          onChange={e => setCategory(e.target.value)}
          className={`flex-1 border rounded-2xl p-2 ${darkMode ? 'bg-gray-700 text-white border-gray-600' : 'bg-white border-gray-300'}`}
        >
          {categories.map(c => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        <button
          onClick={()=>{
            const name = prompt('新增類別');
            if (!name) return;
            if (!categories.includes(name)) {
              setCategories(prev => [...prev, name]);
            }
          }}
          className="px-2 bg-blue-500 text-white rounded"
        >+
        </button>
        <button
          onClick={()=>{
            if (category === '未分類') return;
            if (!window.confirm('刪除分類?')) return;
            setCategories(prev => prev.filter(c => c !== category));
            setCategory('未分類');
          }}
          className="px-2 bg-red-500 text-white rounded"
        >-
        </button>
      </div>

      <input
        type="text"
        placeholder="消費用途（例如：7-11 / 平板）"
        value={note}
        onChange={e => setNote(e.target.value)}
        className={`mt-3 w-full border rounded-2xl p-3 text-sm focus:ring-2 focus:ring-blue-400 focus:border-blue-400 outline-none transition shadow-inner ${darkMode ? 'border-gray-600 bg-gray-700 text-gray-100 placeholder-gray-400' : 'border-gray-300 bg-white text-black'}`}
      />
    </SurfaceCard>
  );
}

