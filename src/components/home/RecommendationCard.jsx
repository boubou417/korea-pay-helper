import React from 'react';
import SurfaceCard from '../common/SurfaceCard';
import SectionHeading from '../common/SectionHeading';

const formatPercent = (value) => {
  const number = Number(value || 0) * 100;
  return `${number.toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')}%`;
};

export default function RecommendationCard({ payment, exchangeRate, currencySymbol, darkMode }) {
  if (!payment) {
    return (
      <SurfaceCard darkMode={darkMode} accent>
        <SectionHeading icon="⭐" title="今日推薦支付" subtitle="新增支付方式後，這裡會顯示優先建議" />
        <div className={`rounded-2xl border border-dashed p-4 text-center text-sm ${darkMode ? 'border-gray-600 text-gray-400' : 'border-gray-300 text-gray-500'}`}>
          尚無可推薦的支付方式
        </div>
      </SurfaceCard>
    );
  }

  const limit = payment.spendLimit || (payment.bonusRate > 0 ? payment.bonusLimit / payment.bonusRate : 0);
  const remainTwd = Math.max(limit - Number(payment.used || 0), 0);
  const remainLocal = exchangeRate > 0 ? Math.floor(remainTwd / exchangeRate) : 0;
  const totalRate = Number(payment.baseRate || 0) + Number(payment.bonusRate || 0);

  return (
    <SurfaceCard darkMode={darkMode} accent className="bg-gradient-to-br from-blue-600 to-indigo-700 text-white border-blue-400/30">
      <SectionHeading icon="⭐" title="今日推薦支付" subtitle="依目前回饋率與剩餘額度排序" />
      <div className="flex items-end justify-between gap-4">
        <div className="min-w-0">
          <div className="truncate text-xl font-black">{payment.name}</div>
          <div className="mt-1 text-sm text-blue-100">
            目前最高可用回饋約 {formatPercent(totalRate)}
          </div>
        </div>
        <div className="shrink-0 rounded-2xl bg-white/15 px-3 py-2 text-right backdrop-blur">
          <div className="text-[11px] text-blue-100">剩餘可刷約</div>
          <div className="text-lg font-extrabold">{currencySymbol}{remainLocal.toLocaleString()}</div>
        </div>
      </div>
    </SurfaceCard>
  );
}
