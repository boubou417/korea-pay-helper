import React, { useEffect, useMemo, useState } from 'react';

const formatLocal = (num, symbol) => `${symbol}${Math.floor(num).toLocaleString()}`;

function Card({ children, darkMode }) {
  return (
    <div className={darkMode ? 'bg-gray-800 border-gray-700 text-white backdrop-blur rounded-3xl shadow-xl p-4 mb-4 transition-colors' : 'bg-white border-gray-200 backdrop-blur rounded-3xl shadow-xl p-4 mb-4 transition-colors'}>
      {children}
    </div>
  );
}

export default function HistorySection({ history, payments, onUndo, currencySymbol, darkMode }) {
  const [filterCategory, setFilterCategory] = useState('全部');
  const [filterCycle, setFilterCycle] = useState('全部');
  const [currentPage, setCurrentPage] = useState(1);
  const PAGE_SIZE = 5;

  const getResetDay = (name) => payments.find(p=>p.name===name)?.resetDay;

  // ⭐ 判斷帳期類型（本期 / 上期）
  const getCycleType = (time, resetDay) => {
    if (!resetDay) return '本期';

    const today = new Date();
    const d = new Date(time);

    const nowDay = today.getDate();
    const tDay = d.getDate();

    if (nowDay > resetDay) {
      return tDay > resetDay ? '本期' : '上期';
    }

    return tDay > resetDay ? '上期' : '本期';
  };

  const getCycleText = (time, resetDay) => {
    if (!resetDay) return '';

    const d = new Date(time);
    const y = d.getFullYear();
    const m = d.getMonth();
    const day = d.getDate();

    let start, end;
    if (day > resetDay) {
      start = new Date(y, m, resetDay + 1);
      end = new Date(y, m + 1, resetDay);
    } else {
      start = new Date(y, m - 1, resetDay + 1);
      end = new Date(y, m, resetDay);
    }

    const fmt = (dt) => `${dt.getMonth()+1}/${dt.getDate()}`;
    return `${fmt(start)}～${fmt(end)}`;
  };

  const filteredHistory = useMemo(() => {
    return history
      .map((item, originalIndex) => ({ item, originalIndex }))
      .filter(({ item }) => {
        const cat = item.category || '未分類';
        if (filterCategory !== '全部' && cat !== filterCategory) return false;

        const resetDay = getResetDay(item.name);
        const cycleType = getCycleType(item.time, resetDay);
        if (filterCycle !== '全部' && cycleType !== filterCycle) return false;

        return true;
      });
  }, [history, payments, filterCategory, filterCycle]);

  const totalPages = Math.max(1, Math.ceil(filteredHistory.length / PAGE_SIZE));

  useEffect(() => {
    setCurrentPage(1);
  }, [filterCategory, filterCycle]);

  useEffect(() => {
    if (currentPage > totalPages) {
      setCurrentPage(totalPages);
    }
  }, [currentPage, totalPages]);

  const pageItems = filteredHistory.slice(
    (currentPage - 1) * PAGE_SIZE,
    currentPage * PAGE_SIZE
  );

  const grouped = {};

  pageItems.forEach(({ item, originalIndex }) => {
    const d = new Date(item.time);
    const key = `${d.getFullYear()}-${d.getMonth()+1}`;

    if (!grouped[key]) grouped[key] = [];
    grouped[key].push({ item, originalIndex });
  });

  const list = Object.entries(grouped);
  const allCategories = ['全部', ...Array.from(new Set(history.map(h => h.category || '未分類')))];

  const pageNumbers = [];
  const startPage = Math.max(1, currentPage - 2);
  const endPage = Math.min(totalPages, startPage + 4);
  const adjustedStart = Math.max(1, endPage - 4);

  for (let page = adjustedStart; page <= endPage; page += 1) {
    pageNumbers.push(page);
  }

  return (
    <Card darkMode={darkMode}>
      <div className="font-bold mb-2">歷史記錄</div>

      {/* ⭐ 帳期篩選 */}
      <div className="flex gap-2 mb-2">
        {['全部','本期','上期'].map(c => (
          <button
            key={c}
            onClick={() => setFilterCycle(c)}
            className={`px-2 py-1 text-xs rounded-full ${
              filterCycle === c
                ? 'bg-purple-500 text-white'
                : darkMode
                ? 'bg-gray-700 text-gray-200'
                : 'bg-gray-200 text-gray-700'
            }`}
          >
            {c}
          </button>
        ))}
      </div>

      {/* ⭐ 分類篩選 */}
      <div className="flex flex-wrap gap-2 mb-3">
        {allCategories.map(c => (
          <button
            key={c}
            onClick={() => setFilterCategory(c)}
            className={`px-2 py-1 text-xs rounded-full ${
              filterCategory === c
                ? 'bg-blue-500 text-white'
                : darkMode
                ? 'bg-gray-700 text-gray-200'
                : 'bg-gray-200 text-gray-700'
            }`}
          >
            {c}
          </button>
        ))}
      </div>

      {filteredHistory.length === 0 && (
        <div className={`py-8 text-center text-sm ${darkMode ? 'text-gray-400' : 'text-gray-500'}`}>
          沒有符合條件的記錄
        </div>
      )}

      {list.map(([month, items]) => (
        <div key={month} className="mb-3">
          <div className={`text-sm font-semibold mb-1 ${darkMode ? 'text-gray-300' : 'text-gray-700'}`}>
            📅 {month}
          </div>

          {items.map(({ item: h, originalIndex }) => {
            const resetDay = getResetDay(h.name);
            const cycleText = getCycleText(h.time, resetDay);

            return (
              <div key={`${h.time}-${originalIndex}`} className={`mb-2 p-3 rounded-xl ${h.name==='現金' ? (darkMode ? 'bg-green-900 text-green-200' : 'bg-green-50 text-green-800') : (darkMode ? 'bg-gray-700 text-gray-200' : 'bg-gray-50 text-gray-800')}`}>
                <div className="flex justify-between text-[11px] mb-1">
                  <span className="opacity-70">{new Date(h.time).toLocaleString()}</span>
                  <button onClick={() => onUndo(originalIndex)} className="text-red-500 text-[11px]">刪除</button>
                </div>

                <div className="flex justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-medium flex gap-1 flex-wrap">
                      {h.name}
                      <span className={`px-2 py-0.5 rounded-full text-[10px] ${h.name==='現金' ? 'bg-green-500 text-white' : 'bg-blue-500 text-white'}`}>
                        {h.category || '未分類'}
                      </span>
                    </div>

                    {cycleText && (
                      <div className="text-[11px] text-purple-400 mt-0.5">
                        🧾 {cycleText}
                      </div>
                    )}

                    {h.note && (
                      <div className={`text-[12px] mt-1 break-words ${darkMode ? 'text-gray-300' : 'text-gray-600'}`}>
                        {h.note}
                      </div>
                    )}
                  </div>

                  <div className="font-semibold text-green-500 shrink-0">
                    {formatLocal(h.amount, currencySymbol)}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      ))}

      {filteredHistory.length > 0 && (
        <div className={`mt-4 pt-3 border-t ${darkMode ? 'border-gray-700' : 'border-gray-200'}`}>
          <div className="flex items-center justify-between gap-2">
            <button
              onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
              disabled={currentPage === 1}
              className={`min-w-[72px] px-3 py-2 rounded-xl text-xs font-medium transition ${
                currentPage === 1
                  ? darkMode
                    ? 'bg-gray-800 text-gray-600 cursor-not-allowed'
                    : 'bg-gray-100 text-gray-400 cursor-not-allowed'
                  : darkMode
                  ? 'bg-gray-700 text-white active:bg-gray-600'
                  : 'bg-gray-200 text-gray-800 active:bg-gray-300'
              }`}
            >
              ◀ 上一頁
            </button>

            <div className={`text-xs font-medium text-center ${darkMode ? 'text-gray-300' : 'text-gray-600'}`}>
              第 {currentPage} / {totalPages} 頁
            </div>

            <button
              onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
              disabled={currentPage === totalPages}
              className={`min-w-[72px] px-3 py-2 rounded-xl text-xs font-medium transition ${
                currentPage === totalPages
                  ? darkMode
                    ? 'bg-gray-800 text-gray-600 cursor-not-allowed'
                    : 'bg-gray-100 text-gray-400 cursor-not-allowed'
                  : darkMode
                  ? 'bg-gray-700 text-white active:bg-gray-600'
                  : 'bg-gray-200 text-gray-800 active:bg-gray-300'
              }`}
            >
              下一頁 ▶
            </button>
          </div>

          {totalPages > 1 && (
            <div className="flex justify-center gap-1 mt-3 flex-wrap">
              {pageNumbers.map(page => (
                <button
                  key={page}
                  onClick={() => setCurrentPage(page)}
                  className={`w-8 h-8 rounded-lg text-xs font-medium transition ${
                    currentPage === page
                      ? 'bg-blue-500 text-white shadow'
                      : darkMode
                      ? 'bg-gray-700 text-gray-300 active:bg-gray-600'
                      : 'bg-gray-100 text-gray-700 active:bg-gray-200'
                  }`}
                >
                  {page}
                </button>
              ))}
            </div>
          )}

          <div className={`text-[11px] text-center mt-2 ${darkMode ? 'text-gray-500' : 'text-gray-400'}`}>
            共 {filteredHistory.length} 筆，每頁 {PAGE_SIZE} 筆
          </div>
        </div>
      )}
    </Card>
  );
}

