import React from 'react';

export default function AppHeader({ darkMode, setDarkMode, deferredPrompt, installPWA, updateReady, applyUpdate, dismissUpdate }) {
  return (
    <div className="mb-4 text-center">
      {deferredPrompt && (
        <button onClick={installPWA} className="mb-2 text-xs px-3 py-1 rounded-full bg-green-500 text-white shadow">
          📲 安裝 App
        </button>
      )}

      {updateReady && (
        <div className="mb-2 flex items-center justify-between text-xs px-3 py-2 rounded-xl bg-orange-500 text-white shadow">
          <span>🔄 有新版本可用</span>
          <div className="flex gap-2">
            <button onClick={applyUpdate} className="bg-white text-orange-500 px-2 py-1 rounded-lg font-semibold">更新</button>
            <button onClick={dismissUpdate} className="bg-orange-600 px-2 py-1 rounded-lg">稍後</button>
          </div>
        </div>
      )}

      <div className="flex justify-end mb-2">
        <button onClick={() => setDarkMode(value => !value)} className="text-xs px-3 py-1 rounded-full bg-gray-200 dark:bg-gray-700 shadow">
          {darkMode ? '☀️ Light' : '🌙 Dark'}
        </button>
      </div>

      <div className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-indigo-600 bg-clip-text text-transparent">Pay Helper</div>
      <div className={`text-xs mt-1 ${darkMode ? 'text-gray-300' : 'text-gray-500'}`}>刷卡回饋最佳化工具</div>
    </div>
  );
}
