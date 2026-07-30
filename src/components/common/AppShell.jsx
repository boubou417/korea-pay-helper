import React from 'react';

export default function AppShell({ darkMode, children }) {
  return (
    <div className={`min-h-screen p-3 pb-20 max-w-md mx-auto transition-colors duration-300 ${darkMode ? 'bg-gradient-to-br from-gray-900 via-gray-900 to-slate-800 text-white' : 'bg-gradient-to-br from-gray-50 via-blue-50 to-indigo-100 text-black'}`}>
      {children}
    </div>
  );
}
