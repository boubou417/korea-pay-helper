import React from 'react';

export default function SurfaceCard({
  children,
  darkMode = false,
  className = '',
  accent = false,
  as: Component = 'section'
}) {
  const base = darkMode
    ? 'bg-gray-800/95 border-gray-700 text-white'
    : 'bg-white/95 border-white/80 text-gray-900';

  return (
    <Component
      className={`relative overflow-hidden rounded-3xl border p-4 mb-4 shadow-xl backdrop-blur transition-colors ${base} ${className}`}
    >
      {accent && (
        <div className="pointer-events-none absolute inset-x-0 top-0 h-1 bg-gradient-to-r from-blue-500 via-indigo-500 to-purple-500" />
      )}
      {children}
    </Component>
  );
}
