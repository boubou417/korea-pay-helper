import React, { useEffect, useState } from 'react';
import SurfaceCard from '../common/SurfaceCard';
import AppButton from '../common/AppButton';
import SectionHeading from '../common/SectionHeading';
import { formatLocal, formatTWD } from '../../utils/formatters';

export default function StrategyCard({ data, settings, onApply, currencySymbol, darkMode, mode }) {
  const [custom, setCustom] = useState([]);

  useEffect(() => {
    if (data) setCustom(data.bestPlan.steps.map(s => ({ ...s })));
  }, [data]);

  if (!data) return null;

  // ⭐ 現金模式：只顯示一個按鈕（不顯示任何策略/回饋）
  if (mode === 'cash') {
    return (
      <SurfaceCard darkMode={darkMode}>
        <div className="text-center font-bold mb-2">現金記帳</div>
        <AppButton onClick={()=>onApply([])} variant="success" className="w-full">使用此策略</AppButton>
      </SurfaceCard>
    );
  }

  const calcCustom = () => {
    const rate = settings.exchangeRate;
    let total = 0;

    custom.forEach(s => {
      const p = settings.payments.find(x => x.name === s.name);
      if (!p) return;
      const spend = s.amount * rate;
      const limit = p.spendLimit || (p.bonusRate > 0 ? p.bonusLimit / p.bonusRate : 999999);
      const remain = Math.max(limit - p.used, 0);
      const eligible = Math.min(spend, remain);
      total += eligible * p.baseRate + Math.min(eligible * p.bonusRate, p.bonusLimit);
    });

    return total;
  };

  const customReward = Math.floor(calcCustom());
  const bestReward = Math.floor(data.bestPlan.reward);
  const noSplitReward = Math.floor(data.noSplitBest.reward);

  const diffCustomVsBest = bestReward - customReward;
  const diffSplitVsNoSplit = bestReward - noSplitReward;

  return (
    <SurfaceCard darkMode={darkMode} accent>
      <SectionHeading icon="🏆" title="最佳支付策略" subtitle="比較拆單與單一支付的預估回饋" />
      <div className="text-center font-bold">最佳策略（拆單）</div>
      <div className="text-xl text-center">{formatTWD(bestReward)}</div>

      
      

      
      <div className="mt-4 text-center font-semibold text-sm">不拆單最佳</div>
      <div className="text-center">{formatTWD(noSplitReward)}</div>
      {data.noSplitBest.steps.map((s,i)=>(
        <div key={'ns-'+i} className="rounded-xl border p-3 mb-2">
          <div className="font-semibold truncate">
            <div className="truncate font-medium">
                {s.name}
            </div>
            <div className="text-xs text-gray-400">{formatLocal(Number(s.amount||0), currencySymbol)}</div>
          </div>
          <button
            onClick={()=>onApply(data.noSplitBest.steps)}
            className="shrink-0 text-xs bg-green-500 text-white px-3 py-2 rounded-lg"
          >
            使用此策略
          </button>
        </div>
      ))}

      
      {diffSplitVsNoSplit > 0 ? (
        <div className="text-center text-green-600 text-sm mt-1">拆單多賺 {formatTWD(diffSplitVsNoSplit)}</div>
      ) : (
        <div className="text-center text-gray-200 text-sm mt-1">拆單無優勢</div>
      )}

      
      <div className="mt-4 text-xs text-gray-300">手動調整</div>

{custom.map((s,i)=>(
  <div
    key={i}
    className="flex items-center gap-2 w-full overflow-hidden mb-2"
  >
    <select
      value={s.name}
      onChange={e=>{
        const val = e.target.value;
        setCustom(prev=>prev.map((x,idx)=>idx===i?{...x,name:val}:x));
      }}
      className={`w-28 shrink-0 border rounded-lg p-1
      focus:ring-2 focus:ring-blue-400 focus:border-blue-400 outline-none transition
      ${darkMode
        ? "border-gray-600 bg-gray-700 text-gray-100"
        : "border-gray-300 bg-white text-black"}`}
    >
      {settings.payments.map(p=>(
        <option key={p.name} value={p.name}>
          {p.name}
        </option>
      ))}
    </select>

    <input
      type="number"
      value={s.amount}
      onChange={e=>{
        const v = Number(e.target.value||0);
        setCustom(prev=>prev.map((x,idx)=>idx===i?{...x,amount:v}:x));
      }}
      className={`flex-1 min-w-0 border rounded-lg p-1
      focus:ring-2 focus:ring-blue-400 focus:border-blue-400 outline-none transition
      ${darkMode
        ? "border-gray-600 bg-gray-700 text-gray-100"
        : "border-gray-300 bg-white text-black"}`}
    />

    <button
      onClick={()=>onApply([s])}
      className="shrink-0 text-xs bg-blue-500 hover:bg-blue-600 text-white px-3 py-2 rounded-lg shadow transition"
    >
      使用
    </button>
  </div>
))}

      <div className="text-center text-green-600 mt-2">手動回饋：{formatTWD(customReward)}</div>

      {diffCustomVsBest > 0 && (
        <div className="text-center text-red-500 text-sm">少拿 {formatTWD(diffCustomVsBest)}</div>
      )}

      <AppButton onClick={()=>onApply(custom)} variant="success" className="mt-3 w-full">套用此策略</AppButton>
    </SurfaceCard>
  );
}

