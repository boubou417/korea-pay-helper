import React from 'react';

const variants = {
  primary: 'bg-gradient-to-r from-blue-500 to-indigo-600 text-white shadow-lg hover:opacity-95',
  success: 'bg-gradient-to-r from-emerald-500 to-green-600 text-white shadow-lg hover:opacity-95',
  danger: 'bg-gradient-to-r from-red-500 to-pink-600 text-white shadow-lg hover:opacity-95',
  secondary: 'bg-gray-200 text-gray-800 hover:bg-gray-300 dark:bg-gray-700 dark:text-gray-100 dark:hover:bg-gray-600',
  ghost: 'bg-transparent text-blue-500 hover:bg-blue-500/10'
};

export default function AppButton({ children, variant = 'primary', className = '', type = 'button', ...props }) {
  return (
    <button
      type={type}
      className={`min-h-[42px] rounded-2xl px-4 py-2 text-sm font-semibold transition active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant] || variants.primary} ${className}`}
      {...props}
    >
      {children}
    </button>
  );
}
