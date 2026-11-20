// Cloudstream's TvType names, grouped into readable browsing filters.
export const CONTENT_TYPES = [
  { id: "anime", label: "Аніме", types: ["Anime", "AnimeMovie", "OVA"] },
  { id: "movies", label: "Фільми", types: ["Movie", "AnimeMovie"] },
  { id: "series", label: "Серіали", types: ["TvSeries"] },
  { id: "drama", label: "Дорами", types: ["AsianDrama"] },
  { id: "cartoons", label: "Мультфільми", types: ["Cartoon"] },
  { id: "documentaries", label: "Документальні", types: ["Documentary"] },
  { id: "live", label: "Прямий ефір", types: ["Live"] },
  { id: "torrent", label: "Торренти", types: ["Torrent"] },
  { id: "adult", label: "18+", types: ["NSFW"] },
  { id: "other", label: "Інше", types: ["Others", "Music", "AudioBook", "CustomMedia"] },
] as const;

export function matchesContentType(types: string[], filter: string): boolean {
  if (filter === "all") return true;
  const group = CONTENT_TYPES.find((group) => group.id === filter);
  return !!group && types.some((type) => (group.types as readonly string[]).includes(type));
}

export function contentTypeLabels(types: string[]): string {
  return (
    CONTENT_TYPES.filter((group) => matchesContentType(types, group.id))
      .map((group) => group.label)
      .join(" · ") || "Різне"
  );
}
