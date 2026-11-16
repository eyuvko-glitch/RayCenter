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
  // Constant-time comparison for equal-length keys.
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
      const incoming = await request.formData();
      const reportText = String(incoming.get("report_text") || "").trim();
      if (!reportText) return json({ ok: false, error: "Пустой отчёт" }, 400);
      const photos = incoming.getAll("photo").filter((item) => item && typeof item !== "string");
      if (photos.length > 12) return json({ ok: false, error: "Разрешено не более 12 фотографий" }, 400);
      const oversized = photos.findIndex((photo) => photo.size > 10 * 1024 * 1024);
      if (oversized >= 0) return json({ ok: false, error: `Фото №${oversized + 1} больше 10 МБ` }, 413);

      const telegramBase = `https://api.telegram.org/bot${env.BOT_TOKEN}`;
      const messageResponse = await fetch(`${telegramBase}/sendMessage`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ chat_id: env.CHAT_ID, text: reportText }),
      });
      const messageResult = await messageResponse.json().catch(() => ({}));
      if (!messageResponse.ok || !messageResult.ok) {
        return json({
          ok: false,
          error: `Telegram sendMessage: ${messageResult.description || messageResponse.status}`,
        }, 502);
      }

      for (let index = 0; index < photos.length; index++) {
        const photo = photos[index];
        const outgoing = new FormData();
        outgoing.set("chat_id", env.CHAT_ID);
        outgoing.set("photo", photo, photo.name || `photo_${index + 1}.jpg`);
        const photoResponse = await fetch(`${telegramBase}/sendPhoto`, {
          method: "POST",
          body: outgoing,
        });
        const photoResult = await photoResponse.json().catch(() => ({}));
        if (!photoResponse.ok || !photoResult.ok) {
          return json({
            ok: false,
            error: `Telegram sendPhoto №${index + 1}: ${photoResult.description || photoResponse.status}; текст отчёта уже отправлен`,
          }, 502);
        }
      }

      return json({ ok: true, photos: photos.length });
    } catch (error) {
      return json({ ok: false, error: error instanceof Error ? error.message : "Внутренняя ошибка сервера" }, 500);
    }
  },
};
