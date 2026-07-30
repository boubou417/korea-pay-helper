import React from 'react';
import SurfaceCard from '../common/SurfaceCard';
import SectionHeading from '../common/SectionHeading';

const formatTWD = (value) => `NT$${Math.round(Number(value || 0)).toLocaleString()}`;

const formatPurchaseTime = (value) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '時間未記錄';

  const pad = (number) => String(number).padStart(2, '0');
  return `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
};

export default function DashboardSummary({ payments = [], history = [], currencySymbol, darkMode, onOpenHistory }) {
  const earned = payments.reduce((sum, payment) => {
    const used = Number(payment.used || 0);
    const base = used * Number(payment.baseRate || 0);
    const bonus = Math.min(used * Number(payment.bonusRate || 0), Number(payment.bonusLimit || 0));
    return sum + base + bonus;
  }, 0);

  const recent = history.slice(0, 3);

  return (
    <div className="grid grid-cols-1 gap-0 sm:grid-cols-2 sm:gap-3">
      <SurfaceCard darkMode={darkMode} accent className="sm:mb-4">
        <SectionHeading icon="💰" title="本期回饋摘要" subtitle="依目前支付使用量估算" />
        <div className="text-3xl font-black tracking-tight text-green-500">{formatTWD(earned)}</div>
        <div className={`mt-3 rounded-2xl px-3 py-2 text-xs ${darkMode ? 'bg-gray-700/70 text-gray-300' : 'bg-gray-100 text-gray-600'}`}>
          共 {payments.length} 種支付方式正在管理
        </div>
      </SurfaceCard>

      <SurfaceCard darkMode={darkMode}>
        <SectionHeading
          icon="🧾"
          title="最近消費"
          subtitle={recent.length ? '最近 3 筆記錄' : '尚無消費記錄'}
          action={recent.length ? (
            <button onClick={onOpenHistory} className="text-xs font-semibold text-blue-500">查看全部</button>
          ) : null}
        />
        {recent.length === 0 ? (
          <div className={`rounded-2xl border border-dashed p-4 text-center text-sm ${darkMode ? 'border-gray-600 text-gray-400' : 'border-gray-300 text-gray-500'}`}>
            使用策略後，最新記錄會顯示在這裡
          </div>
        ) : (
          <div className="space-y-2">
            {recent.map((item, index) => (
              <div key={`${item.time}-${index}`} className={`flex items-center justify-between gap-3 rounded-2xl px-3 py-2 ${darkMode ? 'bg-gray-700/70' : 'bg-gray-50'}`}>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold">{item.note || item.name}</div>
                  <div className="truncate text-[11px] text-gray-400">{item.name} · {item.category || '未分類'}</div>
                  <div className="mt-0.5 text-[10px] text-gray-400">🕒 {formatPurchaseTime(item.time)}</div>
                </div>
                <div className="shrink-0 text-sm font-bold text-green-500">
                  {currencySymbol}{Math.floor(Number(item.amount || 0)).toLocaleString()}
                </div>
              </div>
            ))}
          </div>
        )}
      </SurfaceCard>
    </div>
  );
}
