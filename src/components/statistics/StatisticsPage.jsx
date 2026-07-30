import React, { useState } from 'react';

const formatLocal = (num, symbol) => `${symbol}${Math.floor(num).toLocaleString()}`;

function Card({ children, darkMode }) {
  return (
    <div className={darkMode ? 'bg-gray-800 border-gray-700 text-white backdrop-blur rounded-3xl shadow-xl p-4 mb-4 transition-colors' : 'bg-white border-gray-200 backdrop-blur rounded-3xl shadow-xl p-4 mb-4 transition-colors'}>
      {children}
    </div>
  );
}

function CycleStats({ history, payments, currencySymbol, darkMode }) {
  const [openKey, setOpenKey] = useState(null);

  const getResetDay = (name) => payments.find(p=>p.name===name)?.resetDay;

  const getKey = (time, resetDay) => {
    const d = new Date(time);
    const y = d.getFullYear();
    const m = d.getMonth();
    const day = d.getDate();

    if (!resetDay) return `${y}-${m+1}`;
    if (day > resetDay) return `${y}-${m+1}-cycle`;
    const prev = new Date(y, m-1);
    return `${prev.getFullYear()}-${prev.getMonth()+1}-cycle`;
  };

  const getCycleText = (key, samplePayment) => {
    const resetDay = samplePayment?.resetDay;
    if (!resetDay) return key;

    const now = new Date();
    const y = now.getFullYear();
    const m = now.getMonth();
    const d = now.getDate();

    let start, end;
    if (d > resetDay) {
      start = new Date(y, m, resetDay + 1);
      end = new Date(y, m + 1, resetDay);
    } else {
      start = new Date(y, m - 1, resetDay + 1);
      end = new Date(y, m, resetDay);
    }

    const fmt = (dt) => `${dt.getMonth()+1}/${dt.getDate()}`;
    return `${fmt(start)} ～ ${fmt(end)}`;
  };

  const grouped = {};

  history.forEach(h=>{
    const resetDay = getResetDay(h.name);
    const key = getKey(h.time, resetDay);

    if(!grouped[key]) grouped[key]={ total:0, byPayment:{}, sampleName: h.name };

    grouped[key].total += Number(h.amount||0);

    if(!grouped[key].byPayment[h.name]) {
      grouped[key].byPayment[h.name] = 0;
    }
    grouped[key].byPayment[h.name] += Number(h.amount||0);
  });

  const list = Object.entries(grouped).slice(0,5);

  return (
    <div className="mb-4">
      <div className="font-bold mb-2 text-lg">📊 帳期統計</div>

      {list.length===0 && (
        <Card darkMode={darkMode}>
          <div className="text-xs text-gray-400">尚無資料</div>
        </Card>
      )}

      {list.map(([k,v])=> {
        const samplePayment = payments.find(p=>p.name===v.sampleName);
        const cycleText = getCycleText(k, samplePayment);
        const isOpen = openKey === k;

        return (
          <div key={k} className={`mb-3 p-3 rounded-2xl shadow-lg transition cursor-pointer ${darkMode ? 'bg-gray-800 border border-gray-700' : 'bg-white border border-gray-200'}`} onClick={()=>setOpenKey(isOpen?null:k)}>

            <div className="flex justify-between items-center mb-2">
              <div className="text-sm font-semibold">{cycleText}</div>
              <div className="text-base font-bold text-green-500">
                {formatLocal(v.total, currencySymbol)}
              </div>
            </div>

            <div className="space-y-2">
              {Object.entries(v.byPayment).map(([name, amt], idx) => {
                const percent = v.total ? Math.round((amt / v.total) * 100) : 0;
                const colors = ['from-blue-400 to-blue-600','from-green-400 to-green-600','from-purple-400 to-purple-600','from-pink-400 to-pink-600'];

                return (
                  <div key={name}>
                    <div className="flex justify-between text-xs mb-1">
                      <span className={darkMode ? 'text-gray-300' : 'text-gray-700'}>{name}</span>
                      <span className={darkMode ? 'text-gray-300' : 'text-gray-700'}>
                        {formatLocal(amt, currencySymbol)} ({percent}%)
                      </span>
                    </div>

                    <div className={`w-full h-2 rounded-full overflow-hidden ${darkMode ? 'bg-gray-700' : 'bg-gray-200'}`}>
                      <div className={`h-2 rounded-full bg-gradient-to-r ${colors[idx % colors.length]} transition-all duration-500`} style={{ width: percent + '%' }} />
                    </div>
                  </div>
                );
              })}
            </div>

            {isOpen && (
              <div className={`mt-3 pt-2 border-t text-xs space-y-1 ${darkMode ? 'border-gray-700' : 'border-gray-200'}`}>
                {history.filter(h => getKey(h.time, getResetDay(h.name))===k).map((h,i)=>(
                  <div key={i} className="flex justify-between">
                    <span>{h.name}</span>
                    <span>{formatLocal(h.amount, currencySymbol)}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function CategoryStats({ history, currencySymbol, darkMode }) {
  const grouped = {};

  history.forEach(h => {
    const key = h.category || '未分類';
    if (!grouped[key]) grouped[key] = 0;
    grouped[key] += Number(h.amount || 0);
  });

  const list = Object.entries(grouped).sort((a,b)=>b[1]-a[1]);
  if (list.length === 0) return null;

  const total = list.reduce((sum, [,v])=>sum+v,0) || 1;

  const colors = ['from-blue-400 to-blue-600','from-green-400 to-green-600','from-purple-400 to-purple-600','from-pink-400 to-pink-600','from-yellow-400 to-yellow-600','from-cyan-400 to-cyan-600'];

  return (
    <Card darkMode={darkMode}>
      <div className="font-bold mb-2">📊 消費分類統計</div>

      <div className="space-y-3">
        {list.map(([name, amt], i) => {
          const percent = Math.round((amt / total) * 100);

          return (
            <div key={name}>
              <div className="flex justify-between text-xs mb-1">
                <span>{name}</span>
                <span>{formatLocal(amt, currencySymbol)} ({percent}%)</span>
              </div>

              <div className={`w-full h-3 rounded-full overflow-hidden ${darkMode ? 'bg-gray-700' : 'bg-gray-200'}`}>
                <div
                  className={`h-3 rounded-full bg-gradient-to-r ${colors[i % colors.length]} transition-all duration-500`}
                  style={{ width: percent + '%' }}
                />
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}


export default function StatisticsPage({ history, payments, currencySymbol, darkMode }) {
  return (
    <>
      <CycleStats history={history} payments={payments} currencySymbol={currencySymbol} darkMode={darkMode} />
      <CategoryStats history={history} currencySymbol={currencySymbol} darkMode={darkMode} />
    </>
  );
}
