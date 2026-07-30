Korea Pay Helper V5 Alpha 3 - Part 2

本版重點：
1. 新增 components/payment/PaymentProgress.jsx
2. 新增 components/payment/PaymentCard.jsx
3. 新增 components/payment/PaymentList.jsx
4. 將首頁原本寫在 App.js 裡的 StatusSection 拆出
5. 支付方式改為資訊卡片設計
6. 保留拖曳排序、編輯、刪除、重置、剩餘額度、帳期與回饋計算

測試方式：
npm install
npm run build
npx cap sync android
npx cap open android

建議先在網頁版測試：
- 支付方式卡片顯示
- 拖曳排序
- 編輯
- 刪除
- 重置
- 深色模式
