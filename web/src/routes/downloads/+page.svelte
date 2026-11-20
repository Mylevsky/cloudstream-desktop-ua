<script lang="ts">
  import { api } from "#lib/api";
  import LoadingSkeleton from "#lib/components/LoadingSkeleton.svelte";
  import { toasts } from "#lib/toast.svelte";
  import type { DownloadTask } from "#lib/types";
  import {
    CheckCircle2,
    Clock,
    Download,
    Folder,
    HardDrive,
    Loader2,
    RefreshCw,
    Trash2,
    XCircle,
  } from "@lucide/svelte";
  import { createQuery, useQueryClient } from "@tanstack/svelte-query";

  const client = useQueryClient();

  const tasksQuery = createQuery(() => ({
    queryKey: ["downloads"],
    queryFn: api.downloadTasks,
    refetchInterval: 2000,
  }));

  const tasks = $derived(tasksQuery.data ?? []);
  const activeCount = $derived(
    tasks.filter(
      (t) => t.status === "downloading" || t.status === "resolving" || t.status === "queued",
    ).length,
  );
  const completedCount = $derived(tasks.filter((t) => t.status === "completed").length);

  async function cancelTask(id: string) {
    try {
      await api.cancelDownload(id);
      toasts.show("Cancelled download");
      await client.invalidateQueries({ queryKey: ["downloads"] });
    } catch (e) {
      toasts.error(e);
    }
  }

  async function clearCompleted() {
    try {
      await api.clearCompletedDownloads();
      toasts.show("Cleared finished downloads");
      await client.invalidateQueries({ queryKey: ["downloads"] });
    } catch (e) {
      toasts.error(e);
    }
  }

  function formatEp(t: DownloadTask): string {
    if (t.kind === "movie") return "Movie";
    const s = t.season != null ? `S${String(t.season).padStart(2, "0")}` : "S01";
    const e = t.episode != null ? `E${String(t.episode).padStart(2, "0")}` : "E01";
    return `${s}${e}`;
  }
</script>

<svelte:head>
  <title>Downloads · Cloudstream Desktop</title>
</svelte:head>

<div class="downloads-page">
  <div class="header">
    <div class="title-group">
      <h1>
        <Download size={26} strokeWidth={2} aria-hidden="true" />
        Downloads
      </h1>
      <p class="subtitle">
        Saved to external storage for Jellyfin
        {#if activeCount > 0}
          <span class="active-badge">{activeCount} active</span>
        {/if}
      </p>
    </div>

    <div class="header-actions">
      {#if tasks.some((t) => t.status === "completed" || t.status === "failed" || t.status === "cancelled")}
        <button
          class="btn secondary"
          onclick={clearCompleted}
          title="Clear completed and failed tasks"
        >
          <Trash2 size={16} strokeWidth={1.8} aria-hidden="true" />
          Clear finished
        </button>
      {/if}
      <button
        class="btn icon-only"
        onclick={() => client.invalidateQueries({ queryKey: ["downloads"] })}
        aria-label="Refresh downloads"
        title="Refresh downloads"
      >
        <RefreshCw size={18} strokeWidth={1.8} aria-hidden="true" />
      </button>
    </div>
  </div>

  {#if tasksQuery.isPending}
    <LoadingSkeleton variant="row" />
  {:else if tasks.length === 0}
    <div class="empty-state">
      <HardDrive size={48} strokeWidth={1.5} aria-hidden="true" />
      <h2>No downloads in queue</h2>
      <p>
        Browse any movie or TV show, and click the <b>Download</b> or <b>Download Season</b> button to
        save it for Jellyfin.
      </p>
    </div>
  {:else}
    <div class="tasks-list">
      {#each tasks as task (task.id)}
        <div class="task-card status-{task.status}">
          <div class="task-main">
            <div class="task-info">
              <div class="task-top">
                <span class="ep-badge">{formatEp(task)}</span>
                <span class="task-title">{task.title}</span>
                {#if task.episodeName}
                  <span class="ep-name">· {task.episodeName}</span>
                {/if}
              </div>

              <div class="task-meta">
                {#if task.status === "queued"}
                  <span class="status-tag queued">
                    <Clock size={13} strokeWidth={2} aria-hidden="true" /> Queued
                  </span>
                {:else if task.status === "resolving"}
                  <span class="status-tag resolving">
                    <Loader2 size={13} class="spin" strokeWidth={2} aria-hidden="true" /> Resolving source...
                  </span>
                {:else if task.status === "downloading"}
                  <span class="status-tag downloading">
                    <Download size={13} strokeWidth={2} aria-hidden="true" />
                    {task.progress.toFixed(1)}%
                  </span>
                  {#if task.speed}
                    <span class="meta-item">{task.speed}</span>
                  {/if}
                  {#if task.eta}
                    <span class="meta-item">ETA: {task.eta}</span>
                  {/if}
                {:else if task.status === "completed"}
                  <span class="status-tag completed">
                    <CheckCircle2 size={13} strokeWidth={2} aria-hidden="true" /> Completed
                  </span>
                {:else if task.status === "failed"}
                  <span class="status-tag failed">
                    <XCircle size={13} strokeWidth={2} aria-hidden="true" /> Failed
                  </span>
                  {#if task.error}
                    <span class="error-msg" title={task.error}>{task.error}</span>
                  {/if}
                {:else if task.status === "cancelled"}
                  <span class="status-tag cancelled">Cancelled</span>
                {/if}

                {#if task.filePath}
                  <span class="file-path" title={task.filePath}>
                    <Folder size={12} strokeWidth={1.8} aria-hidden="true" />
                    {task.filePath.split("/").slice(-2).join("/")}
                  </span>
                {/if}
              </div>
            </div>

            <div class="task-actions">
              {#if task.status === "downloading" || task.status === "resolving" || task.status === "queued"}
                <button
                  class="btn cancel-btn"
                  onclick={() => cancelTask(task.id)}
                  title="Cancel download"
                >
                  Cancel
                </button>
              {/if}
            </div>
          </div>

          {#if task.status === "downloading" || task.status === "resolving"}
            <div class="progress-track">
              <div
                class="progress-fill"
                style:width="{task.status === 'resolving' ? 10 : Math.max(5, task.progress)}%"
              ></div>
            </div>
          {/if}
        </div>
      {/each}
    </div>
  {/if}
</div>

<style>
  .downloads-page {
    max-width: 900px;
    margin: 0 auto;
    padding: 24px 20px 48px;
  }
  .header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
    margin-bottom: 24px;
    flex-wrap: wrap;
  }
  .title-group h1 {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 26px;
    font-weight: 700;
    margin: 0 0 6px;
    color: var(--text);
  }
  .subtitle {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--muted);
    font-size: 14px;
    margin: 0;
  }
  .active-badge {
    background: var(--accent);
    color: #fff;
    padding: 2px 8px;
    border-radius: 12px;
    font-size: 11px;
    font-weight: 600;
  }
  .header-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 14px;
    border-radius: var(--radius-sm);
    font-size: 13px;
    font-weight: 600;
    border: 1px solid var(--line);
    background: var(--surface-2);
    color: var(--text);
    cursor: pointer;
    transition:
      background 150ms,
      border-color 150ms;
  }
  .btn:hover {
    background: var(--surface-hover, #2a2b33);
    border-color: var(--line-hover, #444);
  }
  .btn.icon-only {
    padding: 8px;
  }
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    padding: 80px 20px;
    color: var(--muted);
  }
  .empty-state h2 {
    font-size: 18px;
    font-weight: 600;
    color: var(--text);
    margin: 16px 0 6px;
  }
  .empty-state p {
    font-size: 14px;
    max-width: 420px;
    line-height: 1.5;
    margin: 0;
  }
  .tasks-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .task-card {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
    transition: border-color 150ms;
  }
  .task-card:hover {
    border-color: var(--line-strong, #3a3b44);
  }
  .task-main {
    padding: 14px 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
  }
  .task-info {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }
  .task-top {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .ep-badge {
    background: var(--surface-2);
    color: var(--accent);
    border: 1px solid var(--line);
    padding: 2px 6px;
    border-radius: 4px;
    font-size: 11.5px;
    font-weight: 700;
    font-family: monospace;
  }
  .task-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--text);
  }
  .ep-name {
    font-size: 13px;
    color: var(--muted);
  }
  .task-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 12.5px;
    color: var(--muted);
    flex-wrap: wrap;
  }
  .status-tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 7px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 600;
  }
  .status-tag.queued {
    background: #374151;
    color: #9ca3af;
  }
  .status-tag.resolving {
    background: #1e3a8a;
    color: #93c5fd;
  }
  .status-tag.downloading {
    background: #065f46;
    color: #6ee7b7;
  }
  .status-tag.completed {
    background: #14532d;
    color: #86efac;
  }
  .status-tag.failed {
    background: #7f1d1d;
    color: #fca5a5;
  }
  .status-tag.cancelled {
    background: #374151;
    color: #9ca3af;
  }
  .file-path {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: var(--muted);
    font-family: monospace;
    font-size: 11px;
  }
  .cancel-btn {
    font-size: 12px;
    padding: 5px 10px;
    color: #f87171;
  }
  .cancel-btn:hover {
    background: #7f1d1d33;
    border-color: #f8717155;
  }
  .progress-track {
    width: 100%;
    height: 4px;
    background: var(--surface-2);
    overflow: hidden;
  }
  .progress-fill {
    height: 100%;
    background: var(--accent);
    transition: width 250ms ease;
  }
  :global(.spin) {
    animation: spin 1s linear infinite;
  }
  @keyframes spin {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
</style>
