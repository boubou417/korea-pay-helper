Korea Pay Helper V5 Alpha 2（Part 2）

本版重構內容：
1. 將最佳策略分析邏輯從 App.js 拆至 src/services/analyze.js
2. 將歷史記錄與分頁拆至 src/components/history/HistorySection.jsx
3. 將帳期統計與分類統計拆至 src/components/statistics/StatisticsPage.jsx
4. App.js 由約 1765 行縮減至約 1178 行
5. 保留既有資料格式、LocalStorage、四個 Tabs、APK 備份、設定中心與所有操作功能

安裝 / 測試：
1. 解壓縮至新資料夾，或先備份舊專案後覆蓋。
2. 執行 npm install
3. 執行 npm run build
4. 執行 npx cap sync android
5. 執行 npx cap open android

注意：
- ZIP 不含 node_modules，避免帶入其他環境的 npm registry。
- 若你已在 V5 Alpha 1 專案安裝過套件，可直接覆蓋 src 與本說明檔後執行 npm run build。
