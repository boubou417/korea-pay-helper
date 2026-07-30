import React from 'react';

export default function SectionHeading({ icon, title, subtitle, action }) {
  return (
    <div className="mb-4 flex items-start justify-between gap-3">
      <div className="flex min-w-0 items-start gap-3">
        {icon && (
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/20 to-indigo-500/20 text-xl">
            {icon}
          </div>
        )}
        <div className="min-w-0">
          <h2 className="truncate text-base font-bold">{title}</h2>
          {subtitle && <p className="mt-0.5 text-xs text-gray-400">{subtitle}</p>}
        </div>
      </div>
      {action}
    </div>
  );
}
