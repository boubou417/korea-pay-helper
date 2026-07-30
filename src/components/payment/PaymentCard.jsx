import React from 'react';
import AppButton from '../common/AppButton';
import PaymentProgress from './PaymentProgress';

const formatPercent = (value) => {
  const number = Number(value || 0) * 100;
  return `${number.toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')}%`;
};

const formatUsagePercent = (value) =>
  Number.isInteger(value) ? String(value) : Number(value).toFixed(1);

export default function PaymentCard({
  payment,
  exchangeRate,
  currencySymbol,
  darkMode,
  cycleText,
  onEdit,
  onReset,
  onDelete,
  draggable,
  onDragStart,
  onDragOver,
  onDrop
}) {
  const rate = Number(exchangeRate || 0);
  const used = Number(payment.used || 0);
  const limit =
    payment.spendLimit ||
    (payment.bonusRate > 0
      ? payment.bonusLimit / payment.bonusRate
      : 999999);

  const remain = Math.max(limit - used, 0);
  const remainLocal = rate > 0 ? Math.floor(remain / rate) : 0;
  const percentRaw = limit > 0 ? (used / limit) * 100 : 0;
  const percent = Math.min(100, Math.round(percentRaw * 10) / 10);

  const earnedBonusUsed = Math.min(
    used * Number(payment.bonusRate || 0),
    Number(payment.bonusLimit || 0)
  );
  const earned = Math.floor(
    used * Number(payment.baseRate || 0) + earnedBonusUsed
  );
  const bonusRemain = Math.round(
    Math.max(Number(payment.bonusLimit || 0) - earnedBonusUsed, 0)
  );
  const spendRemain =
    payment.bonusRate > 0 ? Math.floor(bonusRemain / payment.bonusRate) : 0;
  const spendRemainLocal = rate > 0 ? Math.floor(spendRemain / rate) : 0;

  let tone = 'success';
  let hint = '回饋額度充足';
  let accentText = darkMode ? 'text-emerald-300' : 'text-emerald-600';
  let surface = darkMode
    ? 'bg-gray-800/90 border-gray-700'
    : 'bg-white border-gray-200';

  if (percent >= 90) {
    tone = 'danger';
    hint = '⚠️ 加碼即將用完';
    accentText = darkMode ? 'text-rose-300' : 'text-rose-600';
    surface = darkMode
      ? 'bg-rose-950/50 border-rose-800'
      : 'bg-rose-50 border-rose-200';
  } else if (percent >= 70) {
    tone = 'warning';
    hint = '⏳ 接近上限，建議優先使用';
    accentText = darkMode ? 'text-amber-300' : 'text-amber-600';
    surface = darkMode
      ? 'bg-amber-950/40 border-amber-800'
      : 'bg-amber-50 border-amber-200';
  } else if (payment.type === 'card' && percent < 30) {
    hint = '🔥 回饋空間充足，適合優先刷';
  }

  return (
    <article
      draggable={draggable}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDrop={onDrop}
      className={`mb-3 rounded-3xl border p-4 shadow-sm transition ${surface} ${
        draggable ? 'cursor-move' : ''
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-indigo-600 text-lg text-white shadow">
              {payment.type === 'card' ? '💳' : '📱'}
            </div>
            <div className="min-w-0">
              <div className="truncate text-base font-extrabold">{payment.name}</div>
              <div className={`text-xs ${darkMode ? 'text-gray-400' : 'text-gray-500'}`}>
                {payment.type === 'card' ? '信用卡' : '行動支付'} · 總回饋約{' '}
                {formatPercent(
                  Number(payment.baseRate || 0) + Number(payment.bonusRate || 0)
                )}
              </div>
            </div>
          </div>
        </div>

        <div className={`shrink-0 text-right ${accentText}`}>
          <div className="text-xl font-black">{formatUsagePercent(percent)}%</div>
          <div className="text-[10px] font-medium">額度使用</div>
        </div>
      </div>

      <div className="mt-4">
        <PaymentProgress value={percent} tone={tone} darkMode={darkMode} />
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2">
        <div className={`rounded-2xl p-3 ${darkMode ? 'bg-black/15' : 'bg-gray-50'}`}>
          <div className={`text-[11px] ${darkMode ? 'text-gray-400' : 'text-gray-500'}`}>
            剩餘可刷
          </div>
          <div className="mt-0.5 truncate text-base font-extrabold">
            {currencySymbol}{remainLocal.toLocaleString()}
          </div>
        </div>
        <div className={`rounded-2xl p-3 ${darkMode ? 'bg-black/15' : 'bg-gray-50'}`}>
          <div className={`text-[11px] ${darkMode ? 'text-gray-400' : 'text-gray-500'}`}>
            已拿回饋
          </div>
          <div className="mt-0.5 text-base font-extrabold text-emerald-500">
            NT${earned.toLocaleString()}
          </div>
        </div>
      </div>

      <div className={`mt-3 rounded-2xl px-3 py-2 text-xs ${darkMode ? 'bg-black/10 text-gray-300' : 'bg-gray-50 text-gray-600'}`}>
        <div className="flex justify-between gap-3">
          <span>加碼剩餘</span>
          <span className="font-semibold">NT${bonusRemain.toLocaleString()}</span>
        </div>
        {payment.bonusRate > 0 && (
          <div className="mt-1 flex justify-between gap-3">
            <span>依加碼額度約可再刷</span>
            <span className="font-semibold text-purple-500">
              {currencySymbol}{spendRemainLocal.toLocaleString()}
            </span>
          </div>
        )}
      </div>

      <div className={`mt-3 text-xs font-semibold ${accentText}`}>{hint}</div>

      <div className={`mt-3 flex items-end justify-between gap-3 text-[11px] ${darkMode ? 'text-gray-400' : 'text-gray-500'}`}>
        <div>
          <div>{payment.resetDay ? `每月 ${payment.resetDay} 號結帳` : '未設定結帳日'}</div>
          {cycleText && <div className="mt-0.5 text-blue-400">本期：{cycleText}</div>}
        </div>
        <AppButton variant="secondary" className="min-h-[34px] px-3 py-1 text-xs" onClick={onReset}>
          重置
        </AppButton>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-2">
        <AppButton variant="primary" onClick={onEdit}>✏️ 編輯</AppButton>
        <AppButton variant="danger" onClick={onDelete}>🗑 刪除</AppButton>
      </div>
    </article>
  );
}
