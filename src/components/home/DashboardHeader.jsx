import React from 'react';

const countryNames = {
  KR: '韓國',
  TW: '台灣',
  JP: '日本'
};

export default function DashboardHeader({ country, flag, estimatedReward, darkMode }) {
  return (
    <div className={`mb-4 overflow-hidden rounded-3xl border p-4 shadow-lg ${
      darkMode
        ? 'border-gray-700 bg-gradient-to-br from-gray-800 via-gray-850 to-slate-900 text-white'
        : 'border-white/70 bg-gradient-to-br from-white via-blue-50 to-indigo-100 text-gray-900'
    }`}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className={`text-xs font-semibold ${darkMode ? 'text-blue-300' : 'text-blue-600'}`}>
            {flag} {countryNames[country] || country}
          </div>
          <div className="mt-1 text-2xl font-black tracking-tight">Pay Helper</div>
          <div className={`mt-1 text-xs ${darkMode ? 'text-gray-400' : 'text-gray-600'}`}>
            打開 App，先看今天最值得使用的支付方式
          </div>
        </div>

        <div className={`shrink-0 rounded-2xl px-3 py-2 text-right ${
          darkMode ? 'bg-white/10' : 'bg-white/75'
        }`}>
          <div className={`text-[10px] ${darkMode ? 'text-gray-300' : 'text-gray-500'}`}>目前還可取得</div>
          <div className="text-lg font-black text-green-500">NT${Math.round(estimatedReward || 0).toLocaleString()}</div>
          <div className={`text-[10px] ${darkMode ? 'text-gray-400' : 'text-gray-500'}`}>預估加碼回饋</div>
        </div>
      </div>
    </div>
  );
}
