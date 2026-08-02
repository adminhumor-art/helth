"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

export default function FamilyAccessGate({ sessionExpired = false }: { sessionExpired?: boolean }) {
  const router = useRouter();
  const [access, setAccess] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(sessionExpired ? "Срок семейного доступа истёк. Войдите снова." : null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!access || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/family/session", {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ access }),
      });
      if (!response.ok) {
        setError(response.status === 401 || response.status === 403
          ? "Код не принят или больше не действует."
          : "Семейный сервер временно недоступен.");
        return;
      }
      setAccess("");
      router.refresh();
    } catch {
      setError("Нет связи с сервером. Попробуйте ещё раз.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="access-shell">
      <section className="access-card" aria-labelledby="access-title">
        <div className="brand"><span className="brand-mark" aria-hidden="true" />Сладкая</div>
        <p className="eyebrow">Семейное наблюдение</p>
        <h1 id="access-title">Вход для семьи</h1>
        <p className="access-copy">Введите выданный владельцем код. Он используется один раз и не сохраняется в браузере.</p>
        <form onSubmit={submit}>
          <label htmlFor="family-access">Код семейного доступа</label>
          <input
            id="family-access"
            type="password"
            value={access}
            onChange={(event) => setAccess(event.target.value)}
            autoComplete="one-time-code"
            maxLength={4096}
            required
          />
          {error && <p className="access-error" role="alert">{error}</p>}
          <button type="submit" disabled={submitting || !access}>
            {submitting ? "Проверяем…" : "Открыть состояние"}
          </button>
        </form>
        <p className="access-note">Значения на семейном экране не заменяют экстренную помощь.</p>
      </section>
    </main>
  );
}
