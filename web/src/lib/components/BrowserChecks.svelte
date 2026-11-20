<script lang="ts">
  import { api } from "#lib/api";
  import { toasts } from "#lib/toast.svelte";
  import type { BrowserCheck } from "#lib/types";
  import { TriangleAlert, X } from "@lucide/svelte";
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";

  // Checks the background browser couldn't pass (usually Turnstile). Solving opens a Chromium window on the
  // desktop; the request that hit the check waits for it, and later ones reuse the cookies.
  const client = useQueryClient();
  const browser = createQuery(() => ({
    queryKey: ["browser"],
    queryFn: api.browser,
    refetchInterval: (q) => (q.state.data?.checks.some((c) => c.solving) ? 1500 : 5000),
  }));

  async function act(fn: () => Promise<unknown>) {
    try {
      client.setQueryData(["browser"], await fn());
    } catch (e) {
      toasts.error(e);
    }
  }

  const what = (c: BrowserCheck) =>
    c.kind === "cloudflare"
      ? "Cloudflare вимагає перевірки на людину"
      : "Сторінка не завершила завантаження у фоновому браузері";
</script>

{#if browser.data?.checks.length}
  <div class="checks">
    {#each browser.data.checks as c (c.id)}
      <div class="check" role="status">
        <span class="icon"><TriangleAlert size={18} strokeWidth={1.8} aria-hidden="true" /></span>
        <div class="text">
          <b>{c.host}</b>
          <small>
            {#if c.solving}
              Пройдіть перевірку у вікні Chromium на сервері{c.kind === "page"
                ? ", потім закрийте вкладку"
                : ""}.
            {:else}
              {what(c)}. Натисніть «Пройти», щоб відкрити вікно на сервері.
            {/if}
          </small>
        </div>
        {#if c.solving}
          <span class="spinner" aria-label="Очікування"></span>
        {:else}
          <button class="btn primary" onclick={() => act(() => api.solveCheck(c.id))}>Пройти</button
          >
        {/if}
        <button
          class="dismiss"
          onclick={() => act(() => api.dismissCheck(c.id))}
          aria-label="Закрити"><X size={18} strokeWidth={1.8} aria-hidden="true" /></button
        >
      </div>
    {/each}
  </div>
{/if}

<style>
  .checks {
    max-width: 1400px;
    margin: 0 auto;
    padding: 12px 16px 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .check {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: var(--radius);
    background: var(--accent-soft);
    border: 1px solid rgb(255 138 76 / 0.35);
  }
  .icon {
    color: var(--accent);
    display: grid;
  }
  .text {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .text b {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  small {
    color: var(--muted);
    font-size: 13px;
  }
  .btn {
    flex-shrink: 0;
  }
  .dismiss {
    color: var(--faint);
    display: grid;
    padding: 4px;
  }
  .dismiss:hover {
    color: var(--text);
  }
</style>
