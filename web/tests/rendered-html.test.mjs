import assert from "node:assert/strict";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("renders the family glucose dashboard", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="ru">/i);
  assert.match(html, /<title>Сладкая — семейное наблюдение<\/title>/i);
  assert.match(html, /Демонстрационный экран глюкозы, графика и семейных тревог/);
  assert.match(html, /\/og-v2\.png/);
  assert.match(html, /Семейное наблюдение/);
  assert.match(html, /Профиль владельца/);
  assert.match(html, /Низкое значение/);
  assert.match(html, /Я увидел тревогу/);
  assert.match(html, /Демо-диапазон тревог 3,9–10,0 ммоль\/л/);
  assert.match(html, /разрыв не соединяется линией/);
  assert.match(html, /Демо-экран активен/);
  assert.match(html, /Сейчас показаны симулированные данные/);
  assert.doesNotMatch(html, /Все системы на связи/);
  assert.doesNotMatch(html, /Целевой диапазон/);
  assert.doesNotMatch(html, /Профиль Михаила/);
  assert.doesNotMatch(html, /\/og\.png/);
  assert.doesNotMatch(html, /codex-preview|Your site is taking shape|react-loading-skeleton/i);
});
