function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function authorized(request, expected) {
  const supplied = request.headers.get("authorization") || "";
  const prefix = "Bearer ";
  if (!supplied.startsWith(prefix) || !expected) return false;
  const a = new TextEncoder().encode(supplied.slice(prefix.length));
  const b = new TextEncoder().encode(expected);
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "tech-control-relay" });
    }
    if (request.method !== "POST" || url.pathname !== "/api/report") {
      return json({ ok: false, error: "Not found" }, 404);
    }
    if (!authorized(request, env.APP_KEY)) {
      return json({ ok: false, error: "Неверный ключ доступа" }, 401);
    }
    if (!env.BOT_TOKEN || !env.CHAT_ID) {
      return json({ ok: false, error: "Сервер не настроен: отсутствуют BOT_TOKEN или CHAT_ID" }, 500);
    }

    try {
      const incoming = await request.json();
      const requestId = String(incoming.request_id || "").trim();
      const reportText = String(incoming.report_text || "").trim();

      if (!requestId || requestId.length > 80) {
        return json({ ok: false, error: "Нет идентификатора отчёта. Обновите приложение." }, 400);
      }
      if (!reportText) return json({ ok: false, error: "Пустой отчёт" }, 400);
      if (reportText.length > 12000) {
        return json({ ok: false, error: "Отчёт слишком длинный" }, 413);
      }

      // Keep successful report IDs for 24 hours to prevent duplicate posts
      // when the phone retries after a temporary network interruption.
      const cache = caches.default;
      const cacheKey = new Request(`${url.origin}/__sent_report/${encodeURIComponent(requestId)}`, { method: "GET" });
      const cached = await cache.match(cacheKey);
      if (cached) return new Response(cached.body, cached);

      const telegramResponse = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/sendMessage`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ chat_id: env.CHAT_ID, text: reportText }),
      });
      const telegramResult = await telegramResponse.json().catch(() => ({}));
      if (!telegramResponse.ok || !telegramResult.ok) {
        return json({
          ok: false,
          error: telegramResult.description || `Telegram API HTTP ${telegramResponse.status}`,
        }, 502);
      }

      const result = { ok: true, request_id: requestId };
      await cache.put(cacheKey, new Response(JSON.stringify(result), {
        status: 200,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": "public, max-age=86400",
        },
      }));
      return json(result);
    } catch (error) {
      return json({
        ok: false,
        error: error instanceof Error ? error.message : "Внутренняя ошибка сервера",
      }, 502);
    }
  },
};
