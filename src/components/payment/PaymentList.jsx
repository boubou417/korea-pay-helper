import React, { useState } from 'react';
import SurfaceCard from '../common/SurfaceCard';
import SectionHeading from '../common/SectionHeading';
import PaymentCard from './PaymentCard';

const getCycleText = (resetDay) => {
  if (!resetDay) return '';

  const today = new Date();
  const year = today.getFullYear();
  const month = today.getMonth();
  const day = today.getDate();

  const start = day > resetDay
    ? new Date(year, month, resetDay + 1)
    : new Date(year, month - 1, resetDay + 1);
  const end = day > resetDay
    ? new Date(year, month + 1, resetDay)
    : new Date(year, month, resetDay);

  const format = (date) => `${date.getMonth() + 1}/${date.getDate()}`;
  return `${format(start)} ～ ${format(end)}`;
};

export default function PaymentList({
  settings,
  setSettings,
  onEdit,
  currencySymbol,
  darkMode
}) {
  const [dragIndex, setDragIndex] = useState(null);
  const payments = settings.payments || [];

  const handleDrop = (targetIndex) => {
    if (dragIndex === null || dragIndex === targetIndex) return;

    const newList = [...payments];
    const [item] = newList.splice(dragIndex, 1);
    newList.splice(targetIndex, 0, item);

    setSettings((previous) => ({ ...previous, payments: newList }));
    setDragIndex(null);
  };

  const resetPayment = (payment) => {
    if (!window.confirm(`重置 ${payment.name}？`)) return;

    setSettings((previous) => ({
      ...previous,
      payments: previous.payments.map((item) =>
        item.name === payment.name ? { ...item, used: 0 } : item
      )
    }));
  };

  const deletePayment = (index, payment) => {
    if (!window.confirm(`刪除 ${payment.name}？`)) return;

    setSettings((previous) => ({
      ...previous,
      payments: previous.payments.filter((_, itemIndex) => itemIndex !== index)
    }));
  };

  return (
    <SurfaceCard darkMode={darkMode} accent>
      <SectionHeading
        icon="📊"
        title="使用狀態"
        subtitle="追蹤各支付方式的回饋、額度與帳期"
      />

      {payments.length === 0 ? (
        <div className={`rounded-2xl border border-dashed p-6 text-center text-sm ${darkMode ? 'border-gray-600 text-gray-400' : 'border-gray-300 text-gray-500'}`}>
          尚未新增支付方式
        </div>
      ) : (
        payments.map((payment, index) => (
          <PaymentCard
            key={`${payment.name}-${index}`}
            payment={payment}
            exchangeRate={settings.exchangeRate}
            currencySymbol={currencySymbol}
            darkMode={darkMode}
            cycleText={getCycleText(payment.resetDay)}
            draggable
            onDragStart={() => setDragIndex(index)}
            onDragOver={(event) => event.preventDefault()}
            onDrop={() => handleDrop(index)}
            onEdit={() => onEdit(index)}
            onReset={() => resetPayment(payment)}
            onDelete={() => deletePayment(index, payment)}
          />
        ))
      )}
    </SurfaceCard>
  );
}
