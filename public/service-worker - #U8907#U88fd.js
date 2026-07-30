const CACHE_NAME = "pay-helper-v6";

// ⭐ 安裝：立即啟用新版本
self.addEventListener("install", (event) => {
  self.skipWaiting();
});

// ⭐ 啟用：接管所有頁面
self.addEventListener("activate", (event) => {
  self.clients.claim();
});

// ⭐ Fetch：網路優先（避免舊版本問題）
self.addEventListener("fetch", (event) => {
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        // 成功 → 更新 cache
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => {
          cache.put(event.request, copy);
        });
        return response;
      })
      .catch(() => {
        // 失敗 → 用 cache
        return caches.match(event.request).then((res) => {
          return res || caches.match("/");
        });
      })
  );
});

// ⭐ 接收 App.js 的強制更新指令
self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
});