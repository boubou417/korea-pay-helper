import React from 'react';
import SurfaceCard from '../common/SurfaceCard';
import SectionHeading from '../common/SectionHeading';

const formatRate = (value) => `${(Number(value || 0) * 100).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')}%`;
const displayPaymentName = (payment) => {
  const name = String(payment?.name || '').trim();
  const bank = String(payment?.bankShortName || payment?.bankName || '').trim();
  if (!bank || !name) return name;
  if (name.includes(bank) || bank.includes(name)) return name;
  return `${bank} ${name}`;
};

export default function AdvisorCard({ payment, exchangeRate, currencySymbol, darkMode }) {
  if (!payment) return null;

  const hasLimit = Number(payment.spendLimit || 0) > 0 || (Number(payment.bonusLimit || 0) > 0 && Number(payment.bonusRate || 0) > 0);
  const spendLimit = Number(payment.spendLimit || 0) || (
    Number(payment.bonusRate || 0) > 0 ? Number(payment.bonusLimit || 0) / Number(payment.bonusRate || 0) : 0
  );
  const used = Number(payment.used || 0);
  const remainTwd = hasLimit ? Math.max(spendLimit - used, 0) : 0;
  const remainLocal = exchangeRate > 0 ? Math.floor(remainTwd / exchangeRate) : 0;
  const totalRate = Number(payment.baseRate || 0) + Number(payment.bonusRate || 0);
  const usedPercent = hasLimit && spendLimit > 0
    ? Math.min(100, Math.round((used / spendLimit) * 100))
    : 0;

  const reasons = [
    `目前可用回饋約 ${formatRate(totalRate)}`,
    hasLimit ? `尚可使用約 ${currencySymbol}${remainLocal.toLocaleString()}` : '回饋額度未設上限',
    hasLimit ? (usedPercent >= 80 ? '接近上限，建議優先使用' : '加碼空間仍充足') : '不需考慮額度上限'
  ];

  return (
    <SurfaceCard darkMode={darkMode}>
      <SectionHeading icon="🤖" title="智慧建議" subtitle="依回饋率、剩餘額度與使用進度判斷" />
      <div className={`rounded-2xl p-3 ${darkMode ? 'bg-gray-700/70' : 'bg-blue-50'}`}>
        <div className="text-sm">
          今天建議先使用 <span className="font-black text-blue-500">{displayPaymentName(payment)}</span>
        </div>
        <div className="mt-2 space-y-1">
          {reasons.map((reason) => (
            <div key={reason} className={`flex items-start gap-2 text-xs ${darkMode ? 'text-gray-300' : 'text-gray-600'}`}>
              <span className="mt-0.5 text-green-500">✓</span>
              <span>{reason}</span>
            </div>
          ))}
        </div>
      </div>
    </SurfaceCard>
  );
}
