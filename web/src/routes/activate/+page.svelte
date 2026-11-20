<script lang="ts">
  import { api, type AuthStatus, type DeviceRequest } from "#lib/api";
  import { page } from "$app/state";
  import { onMount } from "svelte";

  let info = $state<AuthStatus | null>(null);
  let code = $state(page.url.searchParams.get("code") ?? "");
  let request = $state<DeviceRequest | null>(null);
  let result = $state<"approved" | "declined" | null>(null);
  let busy = $state(false);
  let error = $state("");

  const normalized = $derived(code.toUpperCase().replace(/[\s-]/g, ""));

  /** Loopback addresses mean the request came from the server's own machine. */
  function describe(address: string) {
    if (/^(127\.|::1$|0:0:0:0:0:0:0:1$|::ffff:127\.)/.test(address) || address === "localhost")
      return "власний комп'ютер сервера";
    return address;
  }

  async function lookup(event?: SubmitEvent) {
    event?.preventDefault();
    error = "";
    busy = true;
    try {
      request = await api.lookupDevice(normalized);
    } catch (e) {
      error = e instanceof Error ? e.message : "Не вдалося знайти цей код.";
    } finally {
      busy = false;
    }
  }
  async function decide(approve: boolean) {
    error = "";
    busy = true;
    try {
      await api.approveDevice(normalized, approve);
      result = approve ? "approved" : "declined";
    } catch (e) {
      error = e instanceof Error ? e.message : "Не вдалося завершити дію. Спробуйте знову.";
    } finally {
      busy = false;
    }
  }
  function another() {
    code = "";
    request = null;
    result = null;
    error = "";
  }

  onMount(async () => {
    try {
      info = await api.authStatus();
      // A code from the QR link: look it up straight away, the decision still needs a tap.
      if (info.mode === "local" && normalized.length === 6) await lookup();
    } catch (e) {
      error = e instanceof Error ? e.message : "Не вдалося з'єднатися із сервером.";
    }
  });
</script>

<svelte:head><title>Підтвердити пристрій · Cloudstream</title></svelte:head>

<section class="page" aria-labelledby="activate-heading">
  <div class="page-heading">
    <h1 class="page-title" id="activate-heading">Підтвердити пристрій</h1>
    <p>Дозвольте телевізору, смартфону або браузеру авторизуватися на цьому сервері.</p>
  </div>
  <div class="activate">
    {#if info && info.mode !== "local"}
      <p>
        Цей сервер авторизує пристрої через акаунт Cloudstream. Підтверджуйте коди на сайті
        керування акаунтом.
      </p>
      {#if info.workerUrl}<a class="btn" href={`${info.workerUrl}/activate`}
          >Відкрити сайт акаунта</a
        >{/if}
    {:else if result}
      <p role="status">
        {result === "approved"
          ? `${request?.deviceName ?? "Пристрій"} успішно авторизовано. За кілька секунд сесія почнеться.`
          : "Відхилено. Пристрою необхідно згенерувати новий код для повторної спроби."}
      </p>
      <button class="btn" onclick={another}>Підтвердити інший пристрій</button>
    {:else if request}
      <p class="device">
        <b>{request.deviceName}</b> запитує авторизацію на {info?.serverName ?? "цьому сервері"}.
      </p>
      <dl>
        <dt>Код</dt>
        <dd class="code">{normalized.slice(0, 3)} {normalized.slice(3)}</dd>
        <dt>Запит від</dt>
        <dd>{describe(request.address)}</dd>
        <dt>Час запиту</dt>
        <dd>
          {new Date(request.requestedAt).toLocaleTimeString("uk-UA", {
            hour: "numeric",
            minute: "2-digit",
          })}
        </dd>
      </dl>
      <p class="warning">
        Підтверджуйте запит лише в тому разі, якщо цей код відображається на екрані перед вами.
        Будь-який підтверджений пристрій отримує доступ на 30 днів.
      </p>
      <div class="actions">
        <button class="btn primary" onclick={() => decide(true)} disabled={busy}>Підтвердити</button
        >
        <button class="btn" onclick={() => decide(false)} disabled={busy}>Відхилити</button>
      </div>
    {:else if info}
      <p>
        Введіть шестизначний код, який відображається на вашому телевізорі, смартфоні або в
        браузері.
      </p>
      <form onsubmit={lookup}>
        <label
          >Код пристрою<input
            bind:value={code}
            class="code"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            maxlength="8"
            required
            disabled={busy}
          /></label
        >
        <button class="btn primary" disabled={busy || normalized.length !== 6}
          >{busy ? "Перевірка…" : "Продовжити"}</button
        >
      </form>
    {:else if !error}
      <p role="status">Завантаження…</p>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}
  </div>
</section>

<style>
  .activate {
    max-width: 440px;
  }
  .activate p {
    color: var(--muted);
    line-height: 1.6;
    margin: 0 0 20px;
  }
  .device {
    color: var(--text);
    overflow-wrap: anywhere;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 8px 20px;
    margin: 0 0 20px;
  }
  dt {
    color: var(--muted);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .code {
    font-weight: 700;
    letter-spacing: 0.1em;
    font-variant-numeric: tabular-nums;
    text-transform: uppercase;
  }
  input.code {
    font-size: 24px;
    min-height: 52px;
  }
  .warning {
    color: var(--text);
  }
  form,
  label {
    display: grid;
    gap: 8px;
  }
  form {
    gap: 20px;
  }
  label {
    font-size: 14px;
    font-weight: 600;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }
  .btn {
    min-height: 48px;
    justify-content: center;
  }
  .actions .btn {
    flex: 1 1 140px;
  }
  .error {
    color: var(--danger);
  }
</style>
