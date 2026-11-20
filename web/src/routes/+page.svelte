<script lang="ts">
  import { createQuery } from "@tanstack/svelte-query";
  import { api, titleHref } from "#lib/api";
  import { Layers, Search, Settings2 } from "@lucide/svelte";
  import HomeShelf from "#lib/components/HomeShelf.svelte";
  import HomeRow from "#lib/components/HomeRow.svelte";
  import LoadingSkeleton from "#lib/components/LoadingSkeleton.svelte";
  import Poster from "#lib/components/Poster.svelte";
  import ProviderPicker from "#lib/components/ProviderPicker.svelte";

  const providers = createQuery(() => ({
    queryKey: ["providers"],
    queryFn: api.providers,
    staleTime: 30_000,
  }));
  const status = createQuery(() => ({
    queryKey: ["status"],
    queryFn: api.status,
    refetchInterval: 4000,
  }));
  const continueQ = createQuery(() => ({ queryKey: ["continue"], queryFn: api.continueWatching }));

  const withHome = $derived((providers.data ?? []).filter((p) => p.hasMainPage));
  let chosen = $state<string | null>(read());
  const current = $derived(withHome.find((p) => p.name === chosen) ?? withHome[0]);

  function read() {
    try {
      return localStorage.getItem("home-provider");
    } catch {
      return null;
    }
  }
  function choose(name: string) {
    chosen = name;
    try {
      localStorage.setItem("home-provider", name);
    } catch {
      // Private mode: the choice just isn't remembered.
    }
  }

  const sections = createQuery(() => ({
    queryKey: ["home-sections", current?.name],
    queryFn: () => api.homeSections(current!.name),
    enabled: !!current,
    staleTime: 10 * 60_000,
  }));
</script>

<svelte:head><title>Головна · Cloudstream</title></svelte:head>

<div class="page home">
  <div class="top">
    <div class="page-heading">
      <h1 class="page-title">Головна</h1>
      <p>Ваш наступний перегляд починається тут.</p>
    </div>
    {#if current}
      <form class="browse-search" action="/search" role="search">
        <Search size={19} strokeWidth={1.8} aria-hidden="true" />
        <input type="hidden" name="provider" value={current.name} />
        <input
          type="search"
          name="q"
          aria-label={`Пошук у ${current.name}`}
          placeholder={`Пошук у ${current.name}...`}
          enterkeyhint="search"
          required
        />
        <button type="submit" aria-label="Шукати">Пошук</button>
      </form>
    {/if}
    {#if status.data?.building}<span class="building"
        ><span class="spinner"></span> Збірка плагінів…</span
      >{/if}
  </div>

  {#if providers.isPending}
    <LoadingSkeleton variant="home" />
  {:else if providers.isError}
    <div class="empty">
      <p>Не вдалося завантажити джерела: {providers.error.message}</p>
      <button class="btn" onclick={() => providers.refetch()}>Спробувати знову</button>
    </div>
  {:else if !withHome.length}
    <div class="empty">
      <Layers size={32} strokeWidth={1.8} aria-hidden="true" />
      <p>Провайдери ще не завантажені.</p>
      <p class="muted">
        При першому запуску плагіни збираються у фоні. Перевірте налаштування плагінів у разі
        помилок.
      </p>
      <a class="btn" href="/settings"
        ><Settings2 size={17} strokeWidth={1.8} aria-hidden="true" /> Відкрити налаштування</a
      >
    </div>
  {:else}
    <div class="source-band">
      <ProviderPicker providers={withHome} {current} onchoose={choose} />
    </div>

    {#if continueQ.data?.length}
      <HomeShelf title="Продовжити перегляд" description="Продовжуйте з того місця, де зупинилися.">
        {#each continueQ.data as c (c.provider + c.url)}
          <Poster
            provider={c.provider}
            url={c.url}
            name={c.title}
            poster={c.poster}
            meta={c.episodeLabel ?? ""}
            progress={c.duration ? c.position / c.duration : null}
            href={titleHref(c.provider, c.url)}
            horizontal
            showProvider
          />
        {/each}
      </HomeShelf>
    {/if}

    {#if current}
      {#key current.name}
        {#if sections.data}
          {#each sections.data as s (s.index)}
            <HomeRow provider={current.name} section={s} />
          {:else}
            <div class="empty">
              <h2>Колекції відсутні</h2>
              <p class="muted">Спробуйте обрати інше джерело.</p>
              <button class="btn" onclick={() => sections.refetch()}>Оновити колекції</button>
            </div>
          {/each}
        {:else if sections.isError}
          <div class="empty">
            <h2>Не вдалося завантажити колекції</h2>
            <p class="muted">{sections.error?.message}</p>
            <button class="btn" onclick={() => sections.refetch()}>Спробувати знову</button>
          </div>
        {:else}
          <LoadingSkeleton variant="home" showProviders={false} />
        {/if}
      {/key}
    {/if}
  {/if}
</div>

<style>
  .home {
    --card-w: 156px;
    --card-w-h: 280px;
  }
  .source-band {
    padding: 18px 20px;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .source-band :global(.provider-picker) {
    margin-bottom: 0;
  }
  .page-heading h1 {
    font-size: clamp(32px, 4vw, 48px);
    letter-spacing: -0.035em;
    font-weight: 700;
  }
  .page-heading p {
    margin-top: 8px;
  }
  @media (min-width: 900px) {
    .home {
      padding-top: 48px;
      --card-w: 176px;
      --card-w-h: 304px;
    }
  }
  @media (max-width: 599px) {
    .home {
      --card-w: 140px;
      --card-w-h: 248px;
    }
    .source-band {
      padding: 12px;
    }
  }

  .top {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    margin-bottom: 28px;
    justify-content: space-between;
    gap: 12px;
  }
  .page-heading {
    margin-bottom: 0;
  }
  .browse-search {
    display: flex;
    align-items: center;
    gap: 10px;
    width: min(100%, 380px);
    min-height: 48px;
    margin-left: auto;
    padding: 4px 4px 4px 14px;
    color: var(--muted);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
  }
  .browse-search:focus-within {
    border-color: var(--accent);
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .browse-search input {
    flex: 1;
    width: 100%;
    min-width: 0;
    padding: 0;
    border: 0;
    outline: 0;
    background: transparent;
  }
  .browse-search input:focus-visible {
    outline: 0;
  }
  .browse-search button {
    min-height: 38px;
    padding: 0 12px;
    border-radius: 5px;
    color: var(--accent-ink);
    background: var(--accent);
    font-size: 13px;
    font-weight: 600;
  }
  .browse-search button:hover {
    filter: brightness(1.08);
  }
  @media (max-width: 599px) {
    .browse-search {
      width: 100%;
      margin-left: 0;
    }
  }
  .building {
    display: inline-flex;
    gap: 8px;
    align-items: center;
    font-size: 13px;
    color: var(--muted);
  }
  .building .spinner {
    width: 14px;
    height: 14px;
  }
</style>
