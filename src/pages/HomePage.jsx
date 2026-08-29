import React from 'react';
import SurfaceCard from '../components/common/SurfaceCard';
import AppButton from '../components/common/AppButton';
import SectionHeading from '../components/common/SectionHeading';
import AmountInputCard from '../components/home/AmountInputCard';
import StrategyCard from '../components/strategy/StrategyCard';
import PaymentList from '../components/payment/PaymentList';

export default function HomePage({
  settings,
  currencySymbol,
  darkMode,
  amount,
  setAmount,
  note,
  setNote,
  category,
  setCategory,
  categories,
  setCategories,
  mode,
  setMode,
  inputRef,
  data,
  applyStrategy,
  setSettings,
  openPaymentEditor,
  resetCurrentCountry
}) {
  return (
    <>
      <AmountInputCard
        currencySymbol={currencySymbol}
        amount={amount}
        setAmount={setAmount}
        note={note}
        setNote={setNote}
        category={category}
        setCategory={setCategory}
        categories={categories}
        setCategories={setCategories}
        mode={mode}
        setMode={setMode}
        inputRef={inputRef}
        darkMode={darkMode}
      />

      <StrategyCard
        data={data}
        settings={settings}
        onApply={applyStrategy}
        currencySymbol={currencySymbol}
        darkMode={darkMode}
        mode={mode}
      />

      <PaymentList
        currencySymbol={currencySymbol}
        settings={settings}
        setSettings={setSettings}
        onEdit={openPaymentEditor}
        darkMode={darkMode}
      />

      <SurfaceCard darkMode={darkMode}>
        <SectionHeading icon="🧹" title="資料重置" subtitle="清除目前國家的刷卡使用量與歷史記錄" />
        <AppButton onClick={resetCurrentCountry} variant="danger" className="w-full">重置刷卡記錄</AppButton>
      </SurfaceCard>
    </>
  );
}
