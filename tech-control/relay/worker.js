function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function sendTelegramMessage(env, text) {
  const cache = caches.default;
  const migrationKey = new Request("https://tech-control-relay.internal/__telegram_migrated_chat");
  const cachedMigration = await cache.match(migrationKey);
  let chatId = cachedMigration ? await cachedMigration.text() : env.CHAT_ID;

  const send = async (targetChatId) => {
    const response = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/sendMessage`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chat_id: targetChatId, text }),
    });
    const result = await response.json().catch(() => ({}));
    return { response, result };
  };

  let outcome = await send(chatId);
  if ((!outcome.response.ok || !outcome.result.ok) && outcome.result.parameters?.migrate_to_chat_id) {
    chatId = String(outcome.result.parameters.migrate_to_chat_id);
    await cache.put(migrationKey, new Response(chatId, {
      headers: { "cache-control": "public, max-age=2592000" }
    }));
    outcome = await send(chatId);
  }
  return outcome;
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
    if (request.method !== "POST" || !["/api/report", "/api/attendance"].includes(url.pathname)) {
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

      if (url.pathname === "/api/attendance") {
        const requestId = String(incoming.request_id || "").trim();
        const employee = String(incoming.employee || "").trim();
        const object = String(incoming.object || "").trim();
        const event = String(incoming.event || "").trim();
        const time = String(incoming.time || "").trim();
        if (!requestId || requestId.length > 80 || !employee || !object || !["arrival", "departure"].includes(event) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) {
          return json({ ok: false, error: "Некорректные данные отметки рабочего времени" }, 400);
        }
        const cache = caches.default;
        const cacheKey = new Request(`${url.origin}/__attendance/${encodeURIComponent(requestId)}`, { method: "GET" });
        const cached = await cache.match(cacheKey);
        if (cached) return new Response(cached.body, cached);

        const date = new Intl.DateTimeFormat("ru-RU", {
          timeZone: "Asia/Almaty", day: "2-digit", month: "2-digit", year: "numeric"
        }).format(new Date());
        const isArrival = event === "arrival";
        const text = [
          isArrival ? "🟢 ПРИХОД НА РАБОТУ" : "🔴 УХОД С РАБОТЫ",
          isArrival ? "#ПриходНаРаботу" : "#УходСРаботы",
          `Сотрудник: ${employee}`,
          `Объект: ${object}`,
          `Дата: ${date}`,
          `Время: ${time}`
        ].join("\n");
        const { response: telegramResponse, result: telegramResult } = await sendTelegramMessage(env, text);
        if (!telegramResponse.ok || !telegramResult.ok) {
          return json({ ok: false, error: telegramResult.description || `Telegram API HTTP ${telegramResponse.status}` }, 502);
        }
        const result = { ok: true, request_id: requestId };
        await cache.put(cacheKey, new Response(JSON.stringify(result), {
          status: 200, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=86400" }
        }));
        return json(result);
      }

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

      const { response: telegramResponse, result: telegramResult } = await sendTelegramMessage(env, reportText);
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
