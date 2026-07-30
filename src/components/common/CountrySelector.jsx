import React from 'react';
import SurfaceCard from './SurfaceCard';

const COUNTRIES = [
  { key: 'KR', flag: '🇰🇷', label: '韓國' },
  { key: 'TW', flag: '🇹🇼', label: '台灣' },
  { key: 'JP', flag: '🇯🇵', label: '日本' }
];

export default function CountrySelector({ country, setCountry, darkMode }) {
  return (
    <SurfaceCard darkMode={darkMode}>
      <div className={darkMode ? 'flex gap-2 p-1 rounded-2xl bg-gray-800' : 'flex gap-2 p-1 rounded-2xl bg-gray-100'}>
        {COUNTRIES.map(item => (
          <button
            key={item.key}
            onClick={() => setCountry(item.key)}
            className={country === item.key
              ? 'flex-1 py-2 rounded-xl text-sm font-medium flex items-center justify-center gap-1 bg-gradient-to-r from-blue-500 to-indigo-500 text-white shadow'
              : darkMode
                ? 'flex-1 py-2 rounded-xl text-sm font-medium flex items-center justify-center gap-1 text-gray-200 bg-gray-700 hover:bg-gray-600'
                : 'flex-1 py-2 rounded-xl text-sm font-medium flex items-center justify-center gap-1 text-black bg-gray-200 hover:bg-gray-300'}
          >
            <span className="text-lg">{item.flag}</span><span>{item.label}</span>
          </button>
        ))}
      </div>
    </SurfaceCard>
  );
}
