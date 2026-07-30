import React, { useMemo, useState } from 'react';

const COUNTRY_META = {
  KR: { flag: '🇰🇷', name: '韓國', code: 'KRW' },
  JP: { flag: '🇯🇵', name: '日本', code: 'JPY' },
  TW: { flag: '🇹🇼', name: '台灣', code: 'TWD' }
};

function Panel({ children, darkMode, className = '' }) {
  return (
    <div className={`${darkMode ? 'bg-gray-800 border-gray-700 text-white' : 'bg-white border-gray-200 text-gray-900'} border rounded-3xl shadow-xl overflow-hidden ${className}`}>
      {children}
    </div>
  );
}

function Header({ title, onBack, darkMode, action }) {
  return (
    <div className="flex items-center justify-between mb-4 px-1">
      <div className="flex items-center gap-3 min-w-0">
        {onBack && (
          <button onClick={onBack} className="text-blue-500 text-lg px-1" aria-label="返回設定">←</button>
        )}
        <h2 className="font-bold text-xl truncate">{title}</h2>
      </div>
      {action}
    </div>
  );
}

function SettingRow({ icon, title, subtitle, onClick, darkMode, right }) {
  return (
    <button onClick={onClick} className={`w-full flex items-center gap-3 px-4 py-4 text-left transition ${darkMode ? 'active:bg-gray-700' : 'active:bg-gray-100'}`}>
      <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-xl ${darkMode ? 'bg-gray-700' : 'bg-gray-100'}`}>{icon}</div>
      <div className="flex-1 min-w-0">
        <div className="font-semibold">{title}</div>
        {subtitle && <div className="text-xs text-gray-400 mt-0.5 truncate">{subtitle}</div>}
      </div>
      {right || <span className="text-gray-400">›</span>}
    </button>
  );
}

function SettingsHome({ darkMode, setDarkMode, setPage }) {
  return (
    <>
      <Header title="設定" darkMode={darkMode} />
      <Panel darkMode={darkMode}>
        <div className={`divide-y ${darkMode ? 'divide-gray-700' : 'divide-gray-200'}`}>
          <SettingRow icon="🌏" title="國家設定" subtitle="選擇目前使用的國家與幣別" onClick={() => setPage('country')} darkMode={darkMode} />
          <SettingRow icon="💳" title="支付方式管理" subtitle="依國家管理信用卡與行動支付" onClick={() => setPage('payments')} darkMode={darkMode} />
          <SettingRow icon="💱" title="匯率管理" subtitle="設定各國幣別換算為新台幣的匯率" onClick={() => setPage('rates')} darkMode={darkMode} />
          <SettingRow icon="💾" title="資料管理" subtitle="匯出、匯入與清除備份資料" onClick={() => setPage('backup')} darkMode={darkMode} />
          <SettingRow
            icon={darkMode ? '🌙' : '☀️'}
            title="深色模式"
            subtitle={darkMode ? '目前使用深色外觀' : '目前使用淺色外觀'}
            onClick={() => setDarkMode(v => !v)}
            darkMode={darkMode}
            right={<span className={`w-12 h-7 rounded-full p-1 transition ${darkMode ? 'bg-blue-500' : 'bg-gray-300'}`}><span className={`block w-5 h-5 bg-white rounded-full transition-transform ${darkMode ? 'translate-x-5' : ''}`} /></span>}
          />
          <SettingRow icon="ℹ️" title="關於" subtitle="版本資訊與使用說明" onClick={() => setPage('about')} darkMode={darkMode} />
        </div>
      </Panel>
      <div className="text-center text-xs text-gray-400 mt-5">Korea Pay Helper V5 Alpha 2</div>
    </>
  );
}

function CountrySettings({ darkMode, country, setCountry, onBack }) {
  return (
    <>
      <Header title="國家設定" onBack={onBack} darkMode={darkMode} />
      <Panel darkMode={darkMode}>
        <div className={`divide-y ${darkMode ? 'divide-gray-700' : 'divide-gray-200'}`}>
          {Object.entries(COUNTRY_META).map(([code, meta]) => (
            <button key={code} onClick={() => setCountry(code)} className={`w-full flex items-center px-4 py-4 gap-3 ${darkMode ? 'active:bg-gray-700' : 'active:bg-gray-100'}`}>
              <span className="text-2xl">{meta.flag}</span>
              <div className="flex-1 text-left">
                <div className="font-semibold">{meta.name}</div>
                <div className="text-xs text-gray-400">{meta.code}</div>
              </div>
              <span className={`w-5 h-5 rounded-full border-2 flex items-center justify-center ${country === code ? 'border-blue-500' : 'border-gray-400'}`}>
                {country === code && <span className="w-2.5 h-2.5 bg-blue-500 rounded-full" />}
              </span>
            </button>
          ))}
        </div>
      </Panel>
      <div className={`mt-4 p-4 rounded-2xl text-xs ${darkMode ? 'bg-gray-800 text-gray-300' : 'bg-blue-50 text-gray-600'}`}>
        💡 切換國家後，首頁會使用該國家的匯率、支付方式與歷史記錄。
      </div>
    </>
  );
}

function PaymentSettings({ darkMode, settingsMap, onBack, onAdd, onEdit }) {
  const [openCountry, setOpenCountry] = useState('KR');

  return (
    <>
      <Header title="支付方式管理" onBack={onBack} darkMode={darkMode} action={<button onClick={() => onAdd(openCountry)} className="text-sm text-green-500 font-semibold">＋新增</button>} />
      <div className="space-y-3">
        {Object.entries(COUNTRY_META).map(([code, meta]) => {
          const payments = settingsMap[code]?.payments || [];
          const open = openCountry === code;
          return (
            <Panel key={code} darkMode={darkMode}>
              <button onClick={() => setOpenCountry(open ? null : code)} className="w-full flex items-center gap-3 px-4 py-4">
                <span className="text-xl">{meta.flag}</span>
                <span className="font-semibold flex-1 text-left">{meta.name}（{payments.length}）</span>
                <span className="text-gray-400">{open ? '⌃' : '⌄'}</span>
              </button>
              {open && (
                <div className={`border-t ${darkMode ? 'border-gray-700' : 'border-gray-200'}`}>
                  {payments.length === 0 ? (
                    <div className="px-4 py-5 text-sm text-gray-400 text-center">尚未新增支付方式</div>
                  ) : payments.map((p, index) => (
                    <button key={`${p.name}-${index}`} onClick={() => onEdit(code, index)} className={`w-full flex items-center px-4 py-3 gap-3 border-b last:border-b-0 ${darkMode ? 'border-gray-700 active:bg-gray-700' : 'border-gray-100 active:bg-gray-100'}`}>
                      <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${p.type === 'card' ? 'bg-purple-500/20 text-purple-400' : 'bg-blue-500/20 text-blue-400'}`}>{p.type === 'card' ? '💳' : '📱'}</div>
                      <div className="flex-1 text-left min-w-0">
                        <div className="font-medium truncate">{p.name}</div>
                        <div className="text-xs text-gray-400">{((p.baseRate + p.bonusRate) * 100).toFixed(2).replace(/\.00$/, '')}% · 上限 NT${Math.round(p.bonusLimit || 0).toLocaleString()}</div>
                      </div>
                      <span className="text-gray-400">›</span>
                    </button>
                  ))}
                  <button onClick={() => onAdd(code)} className="w-full py-3 text-sm text-blue-500 font-semibold">＋ 新增{meta.name}支付方式</button>
                </div>
              )}
            </Panel>
          );
        })}
      </div>
    </>
  );
}

function RateSettings({ darkMode, settingsMap, onBack, onApplyRate }) {
  const initial = useMemo(() => Object.fromEntries(Object.keys(COUNTRY_META).map(code => [code, String(settingsMap[code]?.exchangeRate ?? '')])), [settingsMap]);
  const [values, setValues] = useState(initial);
  const [savedCode, setSavedCode] = useState(null);

  const save = (code) => {
    const value = Number(values[code]);
    if (!value || Number.isNaN(value)) {
      alert('請輸入正確匯率');
      return;
    }
    onApplyRate(code, value);
    setSavedCode(code);
    window.setTimeout(() => setSavedCode(null), 1500);
  };

  return (
    <>
      <Header title="匯率管理" onBack={onBack} darkMode={darkMode} />
      <div className="space-y-3">
        {Object.entries(COUNTRY_META).map(([code, meta]) => (
          <Panel key={code} darkMode={darkMode} className="p-4">
            <div className="flex items-center gap-3 mb-4">
              <span className="text-2xl">{meta.flag}</span>
              <div className="flex-1">
                <div className="font-semibold">{meta.name} {meta.code}</div>
                <div className="text-xs text-gray-400">1 {meta.code} 換算為新台幣</div>
              </div>
            </div>
            <div className="flex gap-2">
              <input type="number" step="0.001" value={values[code]} onChange={e => setValues(prev => ({ ...prev, [code]: e.target.value }))} className={`flex-1 min-w-0 border rounded-xl px-3 py-2 outline-none focus:ring-2 focus:ring-blue-400 ${darkMode ? 'bg-gray-700 border-gray-600 text-white' : 'bg-white border-gray-300'}`} />
              <button onClick={() => save(code)} className="px-4 rounded-xl bg-blue-500 text-white font-medium">{savedCode === code ? '已儲存' : '修改'}</button>
            </div>
          </Panel>
        ))}
      </div>
    </>
  );
}

function BackupSettings({ darkMode, onBack, handleExport, handleImport, handleClearAll }) {
  return (
    <>
      <Header title="資料管理" onBack={onBack} darkMode={darkMode} />
      <Panel darkMode={darkMode}>
        <div className={`divide-y ${darkMode ? 'divide-gray-700' : 'divide-gray-200'}`}>
          <SettingRow icon="📤" title="匯出備份" subtitle="將所有資料匯出為 JSON 備份檔" onClick={handleExport} darkMode={darkMode} />
          <label className={`w-full flex items-center gap-3 px-4 py-4 cursor-pointer ${darkMode ? 'active:bg-gray-700' : 'active:bg-gray-100'}`}>
            <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-xl ${darkMode ? 'bg-gray-700' : 'bg-gray-100'}`}>📥</div>
            <div className="flex-1 min-w-0">
              <div className="font-semibold">匯入備份</div>
              <div className="text-xs text-gray-400 mt-0.5">從 JSON 備份檔還原所有資料</div>
            </div>
            <span className="text-gray-400">›</span>
            <input type="file" accept="application/json,.json" onChange={handleImport} className="hidden" />
          </label>
          <SettingRow icon="🗑️" title="清除所有資料" subtitle="此操作無法復原，請先匯出備份" onClick={handleClearAll} darkMode={darkMode} right={<span className="text-red-500">›</span>} />
        </div>
      </Panel>
      <div className={`mt-4 p-4 rounded-2xl text-xs ${darkMode ? 'bg-gray-800 text-gray-300' : 'bg-yellow-50 text-gray-600'}`}>
        💡 建議在旅遊前及修改大量支付設定後各備份一次。
      </div>
    </>
  );
}

function AboutSettings({ darkMode, onBack }) {
  return (
    <>
      <Header title="關於" onBack={onBack} darkMode={darkMode} />
      <Panel darkMode={darkMode} className="p-6 text-center">
        <div className="text-5xl mb-3">💳</div>
        <div className="text-xl font-bold">Korea Pay Helper</div>
        <div className="text-sm text-gray-400 mt-1">跨境支付回饋最佳化工具</div>
        <div className="mt-5 text-sm">V5 Alpha 2</div>
        <div className="mt-4 text-xs text-gray-400 leading-6">保留既有最佳拆單、歷史記錄、統計與備份功能，第一階段先重構設定中心。</div>
      </Panel>
    </>
  );
}

export default function SettingsCenter({
  darkMode,
  setDarkMode,
  country,
  setCountry,
  settingsMap,
  handleExport,
  handleImport,
  handleClearAll,
  onAddPayment,
  onEditPayment,
  onApplyRate
}) {
  const [page, setPage] = useState('home');
  const goBack = () => setPage('home');

  if (page === 'country') return <CountrySettings darkMode={darkMode} country={country} setCountry={setCountry} onBack={goBack} />;
  if (page === 'payments') return <PaymentSettings darkMode={darkMode} settingsMap={settingsMap} onBack={goBack} onAdd={onAddPayment} onEdit={onEditPayment} />;
  if (page === 'rates') return <RateSettings darkMode={darkMode} settingsMap={settingsMap} onBack={goBack} onApplyRate={onApplyRate} />;
  if (page === 'backup') return <BackupSettings darkMode={darkMode} onBack={goBack} handleExport={handleExport} handleImport={handleImport} handleClearAll={handleClearAll} />;
  if (page === 'about') return <AboutSettings darkMode={darkMode} onBack={goBack} />;

  return <SettingsHome darkMode={darkMode} setDarkMode={setDarkMode} setPage={setPage} />;
}
