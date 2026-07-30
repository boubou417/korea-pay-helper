import React from 'react';

const ITEMS = [
  { key: 'home', icon: '🏠', label: '首頁' },
  { key: 'stats', icon: '📊', label: '統計' },
  { key: 'history', icon: '📜', label: '記錄' },
  { key: 'settings', icon: '⚙️', label: '設定' }
];

export default function BottomNavigation({ tab, setTab }) {
  return (
    <div className="fixed bottom-0 left-0 right-0 z-40 max-w-md mx-auto bg-white dark:bg-gray-900 border-t border-gray-200 dark:border-gray-700 grid grid-cols-4 pb-[env(safe-area-inset-bottom)]">
      {ITEMS.map(item => (
        <button
          key={item.key}
          onClick={() => setTab(item.key)}
          className={`py-2 text-[11px] flex flex-col items-center justify-center transform transition-all duration-200 ${tab === item.key ? 'text-blue-500 font-bold scale-110' : 'text-gray-400 scale-100'} active:scale-95`}
        >
          <div>{item.icon}</div>
          <div className="leading-none">{item.label}</div>
        </button>
      ))}
    </div>
  );
}
