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

async function telegramCall(url, body, headers = {}) {
  const response = await fetch(url, { method: "POST", body, headers });
  const result = await response.json().catch(() => ({}));
  if (!response.ok || !result.ok) {
    throw new Error(result.description || `Telegram API HTTP ${response.status}`);
  }
  return result;
}

async function sendPhotoGroup(base, chatId, photos, caption = "") {
  const form = new FormData();
  form.set("chat_id", chatId);
  const media = photos.map((photo, index) => {
    const item = { type: "photo", media: `attach://photo${index}` };
    if (index === 0 && caption) item.caption = caption;
    form.set(`photo${index}`, photo, photo.name || `photo_${index + 1}.jpg`);
    return item;
  });
  form.set("media", JSON.stringify(media));
  return telegramCall(`${base}/sendMediaGroup`, form);
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
      const requestId = String(incoming.get("request_id") || "").trim();
      const reportText = String(incoming.get("report_text") || "").trim();
      if (!requestId || requestId.length > 80) {
        return json({ ok: false, error: "Нет идентификатора отчёта. Обновите приложение." }, 400);
      }
      if (!reportText) return json({ ok: false, error: "Пустой отчёт" }, 400);

      // Cache successful submissions for 24 hours. The Android app reuses the
      // same request_id after a network timeout, so it won't post the report twice.
      const cache = caches.default;
      const cacheKey = new Request(`${url.origin}/__sent_report/${encodeURIComponent(requestId)}`, { method: "GET" });
      const cached = await cache.match(cacheKey);
      if (cached) return new Response(cached.body, cached);

      const photos = incoming.getAll("photo").filter((item) => item && typeof item !== "string");
      if (photos.length > 12) return json({ ok: false, error: "Разрешено не более 12 фотографий" }, 400);
      const invalidPhoto = photos.findIndex((photo) => photo.size === 0 || photo.size > 10 * 1024 * 1024);
      if (invalidPhoto >= 0) {
        return json({ ok: false, error: `Фото №${invalidPhoto + 1}: пустой файл или размер больше 10 МБ` }, 413);
      }

      const base = `https://api.telegram.org/bot${env.BOT_TOKEN}`;
      // Telegram photo captions are limited to 1024 characters. Long reports
      // are sent as a normal text message, followed by the photo attachment(s).
      const caption = reportText.length <= 1024 ? reportText : "";
      if (photos.length === 0 || !caption) {
        await telegramCall(`${base}/sendMessage`, JSON.stringify({
          chat_id: env.CHAT_ID,
          text: reportText,
        }), { "content-type": "application/json" });
      }
      if (photos.length === 1) {
        const form = new FormData();
        form.set("chat_id", env.CHAT_ID);
        if (caption) form.set("caption", caption);
        form.set("photo", photos[0], photos[0].name || "photo_1.jpg");
        await telegramCall(`${base}/sendPhoto`, form);
      } else if (photos.length > 1) {
        // Telegram media groups accept 2–10 photos.
        await sendPhotoGroup(base, env.CHAT_ID, photos.slice(0, 10), caption);
        if (photos.length > 10) {
          await sendPhotoGroup(base, env.CHAT_ID, photos.slice(10), "");
        }
      }

      const result = { ok: true, photos: photos.length, request_id: requestId };
      const response = json(result);
      await cache.put(cacheKey, new Response(JSON.stringify(result), {
        status: 200,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": "public, max-age=86400",
        },
      }));
      return response;
    } catch (error) {
      return json({
        ok: false,
        error: error instanceof Error ? error.message : "Внутренняя ошибка сервера",
      }, 502);
    }
  },
};
