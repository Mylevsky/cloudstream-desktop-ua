<script lang="ts">
  import { page } from "$app/state";
  import { api } from "#lib/api";
  import { cast } from "#lib/cast.svelte";
  import { content } from "#lib/nsfw.svelte";
  import { searchState } from "#lib/search-state.svelte";
  import PluginSettings from "#lib/components/PluginSettings.svelte";
  import SourceProfiles from "#lib/components/SourceProfiles.svelte";
  import { prefs } from "#lib/player/prefs.svelte";
  import { toasts } from "#lib/toast.svelte";
  import type {
    BrowserMode,
    BrowserStatus,
    PluginInfo,
    PluginSource,
    PluginSourceMode,
    Settings,
    SkipMode,
  } from "#lib/types";
  import {
    Check,
    CheckCircle2,
    CircleAlert,
    ChevronDown,
    ChevronRight,
    ArrowLeft,
    Trash2,
    Globe,
    HardDrive,
    Layers,
    ListOrdered,
    Play,
    Puzzle,
    Search,
    RefreshCw,
    Shield,
    Smartphone,
    Square,
  } from "@lucide/svelte";
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";
  import { onDestroy } from "svelte";

  const client = useQueryClient();
  const settings = createQuery(() => ({
    queryKey: ["settings"],
    queryFn: api.settings,
  }));
  const status = createQuery(() => ({
    queryKey: ["status"],
    queryFn: api.status,
    refetchInterval: 3000,
  }));
  const plugins = createQuery(() => ({
    queryKey: ["plugins"],
    queryFn: api.plugins,
    refetchInterval: () => (status.data?.building ? 2000 : false),
  }));

  const source = createQuery(() => ({
    queryKey: ["plugin-source"],
    queryFn: api.pluginSource,
    refetchInterval: () => (status.data?.building ? 2000 : false),
  }));

  const browser = createQuery(() => ({
    queryKey: ["browser"],
    queryFn: api.browser,
    refetchInterval: 5000,
  }));

  const categories = [
    { id: "playback", label: "Відтворення", icon: Play },
    { id: "priority", label: "Пріоритет джерел", icon: ListOrdered },
    { id: "storage", label: "Сховище та Jellyfin", icon: HardDrive },
    { id: "content", label: "Контент", icon: Shield },
    { id: "browser", label: "Браузер", icon: Globe },
    { id: "plugins", label: "Плагіни", icon: Layers },
    { id: "devices", label: "Акаунт і пристрої", icon: Smartphone },
  ];
  const availableCategories = categories;
  const activeCategory = $derived(
    availableCategories.find((category) => `#settings-${category.id}` === page.url.hash)?.id ??
      "playback",
  );

  const storageQuery = createQuery(() => ({
    queryKey: ["storage-status"],
    queryFn: api.getStorageStatus,
    refetchInterval: 10000,
  }));

  let isTestingJellyfin = $state(false);
  let jellyfinTestResult = $state<{
    ok: boolean;
    serverName?: string;
    version?: string;
    error?: string;
  } | null>(null);
  let isRefreshingJellyfin = $state(false);

  function formatBytes(bytes?: number): string {
    if (!bytes || bytes <= 0) return "0 GB";
    const units = ["Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ"];
    let val = bytes;
    let idx = 0;
    while (val >= 1024 && idx < units.length - 1) {
      val /= 1024;
      idx++;
    }
    return `${val.toFixed(1)} ${units[idx]}`;
  }

  async function testJellyfinConnection(url?: string, apiKey?: string) {
    if (!url || !apiKey) {
      toasts.show("Вкажіть URL та API-ключ Jellyfin для перевірки");
      return;
    }
    isTestingJellyfin = true;
    jellyfinTestResult = null;
    try {
      const res = await api.testJellyfin({ url, apiKey });
      jellyfinTestResult = res;
      if (res.ok) {
        toasts.show(`Підключено до Jellyfin (${res.serverName ?? "OK"})`);
      } else {
        toasts.error(res.error ?? "Помилка підключення до Jellyfin");
      }
    } catch (e) {
      toasts.error(e);
      jellyfinTestResult = { ok: false, error: String(e) };
    } finally {
      isTestingJellyfin = false;
    }
  }

  async function triggerJellyfinLibraryRefresh() {
    isRefreshingJellyfin = true;
    try {
      const res = await api.refreshJellyfin();
      if (res.ok) {
        toasts.show("Запит на оновлення бібліотеки надіслано в Jellyfin 🚀");
      } else {
        toasts.error(res.error ?? "Помилка оновлення Jellyfin");
      }
    } catch (e) {
      toasts.error(e);
    } finally {
      isRefreshingJellyfin = false;
    }
  }
  let pendingSaves = $state(0);
  let saveState = $state<"idle" | "saved" | "error">("idle");
  let saveFailed = false;

  let openPlugin = $state<string | null>(null);
  let selectedRepo = $state<string | null>(null);
  let pluginSearch = $state("");
  let pluginFilter = $state<"all" | "enabled" | "disabled" | "attention">("all");
  // Keep the requested state until the runtime has actually loaded or unloaded the plugin.
  let pluginChanges = $state<Record<string, boolean>>({});
  let pluginErrors = $state<Record<string, string>>({});
  let failedIcons = $state<Record<string, string>>({});
  let failedRepoIcons = $state<Record<string, string>>({});
  const changingPlugins = $derived(Object.keys(pluginChanges).length > 0);
  let disposed = false;
  onDestroy(() => {
    disposed = true;
  });
  let log = $state<string | null>(null);
  let mpvArgs = $derived(settings.data?.mpv_extra_args.join(" ") ?? "");

  async function save(patch: Partial<Settings>) {
    if (!pendingSaves) saveFailed = false;
    pendingSaves += 1;
    saveState = "idle";
    try {
      const s = await api.saveSettings(patch);
      client.setQueryData(["settings"], s);
      // Visibility settings change what every list shows.
      if ("enabled_plugins" in patch) client.invalidateQueries();
    } catch (e) {
      saveFailed = true;
      toasts.error(e);
    } finally {
      pendingSaves -= 1;
      if (!pendingSaves) saveState = saveFailed ? "error" : "saved";
    }
  }

  async function browserAction(fn: () => Promise<BrowserStatus>) {
    try {
      client.setQueryData(["browser"], await fn());
    } catch (e) {
      toasts.error(e);
    }
  }

  const modes: { id: BrowserMode; label: string }[] = [
    { id: "headless", label: "Фоновий (без вікна)" },
    { id: "xvfb", label: "Віртуальний дисплей (Xvfb)" },
    { id: "headed", label: "Видиме вікно" },
  ];

  /** The server stops a running browser so the next request starts it in the new mode. */
  async function setMode(mode: BrowserMode) {
    await save({ chromium_mode: mode });
    client.invalidateQueries({ queryKey: ["browser"] });
  }

  function browserState(b: BrowserStatus) {
    if (!b.executable) return "Chromium не встановлено";
    if (!b.running) return "Не запущено; запускається за потреби плагіна";
    return `Працює ${b.mode === "headed" ? "у видимому вікні" : b.mode === "xvfb" ? "на віртуальному дисплеї" : "у фоновому режимі"}`;
  }

  /** A loaded plugin that won't fully link against this runtime. */
  const linkWarning = (p: PluginInfo) =>
    p.runtimeLinkage ??
    (p.linkage.length
      ? `${p.linkage.length} ${p.linkage.length === 1 ? "виклик" : "викликів"} середовища не зв'язано, напр. ${p.linkage[0]}`
      : null);

  async function setEnabled(target: PluginInfo, enabled: boolean) {
    const { id, name } = target;
    if (pluginChanges[id] !== undefined) return;
    pluginChanges[id] = enabled;
    delete pluginErrors[id];
    try {
      // This saves the preference; the host loads/unloads asynchronously after it returns.
      await api.setPluginEnabled(id, enabled);
      const deadline = Date.now() + 120_000;
      while (!disposed && Date.now() < deadline) {
        const [list, host] = await Promise.all([api.plugins(), api.status()]);
        if (disposed) return;
        client.setQueryData(["plugins"], list);
        client.setQueryData(["status"], host);
        const plugin = list.find((p) => p.id === id);
        if (!plugin)
          throw new Error(`${name} більше немає в цьому джерелі. Оновіть список плагінів.`);
        const settled =
          plugin.enabled === enabled &&
          (enabled
            ? plugin.status === "loaded" ||
              plugin.status === "error" ||
              (plugin.status === "missing" && !host.building)
            : plugin.status === "disabled");
        if (settled) {
          if (enabled && plugin.status !== "loaded") {
            throw new Error(
              plugin.error ??
                `Не вдалося завантажити ${name}. Перевірте відомості та спробуйте знову.`,
            );
          }
          toasts.show(`${name} ${enabled ? "увімкнено" : "вимкнено"}`);
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, 750));
      }
      if (!disposed)
        throw new Error(
          `Завантаження ${name} триває довше, ніж очікувалося. Оновіть список, щоб перевірити стан.`,
        );
    } catch (e) {
      if (!disposed) {
        pluginErrors[id] = e instanceof Error ? e.message : String(e);
        toasts.error(e);
      }
    } finally {
      if (!disposed) {
        delete pluginChanges[id];
        // Provider lists and settings must reflect the completed operation too.
        client.invalidateQueries();
      }
    }
  }

  async function rebuild() {
    await api.rebuild().catch(toasts.error);
    toasts.show("Перезбирання плагінів…");
    client.invalidateQueries({ queryKey: ["status"] });
  }

  /** The source the server loads plugins from; it unloads everything and loads from the new one. */
  async function setSource(patch: Partial<Settings>) {
    await save(patch);
    setTimeout(() => client.invalidateQueries(), 400);
  }

  // Repo mode: which action is running ("check", "all" or a plugin name), so its button shows a spinner.
  let working = $state<string | null>(null);
  async function repoAction(
    key: string,
    fn: () => Promise<PluginSource | { failed: Record<string, string>; source: PluginSource }>,
  ) {
    working = key;
    try {
      const r = await fn();
      if ("failed" in r) {
        client.setQueryData(["plugin-source"], r.source);
        const failed = Object.entries(r.failed);
        if (failed.length) toasts.error(new Error(failed.map(([n, e]) => `${n}: ${e}`).join("; ")));
      } else client.setQueryData(["plugin-source"], r);
    } catch (e) {
      toasts.error(e);
    } finally {
      working = null;
      // Updated plugins can add or drop providers.
      client.invalidateQueries();
    }
  }

  const sources: { id: PluginSourceMode; label: string }[] = [
    { id: "repo", label: "Онлайн-репозиторій (опубліковані плагіни)" },
    { id: "dev", label: "Локальний репозиторій розширень (gradle)" },
  ];

  const ago = (t: number) => {
    const m = Math.round((Date.now() - t) / 60000);
    if (m < 1) return "щойно";
    if (m < 60) return `${m} хв тому`;
    if (m < 1440) return `${Math.round(m / 60)} год тому`;
    return new Date(t).toLocaleDateString("uk-UA");
  };

  const src = $derived(source.data);
  const repo = $derived(src?.mode === "repo");

  const repoUrls = $derived(settings.data?.plugin_repo_urls ?? []);
  let newRepoUrl = $state("");
  async function addRepo() {
    const u = newRepoUrl.trim();
    if (!u || working !== null) return;
    working = "add-repo";
    try {
      await api.addRepository(u);
      newRepoUrl = "";
      await client.invalidateQueries();
    } catch (e) {
      toasts.error(e);
    } finally {
      working = null;
    }
  }
  async function removeRepo(u: string) {
    if (
      !confirm(
        `Видалити цей репозиторій? Плагіни, встановлені з нього, також буде видалено.\n\n${u}`,
      )
    )
      return;
    await setSource({ plugin_repo_urls: repoUrls.filter((x) => x !== u) });
    if (selectedRepo === u) selectedRepo = null;
  }

  const sessions = createQuery(() => ({ queryKey: ["auth-sessions"], queryFn: api.sessions }));
  const authInfo = createQuery(() => ({ queryKey: ["auth-info"], queryFn: api.authStatus }));
  async function signOut() {
    try {
      await api.logout();
      location.reload();
    } catch (e) {
      toasts.error(e);
    }
  }
  async function revoke(id: string, current: boolean) {
    try {
      await api.revokeSession(id);
      if (current) location.reload();
      else await sessions.refetch();
    } catch (e) {
      toasts.error(e);
    }
  }
  let currentPassword = $state("");
  let newPassword = $state("");
  let confirmPassword = $state("");
  let savingPassword = $state(false);
  async function savePassword(event: SubmitEvent) {
    event.preventDefault();
    if (newPassword !== confirmPassword) {
      toasts.show("Нові паролі не збігаються.", true);
      return;
    }
    savingPassword = true;
    try {
      await api.setPassword(newPassword, authInfo.data?.passwordSet ? currentPassword : undefined);
      currentPassword = newPassword = confirmPassword = "";
      toasts.show("Пароль збережено. На інших пристроях виконано вихід.");
      await Promise.all([authInfo.refetch(), sessions.refetch()]);
    } catch (e) {
      toasts.error(e);
    } finally {
      savingPassword = false;
    }
  }
  async function revokeAll() {
    if (!confirm("Вийти з усіх пристроїв, підключених до цього сервера, включно з цим браузером?"))
      return;
    try {
      await api.revokeAll();
      location.reload();
    } catch (e) {
      toasts.error(e);
    }
  }

  const hasUpdate = (p: PluginInfo) =>
    p.available != null && p.version != null && p.available > p.version && p.status !== "missing";

  const needsAttention = (p: PluginInfo) =>
    p.status === "error" || p.status === "missing" || (p.status === "loaded" && !!linkWarning(p));
  const scopedPlugins = $derived(
    repo && selectedRepo
      ? (plugins.data ?? []).filter((p) => p.repo === selectedRepo)
      : (plugins.data ?? []),
  );
  const visiblePlugins = $derived(
    scopedPlugins.filter((p) => {
      const query = pluginSearch.trim().toLocaleLowerCase();
      const matchesSearch =
        !query ||
        [p.name, p.description ?? "", ...p.providers].some((value) =>
          value.toLocaleLowerCase().includes(query),
        );

      return (
        matchesSearch &&
        (pluginChanges[p.id] !== undefined ||
          pluginFilter === "all" ||
          (pluginFilter === "enabled" && p.enabled) ||
          (pluginFilter === "disabled" && !p.enabled) ||
          (pluginFilter === "attention" && needsAttention(p)))
      );
    }),
  );

  /** Repo mode: the visible plugins under a heading per repo, in priority order. Dev mode: one unlabelled list. */
  const pluginGroups = $derived.by(() => {
    if (src?.mode !== "repo") return [{ key: "all", label: "", plugins: visiblePlugins }];
    const repos = src.repos ?? src.repoUrls.map((url) => ({ url, name: null }));
    const groups = repos.map((r) => ({
      key: r.url,
      label: r.name || hostOf(r.url),
      plugins: visiblePlugins.filter((p) => p.repo === r.url),
    }));
    const known = new Set(repos.map((r) => r.url));
    const other = visiblePlugins.filter((p) => !p.repo || !known.has(p.repo));
    if (other.length) groups.push({ key: "other", label: "Other", plugins: other });
    return groups.filter((g) => g.plugins.length);
  });

  function hostOf(url: string) {
    try {
      return new URL(url).host;
    } catch {
      return url;
    }
  }
</script>

<svelte:head><title>Налаштування · Cloudstream</title></svelte:head>

<div class="page settings-page">
  <header class="settings-heading">
    <div>
      <h1 class="page-title">Налаштування</h1>
      <p>Налаштуйте Cloudstream за власними уподобаннями.</p>
    </div>
    {#if activeCategory !== "priority" && activeCategory !== "devices"}
      <p class={["save-status", { failed: saveState === "error" }]} role="status">
        {#if pendingSaves}<span class="spinner"></span> Збереження…
        {:else if saveState === "error"}Не вдалося зберегти. Спробуйте знову.
        {:else if saveState === "saved"}<Check size={15} aria-hidden="true" /> Зміни збережено
        {:else}Зміни зберігаються автоматично{/if}
      </p>
    {/if}
  </header>

  <div class="settings-layout">
    <nav class="settings-nav" aria-label="Категорії налаштувань">
      {#each availableCategories as category (category.id)}
        <a
          href="#settings-{category.id}"
          data-sveltekit-reset="false"
          class={{ selected: activeCategory === category.id }}
          aria-current={activeCategory === category.id ? "location" : undefined}
        >
          <category.icon size={18} strokeWidth={1.8} aria-hidden="true" />
          <span>{category.label}</span>
        </a>
      {/each}
    </nav>
    <div class="settings-content">
      {#if settings.isPending}
        <div class="loading-settings" role="status">
          <span class="sr-only">Завантаження налаштувань…</span>
          <div></div>
          <div></div>
          <div></div>
        </div>
      {:else if settings.isError}
        <div class="load-error" role="alert">
          <h2>Не вдалося завантажити налаштування</h2>
          <p>Перевірте підключення до сервера та спробуйте знову.</p>
          <button class="btn" onclick={() => settings.refetch()}>Спробувати знову</button>
        </div>
      {/if}

      <section hidden={activeCategory !== "devices"} aria-labelledby="devices-heading">
        <h2 id="devices-heading">Акаунт і пристрої</h2>
        <p class="muted">
          Увійдіть на новому пристрої за допомогою коду авторизації. Сесії діють 30 днів.
        </p>
        {#if authInfo.data?.mode === "local"}
          <div class="setting-row">
            <span class="setting-copy"
              ><b>Підтвердити пристрій</b><small class="muted"
                >Введіть код, який відображається на екрані телевізора, смартфона чи браузера.</small
              ></span
            >
            <a class="btn" href="/activate">Ввести код</a>
          </div>
          <h3>{authInfo.data.passwordSet ? "Змінити пароль" : "Встановити пароль"}</h3>
          <p class="muted">
            Необов'язково. За допомогою пароля ви зможете авторизуватися напряму через HTTPS або на
            цьому комп'ютері без підтвердження з іншого пристрою. Збереження пароля виконує вихід на
            всіх інших пристроях.
          </p>
          <form class="password-form" onsubmit={savePassword}>
            <input type="text" autocomplete="username" value="cloudstream" hidden readonly />
            {#if authInfo.data.passwordSet}
              <label
                >Поточний пароль<input
                  type="password"
                  autocomplete="current-password"
                  bind:value={currentPassword}
                  required
                  maxlength="256"
                /></label
              >
            {/if}
            <label
              >Новий пароль<input
                type="password"
                autocomplete="new-password"
                bind:value={newPassword}
                required
                minlength="12"
                maxlength="256"
              /><small class="muted">Щонайменше 12 символів.</small></label
            >
            <label
              >Підтвердьте новий пароль<input
                type="password"
                autocomplete="new-password"
                bind:value={confirmPassword}
                required
                minlength="12"
                maxlength="256"
              /></label
            >
            <button class="btn" disabled={savingPassword}
              >{savingPassword ? "Збереження…" : "Зберегти пароль"}</button
            >
          </form>
        {/if}
        {#if authInfo.data?.workerUrl}
          <p>
            <a class="btn" href={authInfo.data.workerUrl} target="_blank" rel="noopener noreferrer"
              >Керувати акаунтом</a
            >
          </p>
        {/if}
        <h3>Підключені пристрої</h3>
        {#if sessions.isPending}<p role="status">Завантаження пристроїв…</p>
        {:else if sessions.isError}<p role="alert">Не вдалося завантажити пристрої.</p>
          <button class="btn" onclick={() => sessions.refetch()}>Спробувати знову</button>
        {:else}
          {#each sessions.data ?? [] as session (session.id)}
            <div class="setting-row">
              <span class="setting-copy"
                ><b>{session.name}{session.current ? " (цей пристрій)" : ""}</b><small class="muted"
                  >Діє до {new Date(session.expiresAt).toLocaleDateString("uk-UA")}</small
                ></span
              >
              <button class="btn" onclick={() => revoke(session.id, session.current)}>Вийти</button>
            </div>
          {/each}
        {/if}
        <div class="setting-row">
          <button class="btn" onclick={signOut}>Вийти з цього браузера</button><button
            class="btn"
            onclick={revokeAll}>Вийти з усіх пристроїв</button
          >
        </div>
      </section>

      {#if settings.data}
        {@const s = settings.data}
        <section hidden={activeCategory !== "playback"} aria-labelledby="playback-heading">
          <div class="section-heading">
            <h2 id="playback-heading">Відтворення</h2>
            <p>Налаштуйте запуск потоків та поведінку плеєра.</p>
          </div>
          <h3>Трансляція</h3>
          <label class="setting-row">
            <span class="setting-copy"
              ><b>Назва цього пристрою</b>
              <small class="muted"
                >Як цей пристрій відображається в меню трансляції на інших пристроях.</small
              ></span
            >
            <input
              type="text"
              maxlength="60"
              value={cast.name}
              onchange={(e) => cast.rename(e.currentTarget.value)}
            />
          </label>
          <label class="toggle">
            <span
              ><b>Дозволити трансляцію на цей браузер</b><small
                >Інші підключені пристрої можуть відтворювати відео тут та керувати ним, доки
                вкладка відкрита.</small
              ></span
            >
            <input
              type="checkbox"
              role="switch"
              checked={cast.receiver}
              onchange={(e) => cast.setReceiver(e.currentTarget.checked)}
            />
          </label>
          <details class="advanced">
            <summary>Вбудований плеєр браузера <ChevronDown size={17} aria-hidden="true" /></summary
            >
            <div class="advanced-fields">
              <label class="setting-row">
                <span class="setting-copy"><b>Перемотка стрілками та подвійним дотиком</b></span>
                <select
                  value={String(prefs.value.seekStep)}
                  onchange={(e) => prefs.update({ seekStep: Number(e.currentTarget.value) })}
                >
                  {#each [5, 10, 15, 30] as n (n)}<option value={String(n)}>{n} секунд</option
                    >{/each}
                </select>
              </label>
              <label class="setting-row">
                <span class="setting-copy"><b>Швидкість при утриманні відео</b></span>
                <select
                  value={String(prefs.value.holdSpeed)}
                  onchange={(e) => prefs.update({ holdSpeed: Number(e.currentTarget.value) })}
                >
                  {#each [1.5, 2, 3] as n (n)}<option value={String(n)}>{n}×</option>{/each}
                </select>
              </label>
              <label class="toggle">
                <span
                  ><b>Свайп вліво/вправо для перемотки</b><small
                    >Проведіть пальцем по відео для швидкої перемотки.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.swipeSeek}
                  onchange={(e) => prefs.update({ swipeSeek: e.currentTarget.checked })}
                />
              </label>
              <label class="toggle">
                <span
                  ><b>Свайп вгору/вниз для гучності та яскравості</b><small
                    >Сенсорні екрани: права частина для гучності, ліва для яскравості.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.volumeGesture && prefs.value.brightnessGesture}
                  onchange={(e) =>
                    prefs.update({
                      volumeGesture: e.currentTarget.checked,
                      brightnessGesture: e.currentTarget.checked,
                    })}
                />
              </label>
              <label class="setting-row">
                <span class="setting-copy"><b>Розмір субтитрів</b></span>
                <select
                  value={String(prefs.value.subtitleScale)}
                  onchange={(e) => prefs.update({ subtitleScale: Number(e.currentTarget.value) })}
                >
                  {#each [0.75, 0.9, 1, 1.15, 1.3, 1.5] as n (n)}<option value={String(n)}
                      >{Math.round(n * 100)}%</option
                    >{/each}
                </select>
              </label>
              <label class="toggle">
                <span
                  ><b>Субтитри на темній підкладці</b><small
                    >Замість контуру тексту. ASS субтитри зберігають власний стиль.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.subtitleBackground}
                  onchange={(e) => prefs.update({ subtitleBackground: e.currentTarget.checked })}
                />
              </label>
              <label class="toggle">
                <span
                  ><b>Картинка в картинці при переході з вкладки</b><small
                    >Продовжує відтворення у плаваючому вікні (якщо підтримується браузером).</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={prefs.value.autoPip}
                  onchange={(e) => prefs.update({ autoPip: e.currentTarget.checked })}
                />
              </label>
            </div>
          </details>
          <h3>Автовідтворення</h3>
          <label class="toggle">
            <span
              ><b>Автовідтворення найкращого посилання</b><small
                >Починає відтворення, щойно пріоритет посилання становить 10 або більше; решта
                довантажується у фоні.</small
              ></span
            >
            <input
              type="checkbox"
              role="switch"
              checked={s.autoplay_best}
              onchange={(e) => save({ autoplay_best: e.currentTarget.checked })}
            />
          </label>
          <label class="toggle">
            <span><b>Автоматично відтворювати наступну серію</b></span>
            <input
              type="checkbox"
              role="switch"
              checked={s.autoplay_next}
              onchange={(e) => save({ autoplay_next: e.currentTarget.checked })}
            />
          </label>
          <h3>Сегменти серій</h3>
          <label class="setting-row">
            <span class="setting-copy"
              ><b>Пропускати опенінги та ендінги в аніме</b>
              <small class="muted"
                >Часові мітки завантажуються з AniSkip для підтримуваних провайдерів. Також
                відображаються як розділи в mpv.</small
              ></span
            >
            <select
              value={s.skip_segments}
              onchange={(e) => save({ skip_segments: e.currentTarget.value as SkipMode })}
            >
              <option value="prompt">Показувати кнопку «Пропустити»</option>
              <option value="auto">Пропускати автоматично</option>
              <option value="off">Вимкнено</option>
            </select>
          </label>
          <details class="advanced">
            <summary
              >Додаткові параметри відтворення <ChevronDown size={17} aria-hidden="true" /></summary
            >
            <div class="advanced-fields">
              <label class="toggle">
                <span
                  ><b>Пропускати всі потоки через проксі-сервер</b><small
                    >Потрібно лише у випадках, коли сайт блокує прямі запити плеєра.</small
                  ></span
                >
                <input
                  type="checkbox"
                  role="switch"
                  checked={s.proxy_all}
                  onchange={(e) => save({ proxy_all: e.currentTarget.checked })}
                />
              </label>
              <label class="stack">
                <b>Додаткові параметри mpv</b>
                <small class="muted"
                  >Застосовується під час наступного запуску mpv, наприклад <code
                    >--profile=gpu-hq --fullscreen</code
                  ></small
                >
                <input
                  value={mpvArgs}
                  oninput={(e) => (mpvArgs = e.currentTarget.value)}
                  onchange={() => save({ mpv_extra_args: mpvArgs.split(/\s+/).filter(Boolean) })}
                  placeholder="--fullscreen"
                  spellcheck="false"
                />
              </label>
            </div>
          </details>
        </section>

        <section hidden={activeCategory !== "priority"} aria-labelledby="priority-heading">
          <div class="section-heading">
            <h2 id="priority-heading">Пріоритет джерел</h2>
            <p>Налаштуйте, які джерела та якість відео запускаються першими.</p>
          </div>
          <SourceProfiles settings={s} />
        </section>

        <section hidden={activeCategory !== "storage"} aria-labelledby="storage-heading">
          <div class="section-heading">
            <h2 id="storage-heading">Сховище та Jellyfin</h2>
            <p>Керуйте дисковим простором для завантажень Proxmox та синхронізацією з Jellyfin.</p>
          </div>

          {#if storageQuery.data}
            {@const st = storageQuery.data}
            {@const totalBytes = st.totalSpaceBytes || 1}
            {@const usableBytes = st.usableSpaceBytes || 0}
            {@const usedBytes = Math.max(0, totalBytes - usableBytes)}
            {@const usedPercent = Math.min(
              100,
              Math.max(0, Math.round((usedBytes / totalBytes) * 100)),
            )}

            <div class="storage-card">
              <div class="storage-card-header">
                <div class="storage-card-title">
                  <HardDrive size={20} strokeWidth={2} aria-hidden="true" />
                  <span>Підключений диск Proxmox</span>
                </div>
                <div class="storage-card-stats">
                  <b>{formatBytes(usableBytes)} вільно</b>
                  <span class="muted"
                    >з {formatBytes(totalBytes)} ({100 - usedPercent}% вільно)</span
                  >
                </div>
              </div>

              <div class="disk-meter-track">
                <div
                  class="disk-meter-fill {usedPercent > 90
                    ? 'critical'
                    : usedPercent > 75
                      ? 'warning'
                      : 'ok'}"
                  style:width="{usedPercent}%"
                ></div>
              </div>

              <div class="storage-paths-status">
                <div class="path-status-item">
                  <span class="path-label">Фільми:</span>
                  <code>{st.moviesPath}</code>
                  {#if st.moviesDirExists}
                    <span class="status-badge ok"
                      ><Check size={12} strokeWidth={2.5} /> Доступно</span
                    >
                  {:else}
                    <span class="status-badge warn"
                      ><CircleAlert size={12} strokeWidth={2.5} /> Створиться автоматично</span
                    >
                  {/if}
                </div>
                <div class="path-status-item">
                  <span class="path-label">Серіали:</span>
                  <code>{st.showsPath}</code>
                  {#if st.showsDirExists}
                    <span class="status-badge ok"
                      ><Check size={12} strokeWidth={2.5} /> Доступно</span
                    >
                  {:else}
                    <span class="status-badge warn"
                      ><CircleAlert size={12} strokeWidth={2.5} /> Створиться автоматично</span
                    >
                  {/if}
                </div>
              </div>
            </div>
          {/if}

          <h3>Папки збереження на диску</h3>
          <div class="setting-row">
            <span class="setting-copy">
              <b>Шлях до фільмів</b>
              <small class="muted"
                >Директорія, куди зберігаються завантажені фільми (наприклад, <code
                  >/data/Media/Movies</code
                >).</small
              >
            </span>
            <input
              type="text"
              class="setting-input"
              value={s.movies_path ?? "/data/Media/Movies"}
              placeholder="/data/Media/Movies"
              onchange={(e) => save({ movies_path: e.currentTarget.value.trim() })}
            />
          </div>

          <div class="setting-row">
            <span class="setting-copy">
              <b>Шлях до серіалів</b>
              <small class="muted"
                >Директорія, куди зберігаються завантажені сезони та серії (наприклад, <code
                  >/data/Media/Shows</code
                >).</small
              >
            </span>
            <input
              type="text"
              class="setting-input"
              value={s.shows_path ?? "/data/Media/Shows"}
              placeholder="/data/Media/Shows"
              onchange={(e) => save({ shows_path: e.currentTarget.value.trim() })}
            />
          </div>

          <h3>Параметри завантаження</h3>
          <label class="setting-row">
            <span class="setting-copy">
              <b>Бажана якість завантаження</b>
              <small class="muted"
                >Якість відео, якій надаватиметься пріоритет під час завантаження фільму чи сезону.</small
              >
            </span>
            <select
              value={s.preferred_download_quality ?? "best"}
              onchange={(e) => save({ preferred_download_quality: e.currentTarget.value })}
            >
              <option value="best">Найкраща доступна (Original / 1080p+)</option>
              <option value="1080">1080p Full HD</option>
              <option value="720">720p HD</option>
              <option value="480">480p SD (економія місця)</option>
            </select>
          </label>

          <label class="toggle">
            <span>
              <b>Завантажувати українські субтитри</b>
              <small
                >Автоматично зберігати виявлені субтитри поруч із відеофайлом для Jellyfin.</small
              >
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={s.download_subtitles ?? true}
              onchange={(e) => save({ download_subtitles: e.currentTarget.checked })}
            />
          </label>

          <h3>Інтеграція з медіасервером Jellyfin</h3>
          <p class="muted">
            Налаштуйте з'єднання з Jellyfin, щоб медіатека автоматично оновлювалася одразу після
            того, як фільм або сезон завантажиться на диск.
          </p>

          <div class="setting-row">
            <span class="setting-copy">
              <b>URL сервера Jellyfin</b>
              <small class="muted"
                >Локальна адреса сервера (наприклад, <code>http://192.168.0.10:8096</code>).</small
              >
            </span>
            <input
              type="url"
              class="setting-input"
              value={s.jellyfin_url ?? ""}
              placeholder="http://192.168.0.x:8096"
              onchange={(e) => save({ jellyfin_url: e.currentTarget.value.trim() })}
            />
          </div>

          <div class="setting-row">
            <span class="setting-copy">
              <b>API-ключ Jellyfin</b>
              <small class="muted"
                >Створіть у Jellyfin: <i>Панель керування → Розширені → Ключі API</i>.</small
              >
            </span>
            <input
              type="password"
              class="setting-input"
              value={s.jellyfin_api_key ?? ""}
              placeholder="Вставте API-ключ Jellyfin"
              onchange={(e) => save({ jellyfin_api_key: e.currentTarget.value.trim() })}
            />
          </div>

          <label class="toggle">
            <span>
              <b>Автоматично оновлювати бібліотеку Jellyfin</b>
              <small
                >Надсилати сигнал сканування <code>/Library/Refresh</code> одразу після завершення кожного
                завантаження.</small
              >
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={s.jellyfin_auto_refresh ?? true}
              onchange={(e) => save({ jellyfin_auto_refresh: e.currentTarget.checked })}
            />
          </label>

          <div class="jellyfin-actions">
            <button
              class="btn secondary"
              disabled={isTestingJellyfin || !s.jellyfin_url || !s.jellyfin_api_key}
              onclick={() => testJellyfinConnection(s.jellyfin_url, s.jellyfin_api_key)}
            >
              {#if isTestingJellyfin}
                <RefreshCw size={16} class="spin" />
                <span>Перевірка…</span>
              {:else}
                <CheckCircle2 size={16} />
                <span>Перевірити підключення</span>
              {/if}
            </button>

            <button
              class="btn secondary"
              disabled={isRefreshingJellyfin || !s.jellyfin_url || !s.jellyfin_api_key}
              onclick={triggerJellyfinLibraryRefresh}
            >
              {#if isRefreshingJellyfin}
                <RefreshCw size={16} class="spin" />
                <span>Оновлення…</span>
              {:else}
                <RefreshCw size={16} />
                <span>Оновити бібліотеку Jellyfin зараз</span>
              {/if}
            </button>
          </div>

          {#if jellyfinTestResult}
            <div class="jellyfin-test-box {jellyfinTestResult.ok ? 'ok' : 'error'}">
              {#if jellyfinTestResult.ok}
                <Check size={16} strokeWidth={2.5} />
                <span
                  >З'єднання успішне! Сервер: <b>{jellyfinTestResult.serverName}</b> (версія {jellyfinTestResult.version})</span
                >
              {:else}
                <CircleAlert size={16} strokeWidth={2.5} />
                <span>Помилка підключення: {jellyfinTestResult.error}</span>
              {/if}
            </div>
          {/if}
        </section>

        <section hidden={activeCategory !== "content"} aria-labelledby="content-heading">
          <div class="section-heading">
            <h2 id="content-heading">Контент</h2>
            <p>Налаштуйте, які джерела відображаються в Cloudstream.</p>
          </div>
          <label class="toggle">
            <span
              ><b>Показувати джерела для дорослих (18+)</b><small
                >Вимкнення приховує їх з головної, пошуку та списку плагінів. Такий контент не
                додається до історії. Застосовується лише на цьому пристрої.</small
              ></span
            >
            <input
              type="checkbox"
              role="switch"
              checked={content.showNsfw ?? s.show_nsfw}
              onchange={(e) => {
                content.setShowNsfw(e.currentTarget.checked);
                searchState.reset();
                client.invalidateQueries();
              }}
            />
          </label>
        </section>
      {/if}

      {#if activeCategory === "browser" && browser.isPending}
        <p class="muted" role="status">Завантаження стану браузера…</p>
      {:else if activeCategory === "browser" && browser.isError}
        <div class="load-error" role="alert">
          <h2>Не вдалося завантажити стан браузера</h2>
          <p>Перевірте з'єднання та спробуйте знову.</p>
          <button class="btn" onclick={() => browser.refetch()}>Спробувати знову</button>
        </div>
      {/if}
      {#if settings.data && browser.data}
        {@const b = browser.data}
        <section hidden={activeCategory !== "browser"} aria-labelledby="browser-heading">
          <div class="row-head">
            <div class="section-heading">
              <h2 id="browser-heading">Браузер</h2>
              <p>Обробка сайтів, які потребують повноцінного браузера.</p>
            </div>
            {#if b.running}<button class="btn" onclick={() => browserAction(api.stopBrowser)}
                ><Square size={16} strokeWidth={1.8} aria-hidden="true" /> Зупинити</button
              >{/if}
          </div>
          <p class="muted small">
            Chromium проходить перевірки Cloudflare та завантажує сторінки складних захищених
            сайтів. {browserState(b)}.
          </p>
          {#if b.error}<div class="p-error flush">{b.error}</div>{/if}
          <label class="stack">
            <b>Режим</b>
            <small class="muted"
              >Cloudflare Turnstile часто відхиляє повністю фонові браузери. Віртуальний дисплей
              успішно проходить більшість перевірок без вікна; видиме вікно дозволяє пройти
              перевірку самостійно. Якщо перевірка потребує втручання, з'явиться кнопка розв'язання
              капчі.</small
            >
            <select
              value={settings.data.chromium_mode}
              onchange={(e) => setMode(e.currentTarget.value as BrowserMode)}
            >
              {#each modes as m (m.id)}<option value={m.id}>{m.label}</option>{/each}
            </select>
          </label>
          {#if b.clearances.length}
            <div class="stack">
              <b>Збережені дозволи Cloudflare</b>
              <small class="muted"
                >Використовуються повторно, доки сайт їх приймає. Видаліть запис, щоб пройти
                перевірку знову.</small
              >
              <ul class="hosts">
                {#each b.clearances as host (host)}
                  <li>
                    <span>{host}</span><button
                      class="link-btn"
                      onclick={() => browserAction(() => api.forgetClearance(host))}>Забути</button
                    >
                  </li>
                {/each}
              </ul>
            </div>
          {/if}
        </section>
      {/if}

      <section hidden={activeCategory !== "plugins"} aria-labelledby="plugins-heading">
        <div class="row-head">
          <div class="section-heading">
            <h2 id="plugins-heading">Плагіни</h2>
            <p>Керуйте плагінами, які надають джерела для Cloudstream.</p>
          </div>
          {#if repo}
            <button
              class="btn"
              onclick={() => repoAction("check", api.checkUpdates)}
              disabled={working !== null || changingPlugins || status.data?.building}
            >
              {#if working === "check" || (working === null && status.data?.building)}<span
                  class="spinner"
                ></span> Перевірка…{:else}<RefreshCw
                  size={18}
                  strokeWidth={1.8}
                  aria-hidden="true"
                /> Перевірити оновлення{/if}
            </button>
          {:else if src}
            <button
              class="btn"
              onclick={rebuild}
              disabled={status.data?.building || changingPlugins || !src.devAvailable}
            >
              {#if status.data?.building}<span class="spinner"></span> Збирання…{:else}<RefreshCw
                  size={18}
                  strokeWidth={1.8}
                  aria-hidden="true"
                /> Перезібрати{/if}
            </button>
          {/if}
        </div>
        {#if settings.data && src}
          <div class="plugin-source">
            <label class="stack">
              <b>Джерело плагінів</b>
              {#if !status.data?.loopback}<small class="muted"
                  >Лише серверний додаток може змінювати джерело плагінів.</small
                >{/if}
              <select
                value={src.mode}
                disabled={!status.data?.loopback ||
                  changingPlugins ||
                  working !== null ||
                  status.data?.building}
                onchange={(e) =>
                  setSource({
                    plugin_source: e.currentTarget.value as PluginSourceMode,
                  })}
              >
                {#each sources as o (o.id)}<option
                    value={o.id}
                    disabled={o.id === "dev" && !src.devAvailable}
                    >{o.label}{o.id === "dev" && !src.devAvailable ? " (не знайдено)" : ""}</option
                  >{/each}
              </select>
            </label>
            <details class="source-details">
              <summary
                >{repo ? "Відомості про репозиторій" : "Відомості про локальну збірку"}<ChevronDown
                  size={16}
                  aria-hidden="true"
                /></summary
              >
              {#if repo}
                <div class="stack">
                  <small class="muted"
                    >Плагіни прив'язані до репозиторіїв. Видалення репозиторію призводить до
                    видалення його плагінів.</small
                  >
                </div>
                <p class="muted small">
                  {src.repoName ?? "Репозиторій"}{src.checkedAt
                    ? `, перевірено ${ago(src.checkedAt)}`
                    : ""}.
                  {#if status.data}Завантажено {status.data.loaded} з {status.data.plugins}.{/if}
                  Завантажені JAR-файли звіряються за контрольними сумами; останні дві версії зберігаються
                  для відкату.
                </p>
              {:else}
                <p class="muted small">
                  Зібрано з <code>{src.devPath}</code>. Будь-який новий
                  <code>&lt;Назва&gt;/build/&lt;Назва&gt;.jar</code>
                  перезавантажується автоматично, тому команди Перезібрати (або
                  <code>./gradlew :Назва:make</code>
                  для крос-платформних плагінів) достатньо.
                  {#if status.data}Завантажено {status.data.loaded} з {status.data.plugins}.{/if}
                  <button
                    class="link-btn"
                    onclick={async () => (log = log === null ? await api.buildLog() : null)}
                    >{log === null ? "Показати журнал збірки" : "Сховати журнал збірки"}</button
                  >
                </p>
                {#if log !== null}<pre class="log">{log || "Збирання ще не запускалося."}</pre>{/if}
              {/if}
            </details>
            {#if src.updates.length}
              <button
                class="btn start primary"
                onclick={() => repoAction("all", () => api.updatePlugins())}
                disabled={working !== null || changingPlugins || status.data?.building}
              >
                {#if working === "all"}<span class="spinner"></span> Оновлення…{:else}<ChevronDown
                    size={18}
                    strokeWidth={1.8}
                    aria-hidden="true"
                  /> Оновити плагіни ({src.updates.length}){/if}
              </button>
            {/if}
            {#if src.error}<div class="p-error flush" role="alert">{src.error}</div>{/if}
          </div>
        {/if}

        {#if repo && !selectedRepo}
          <div class="repositories">
            <div class="repositories-heading">
              <div>
                <h3>Репозиторії <span>{repoUrls.length}</span></h3>
                <p>Виберіть репозиторій для керування його плагінами.</p>
              </div>
            </div>
            <div class="repository-list">
              {#each repoUrls as u (u)}
                {@const info = src?.repos?.find((r) => r.url === u)}
                <div class="repository-row">
                  <button
                    class="repository-open"
                    onclick={() => {
                      selectedRepo = u;
                      pluginSearch = "";
                      pluginFilter = "all";
                    }}
                  >
                    <span class="plugin-icon" aria-hidden="true">
                      {#if info?.iconUrl && failedRepoIcons[u] !== info.iconUrl}
                        <img
                          src={info.iconUrl}
                          alt=""
                          width="40"
                          height="40"
                          loading="lazy"
                          referrerpolicy="no-referrer"
                          onerror={() => {
                            failedRepoIcons[u] = info.iconUrl!;
                          }}
                        />
                      {:else}<Layers size={21} />{/if}
                    </span>
                    <span class="repo-id"
                      ><b>{info?.name || hostOf(u)}</b><small title={u}>{u}</small
                      >{#if info?.error}<small class="p-error">{info.error}</small>{/if}</span
                    >
                    <ChevronRight class="repository-chevron" size={18} aria-hidden="true" />
                  </button>
                  <button
                    class="repository-remove"
                    aria-label="Видалити репозиторій {info?.name || hostOf(u)}"
                    disabled={changingPlugins || working !== null || status.data?.building}
                    onclick={() => removeRepo(u)}><Trash2 size={19} aria-hidden="true" /></button
                  >
                </div>
              {/each}
              {#if !repoUrls.length}<p class="repository-empty">
                  Репозиторії ще не додані. Додайте посилання нижче.
                </p>{/if}
            </div>
            <form
              class="repo-row repo-add"
              onsubmit={(e) => {
                e.preventDefault();
                addRepo();
              }}
            >
              <label class="repo-input">
                <span>Додати репозиторій</span>
                <input
                  type="text"
                  placeholder="URL репозиторію або короткий код"
                  disabled={changingPlugins || working !== null || status.data?.building}
                  bind:value={newRepoUrl}
                />
              </label>
              <button
                class="btn"
                type="submit"
                disabled={!newRepoUrl.trim() ||
                  changingPlugins ||
                  working !== null ||
                  status.data?.building}
                >{working === "add-repo" ? "Завантаження…" : "Додати"}</button
              >
            </form>
            {#if working === "add-repo"}<p class="muted small" role="status">
                Завантаження репозиторію…
              </p>{/if}
          </div>
        {:else}
          {#if repo && selectedRepo}
            <div class="repo-detail-heading">
              <button
                class="link-btn repo-back"
                onclick={() => {
                  selectedRepo = null;
                  openPlugin = null;
                }}
              >
                <ArrowLeft size={18} aria-hidden="true" /> Репозиторії</button
              >
              <h3>
                {src?.repos?.find((r) => r.url === selectedRepo)?.name || hostOf(selectedRepo)}
              </h3>
            </div>
          {/if}
          {#if plugins.isPending}
            <div class="plugin-skeleton" role="status">
              <span class="sr-only">Завантаження плагінів…</span>
              {#each [0, 1, 2] as row (row)}<div><span></span><span></span></div>{/each}
            </div>
          {:else if plugins.isError}
            <div class="plugin-empty" role="alert">
              <p>Не вдалося завантажити плагіни. Перевірте з'єднання та спробуйте знову.</p>
              <button class="btn" onclick={() => plugins.refetch()}>Спробувати знову</button>
            </div>
          {:else if !scopedPlugins.length}
            <p class="muted small">У цьому джерелі плагінів не знайдено.</p>
          {:else}
            <div class="plugin-tools">
              <label class="plugin-search">
                <Search size={18} aria-hidden="true" />
                <input
                  type="search"
                  bind:value={pluginSearch}
                  placeholder="Пошук плагінів або джерел"
                  aria-label="Пошук плагінів або джерел"
                />
              </label>
              <select bind:value={pluginFilter} aria-label="Фільтр плагінів">
                <option value="all">Усі плагіни</option>
                <option value="enabled">Увімкнені</option>
                <option value="disabled">Вимкнені</option>
                <option value="attention">Потребують уваги</option>
              </select>
            </div>
            <div class="plugin-list-heading">
              <p class="plugin-count" aria-live="polite">
                {pluginSearch.trim() || pluginFilter !== "all"
                  ? `${visiblePlugins.length} з ${scopedPlugins.length}`
                  : scopedPlugins.length} плагінів
              </p>
              <span>{scopedPlugins.filter((p) => p.status === "loaded").length} завантажено</span>
            </div>
            {#if !visiblePlugins.length}
              <div class="plugin-empty">
                <p>Жоден плагін не відповідає пошуку та фільтрам.</p>
                <button
                  class="link-btn"
                  onclick={() => {
                    pluginSearch = "";
                    pluginFilter = "all";
                  }}>Очистити пошук і фільтри</button
                >
              </div>
            {/if}
            {#each pluginGroups as g (g.key)}
              {#if g.label && !selectedRepo}<h3 class="plugin-group" title={g.key}>
                  {g.label}<span>{g.plugins.length}</span>
                </h3>{/if}
              <ul class="plugins">
                {#each g.plugins as p (p.id)}
                  {@const changing = pluginChanges[p.id] !== undefined}
                  {@const updating = working === p.id || (working === "all" && hasUpdate(p))}
                  {@const busy = changing || updating}
                  {@const warning = p.status === "loaded" && !!linkWarning(p)}
                  <li class={{ "plugin-busy": busy }}>
                    <div class="p-row">
                      <button
                        class="p-name"
                        aria-expanded={openPlugin === p.id}
                        aria-controls="plugin-details-{p.id}"
                        onclick={() => (openPlugin = openPlugin === p.id ? null : p.id)}
                      >
                        <span class="plugin-icon" aria-hidden="true">
                          {#if p.iconUrl && failedIcons[p.id] !== p.iconUrl}
                            <img
                              src={p.iconUrl}
                              alt=""
                              width="40"
                              height="40"
                              loading="lazy"
                              referrerpolicy="no-referrer"
                              onerror={() => {
                                failedIcons[p.id] = p.iconUrl!;
                              }}
                            />
                          {:else}<Puzzle size={23} strokeWidth={1.6} />{/if}
                        </span>
                        <span class="plugin-copy">
                          <span class="plugin-title"
                            ><b>{p.name}</b><ChevronDown size={15} aria-hidden="true" /></span
                          >
                          <span class="plugin-meta"
                            >{p.version != null ? `v${p.version}` : "Версія невідома"}{hasUpdate(p)
                              ? ` → v${p.available}`
                              : ""}{p.providers.length ? ` · ${p.providers.join(", ")}` : ""}{p.nsfw
                              ? " · 18+"
                              : ""}</span
                          >
                        </span>
                      </button>
                      <div class="plugin-actions">
                        {#if hasUpdate(p) || (p.status === "missing" && p.available != null && p.enabled)}
                          <button
                            class="btn small-btn"
                            onclick={() => repoAction(p.id, () => api.updatePlugins([p.id]))}
                            disabled={working !== null || changingPlugins || status.data?.building}
                          >
                            {#if updating}<span class="spinner" aria-hidden="true"
                              ></span>{/if}{p.status === "missing" ? "Встановити" : "Оновити"}
                          </button>
                        {/if}
                        <label class="plugin-toggle">
                          <input
                            type="checkbox"
                            role="switch"
                            bind:checked={
                              () => pluginChanges[p.id] ?? p.enabled,
                              (checked) => {
                                void setEnabled(p, checked);
                              }
                            }
                            disabled={busy || working !== null || status.data?.building}
                            aria-label="Увімкнути {p.name}"
                            aria-describedby="plugin-state-{p.id}"
                          />
                        </label>
                      </div>
                    </div>
                    <div class="plugin-feedback" id="plugin-state-{p.id}" aria-live="polite">
                      {#if busy}<p class="p-progress">
                          {changing
                            ? pluginChanges[p.id]
                              ? "Завантаження плагіна та його джерел…"
                              : "Вимкнення плагіна та його джерел…"
                            : "Завантаження та активація плагіна…"}
                        </p>
                      {:else if pluginErrors[p.id] || p.error}<p class="p-error">
                          <CircleAlert size={15} aria-hidden="true" />{pluginErrors[p.id] ||
                            p.error}
                        </p>
                      {:else if p.status === "error"}<p class="p-error">
                          <CircleAlert size={15} aria-hidden="true" />Не вдалося завантажити цей
                          плагін.
                        </p>
                      {:else if p.status === "missing"}<p class="p-warn">
                          <CircleAlert size={15} aria-hidden="true" />Плагін не встановлено.
                        </p>
                      {:else if warning}<p class="p-warn">
                          <CircleAlert size={15} aria-hidden="true" />{linkWarning(p)}
                        </p>{/if}
                    </div>
                    {#if openPlugin === p.id}
                      <div class="p-more" id="plugin-details-{p.id}">
                        {#if p.description}<p class="muted small">
                            {p.description}
                          </p>{/if}
                        {#if p.linkage.length > 1}
                          <pre class="log">{p.linkage.join("\n")}</pre>
                        {/if}
                        {#if p.previous != null}
                          <button
                            class="link-btn"
                            onclick={() => repoAction(p.id, () => api.rollbackPlugin(p.id))}
                            disabled={working !== null || changingPlugins || status.data?.building}
                            >Відкотити до v{p.previous}</button
                          >
                        {/if}
                        {#if p.status === "loaded" && !busy}<PluginSettings
                            id={p.id}
                            name={p.name}
                            schema={p.settingsSchema ?? []}
                          />{/if}
                      </div>
                    {/if}
                  </li>
                {/each}
              </ul>
            {/each}
          {/if}
        {/if}
      </section>
    </div>
  </div>
</div>

<style>
  .settings-page {
    max-width: 1220px;
  }
  .settings-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    margin-bottom: 36px;
  }
  .settings-heading .page-title {
    font-size: 30px;
    margin: 0 0 8px;
  }
  .settings-heading p {
    margin: 0;
    color: var(--muted);
    font-size: 14px;
  }
  .settings-heading .save-status {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 12px;
  }
  .save-status .spinner {
    width: 14px;
    height: 14px;
  }
  .settings-heading .save-status.failed {
    color: var(--danger);
  }
  .settings-layout {
    display: grid;
    grid-template-columns: 180px minmax(0, 1fr);
    gap: 36px;
    align-items: start;
  }
  .settings-nav {
    position: sticky;
    top: 32px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .settings-nav a {
    display: flex;
    align-items: center;
    gap: 11px;
    min-height: 44px;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: 14px;
    font-weight: 500;
    white-space: nowrap;
  }
  .settings-nav a:hover {
    color: var(--text);
    background: var(--surface);
  }
  .settings-nav a.selected {
    color: var(--accent);
    background: var(--accent-soft);
  }
  .settings-content {
    min-width: 0;
  }
  section {
    display: flex;
    flex-direction: column;
    gap: 20px;
    min-width: 0;
  }
  section[hidden] {
    display: none;
  }
  h2 {
    font-size: 22px;
    font-weight: 650;
  }
  h3 {
    font-size: 13px;
    font-weight: 600;
    color: var(--muted);
    margin-top: 12px;
  }
  .section-heading {
    padding-bottom: 20px;
    border-bottom: 1px solid var(--line);
  }
  .section-heading p {
    margin: 8px 0 0;
    color: var(--muted);
    font-size: 14px;
    max-width: 65ch;
  }
  section > p {
    margin: 0;
  }
  .loading-settings {
    display: grid;
    gap: 20px;
  }
  .loading-settings div {
    height: 80px;
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .loading-settings div:first-child {
    height: 64px;
    width: 70%;
  }
  .load-error {
    padding: 24px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .load-error p {
    color: var(--muted);
  }
  .setting-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(190px, 230px);
    align-items: center;
    gap: 24px;
  }
  .setting-copy {
    display: grid;
    gap: 5px;
  }
  .setting-row select {
    width: 100%;
  }
  .setting-input {
    width: 100%;
    padding: 9px 12px;
    border-radius: 8px;
    border: 1px solid var(--line);
    background: var(--surface);
    color: var(--text);
    font-size: 14px;
    font-family: inherit;
  }
  .setting-input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .storage-card {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 12px;
    padding: 18px 20px;
    margin-bottom: 24px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .storage-card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
  }
  .storage-card-title {
    display: flex;
    align-items: center;
    gap: 10px;
    font-weight: 600;
    font-size: 15px;
  }
  .storage-card-stats {
    display: flex;
    align-items: baseline;
    gap: 8px;
    font-size: 14px;
  }
  .disk-meter-track {
    width: 100%;
    height: 10px;
    background: var(--line);
    border-radius: 5px;
    overflow: hidden;
  }
  .disk-meter-fill {
    height: 100%;
    border-radius: 5px;
    transition: width 0.4s ease;
  }
  .disk-meter-fill.ok {
    background: #10b981;
  }
  .disk-meter-fill.warning {
    background: #f59e0b;
  }
  .disk-meter-fill.critical {
    background: #ef4444;
  }
  .storage-paths-status {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 6px;
    border-top: 1px solid var(--line);
    font-size: 13px;
  }
  .path-status-item {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .path-label {
    color: var(--muted);
    font-weight: 500;
  }
  .path-status-item code {
    background: var(--bg);
    padding: 2px 6px;
    border-radius: 4px;
    font-family: monospace;
    font-size: 12px;
  }
  .status-badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    border-radius: 999px;
    font-size: 11.5px;
    font-weight: 500;
  }
  .status-badge.ok {
    background: rgba(16, 185, 129, 0.15);
    color: #10b981;
  }
  .status-badge.warn {
    background: rgba(245, 158, 11, 0.15);
    color: #f59e0b;
  }
  .jellyfin-actions {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    margin-top: 8px;
  }
  .jellyfin-test-box {
    margin-top: 12px;
    padding: 12px 16px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 13.5px;
  }
  .jellyfin-test-box.ok {
    background: rgba(16, 185, 129, 0.12);
    border: 1px solid rgba(16, 185, 129, 0.3);
    color: #10b981;
  }
  .jellyfin-test-box.error {
    background: rgba(239, 68, 68, 0.12);
    border: 1px solid rgba(239, 68, 68, 0.3);
    color: #ef4444;
  }
  .advanced {
    margin-top: 12px;
    border-block: 1px solid var(--line);
  }
  .advanced summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 56px;
    cursor: pointer;
    list-style: none;
    font-size: 14px;
    font-weight: 600;
  }
  .advanced summary::-webkit-details-marker {
    display: none;
  }
  .advanced summary:hover {
    color: var(--accent);
  }
  .advanced[open] summary :global(svg) {
    transform: rotate(180deg);
  }
  .advanced-fields {
    display: grid;
    gap: 24px;
    padding: 8px 0 24px;
  }
  b {
    font-weight: 550;
    font-size: 14px;
  }
  .small {
    font-size: 13.5px;
  }
  .toggle {
    display: flex;
    min-height: 44px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    cursor: pointer;
  }
  .toggle span,
  .repo-row {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .repositories {
    margin-top: 4px;
  }
  .repositories h3 {
    margin: 0;
    color: var(--text);
    font-size: 16px;
  }
  .repositories h3 span {
    margin-left: 6px;
    color: var(--muted);
    font-size: 13px;
    font-weight: 500;
  }
  .repositories-heading p {
    margin: 5px 0 16px;
    color: var(--muted);
    font-size: 13px;
  }
  .repository-list {
    border-block: 1px solid var(--line);
  }
  .repository-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .repository-row + .repository-row {
    border-top: 1px solid var(--line);
  }
  .repository-open {
    display: flex;
    align-items: center;
    gap: 16px;
    flex: 1;
    min-width: 0;
    padding: 14px 8px;
    text-align: left;
    color: inherit;
    background: none;
    border: 0;
    cursor: pointer;
  }
  .repository-open:hover b {
    color: var(--accent);
  }
  .repository-open:hover :global(.repository-chevron) {
    color: var(--accent);
  }
  .repository-open :global(.repository-chevron) {
    flex-shrink: 0;
    color: var(--muted);
  }
  .repository-open small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .repository-remove {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    color: var(--text);
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    cursor: pointer;
  }
  .repository-remove:not(:disabled):hover {
    color: var(--text);
    background: var(--surface-3);
  }
  .repository-remove:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .repo-add {
    align-items: end;
    margin-top: 24px;
  }
  .repo-input {
    display: grid;
    flex: 1;
    min-width: 0;
    gap: 8px;
    font-size: 14px;
    font-weight: 550;
  }
  .repo-input input {
    width: 100%;
  }
  .repository-empty {
    margin: 0;
    padding: 20px 8px;
    color: var(--muted);
    font-size: 13px;
  }
  .repo-detail-heading {
    display: grid;
    justify-items: start;
    gap: 16px;
  }
  .repo-detail-heading h3 {
    margin: 0;
  }
  .repo-back {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 44px;
  }
  .repo-id {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  .repo-row input {
    flex: 1;
  }
  .stack {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }
  small {
    color: var(--muted);
    font-size: 13px;
  }
  .password-form {
    display: grid;
    gap: 16px;
    max-width: 420px;
    margin-bottom: 24px;
  }
  .password-form label {
    display: grid;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
  }
  .password-form small {
    font-weight: 400;
  }
  .password-form .btn {
    justify-self: start;
  }
  input[type="checkbox"] {
    appearance: none;
    width: 46px;
    height: 28px;
    border-radius: 999px;
    background: var(--surface-3);
    position: relative;
    flex-shrink: 0;
    cursor: pointer;
    padding: 0;
    border: 1px solid var(--muted);
    transition: background 0.15s;
  }
  input[type="checkbox"]::after {
    content: "";
    position: absolute;
    top: 2px;
    left: 2px;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: #fff;
    transition: transform 0.15s;
  }
  input[type="checkbox"]:checked {
    background: var(--accent);
    border-color: var(--accent);
  }
  input[type="checkbox"]:disabled {
    opacity: 0.5;
    cursor: wait;
  }
  input[type="checkbox"]:checked::after {
    transform: translateX(18px);
  }
  code {
    font-size: 12.5px;
    word-break: break-all;
    color: var(--muted);
  }
  .start {
    align-self: flex-start;
  }
  .row-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    padding-bottom: 20px;
    border-bottom: 1px solid var(--line);
  }
  .row-head .section-heading {
    border: 0;
    padding: 0;
  }
  .link-btn {
    color: var(--accent);
    font-weight: 600;
    min-height: 44px;
  }
  .link-btn:disabled {
    opacity: 0.5;
  }
  .small-btn {
    padding: 6px 12px;
    font-size: 13px;
    flex-shrink: 0;
  }
  .log {
    max-height: 320px;
    overflow: auto;
    font-size: 11.5px;
    background: var(--bg);
    border-radius: 8px;
    padding: 10px;
    white-space: pre-wrap;
    margin: 0;
  }
  .plugin-source {
    display: grid;
    gap: 12px;
    padding-bottom: 20px;
    border-bottom: 1px solid var(--line);
  }
  .source-details summary {
    display: flex;
    align-items: center;
    gap: 8px;
    width: fit-content;
    min-height: 36px;
    color: var(--muted);
    font-size: 13px;
    cursor: pointer;
    list-style: none;
  }
  .source-details summary::-webkit-details-marker {
    display: none;
  }
  .source-details summary:hover {
    color: var(--text);
  }
  .source-details[open] summary :global(svg) {
    transform: rotate(180deg);
  }
  .source-details[open] {
    display: grid;
    gap: 12px;
  }
  .source-details p {
    margin: 8px 0 0;
    line-height: 1.65;
  }
  .plugins {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .plugin-group {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin: 28px 0 0;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.02em;
    color: var(--text);
  }
  .plugin-group span {
    color: var(--muted);
    font-weight: 400;
  }
  .plugin-tools {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
  }
  .plugin-search {
    position: relative;
    flex: 1 1 220px;
    min-width: 0;
  }
  .plugin-search > :global(svg) {
    position: absolute;
    left: 14px;
    top: 14px;
    color: var(--muted);
    pointer-events: none;
  }
  .plugin-search input {
    width: 100%;
    padding-left: 42px;
  }
  .plugin-tools select {
    flex: 0 1 180px;
  }
  .plugin-list-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: var(--muted);
    font-size: 12.5px;
    margin-bottom: -16px;
  }
  .plugin-count {
    margin: 0;
  }
  .plugin-empty {
    padding: 20px 0;
  }
  .plugin-empty p {
    margin: 0 0 10px;
  }
  .plugins > li {
    border-bottom: 1px solid var(--line);
    padding: 12px 0;
  }
  .p-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 16px;
  }
  .p-name {
    display: flex;
    align-items: center;
    gap: 14px;
    flex: 1 1 180px;
    min-width: 0;
    text-align: left;
    padding: 4px 0;
  }
  .plugin-icon {
    display: grid;
    place-items: center;
    flex-shrink: 0;
    width: 48px;
    height: 48px;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
    color: var(--muted);
    overflow: hidden;
  }
  .plugin-icon img {
    width: 40px;
    height: 40px;
    object-fit: contain;
  }
  .plugin-copy {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }
  .plugin-title {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .plugin-title b {
    overflow-wrap: anywhere;
    font-weight: 600;
  }
  .plugin-title :global(svg) {
    flex-shrink: 0;
    color: var(--muted);
    transition: transform 150ms ease;
  }
  .p-name[aria-expanded="true"] .plugin-title :global(svg) {
    transform: rotate(180deg);
  }
  .p-name:hover .plugin-title {
    color: var(--accent);
  }
  .plugin-meta {
    color: var(--muted);
    font-size: 12.5px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .p-progress {
    color: var(--accent);
  }
  .plugin-actions {
    display: flex;
    align-items: center;
    gap: 16px;
    flex-shrink: 0;
    margin-left: auto;
  }
  .plugin-toggle {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    min-height: 44px;
    font-size: 12px;
    color: var(--muted);
    cursor: pointer;
  }
  .plugin-busy .plugin-toggle {
    color: var(--accent);
    cursor: wait;
  }
  .plugin-feedback,
  .p-more {
    margin-left: 62px;
  }
  .p-progress,
  .p-error,
  .p-warn {
    display: flex;
    align-items: flex-start;
    gap: 7px;
    margin: 6px 0 0;
    font-size: 12.5px;
    line-height: 1.6;
    overflow-wrap: anywhere;
  }
  .p-error {
    color: var(--danger);
  }
  .p-warn {
    color: #f2c14e;
  }
  .p-error :global(svg),
  .p-warn :global(svg) {
    flex-shrink: 0;
    margin-top: 2px;
  }
  .p-error.flush {
    margin: 0;
  }
  .p-more {
    display: grid;
    gap: 12px;
    margin-top: 12px;
    padding: 0 0 4px;
  }
  .plugin-skeleton {
    display: grid;
    gap: 1px;
  }
  .plugin-skeleton > div {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 20px 0;
    border-bottom: 1px solid var(--line);
  }
  .plugin-skeleton span:not(.sr-only) {
    width: 48px;
    height: 48px;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
  }
  .plugin-skeleton span + span {
    width: 45%;
    height: 32px;
  }
  @media (max-width: 480px) {
    .plugin-tools select {
      flex: 1 1 100%;
    }
    .plugin-meta {
      white-space: normal;
      overflow-wrap: anywhere;
    }
    .plugin-actions {
      gap: 10px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .plugin-toggle input,
    .plugin-toggle input::after,
    .plugin-title :global(svg) {
      transition: none;
    }
  }
  .hosts {
    list-style: none;
    margin: 4px 0 0;
    padding: 0;
  }
  .hosts li {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 2px 0;
    gap: 16px;
    border-top: 1px solid var(--line);
    font-size: 14px;
  }
  .hosts li span {
    overflow-wrap: anywhere;
    min-width: 0;
  }
  @media (max-width: 1199px) {
    .settings-layout {
      grid-template-columns: 155px minmax(0, 1fr);
      gap: 24px;
    }
    .setting-row {
      grid-template-columns: minmax(0, 1fr);
      gap: 12px;
    }
  }
  @media (max-width: 699px) {
    .settings-heading {
      flex-direction: column;
      align-items: flex-start;
      gap: 12px;
      margin-bottom: 24px;
    }
    .settings-layout {
      display: block;
    }
    .settings-nav {
      position: static;
      flex-direction: row;
      overflow-x: auto;
      margin: 0 -20px 28px;
      padding: 0 20px 12px;
      gap: 6px;
    }
    .settings-nav a {
      flex-shrink: 0;
    }
    .settings-nav a:not(.selected) {
      background: var(--surface);
    }
    .settings-content {
      padding-bottom: 24px;
    }
    .row-head {
      flex-wrap: wrap;
      gap: 16px;
    }
    .section-heading {
      padding-bottom: 20px;
    }
    h2 {
      font-size: 21px;
    }
    .plugin-tools {
      margin-top: 4px;
    }
  }
  @media (max-width: 359px) {
    .settings-nav {
      margin-inline: -16px;
      padding-inline: 16px;
    }
    .p-row {
      gap: 8px;
    }
    .small-btn {
      padding-inline: 8px;
    }
  }
</style>
