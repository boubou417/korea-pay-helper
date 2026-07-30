Korea Pay Helper V5 Alpha 3 - Part 1

本版內容：
1. 新增共用 SurfaceCard 元件
2. 新增共用 AppButton 元件
3. 新增共用 SectionHeading 元件
4. 首頁新增「今日推薦支付」卡片
5. 輸入金額、最佳策略、使用狀態加入統一標題與卡片視覺
6. 重置按鈕改用共用 Danger Button
7. 保留最佳拆單、記錄、統計、設定、備份與既有資料格式

新增檔案：
src/components/common/SurfaceCard.jsx
src/components/common/AppButton.jsx
src/components/common/SectionHeading.jsx
src/components/home/RecommendationCard.jsx

使用方式：
1. 先備份原專案
2. 解壓縮並覆蓋原專案，或直接把本專案放到新資料夾測試
3. 執行：npm install
4. 執行：npm run build
5. APK：npx cap sync android
6. 執行：npx cap open android

注意：
此交付環境無法連線完成 npm install，因此未在此環境跑完正式 build；程式修改已依現有 Alpha 2 專案結構完成。
