import React from 'react';

const toneClasses = {
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  danger: 'bg-rose-500',
  primary: 'bg-blue-500'
};

export default function PaymentProgress({ value = 0, tone = 'success', darkMode = false }) {
  const safeValue = Math.max(0, Math.min(100, Number(value) || 0));

  return (
    <div
      className={`h-2.5 w-full overflow-hidden rounded-full ${
        darkMode ? 'bg-gray-700' : 'bg-gray-200'
      }`}
      role="progressbar"
      aria-valuemin="0"
      aria-valuemax="100"
      aria-valuenow={Math.round(safeValue)}
    >
      <div
        className={`h-full rounded-full transition-all duration-500 ${
          toneClasses[tone] || toneClasses.success
        }`}
        style={{ width: `${safeValue}%` }}
      />
    </div>
  );
}
