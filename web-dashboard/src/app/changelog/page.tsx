"use client";

import { useEffect, useState } from "react";
import { getChangelogs } from "@/lib/api";
import type { ChangelogEntry } from "@/types";
import { useT } from "@/lib/i18n/i18n-context";

const categoryColors: Record<string, string> = {
  FEATURE: "bg-green-500/20 text-green-400",
  UPDATE: "bg-blue-500/20 text-blue-400",
  FIX: "bg-yellow-500/20 text-yellow-400",
  SECURITY: "bg-red-500/20 text-red-400",
};

export default function ChangelogPage() {
  const { t } = useT();
  const [entries, setEntries] = useState<ChangelogEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getChangelogs()
      .then(setEntries)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div className="p-6 text-muted-foreground">{t("common.loading")}</div>;
  }

  return (
    <div className="p-6 max-w-3xl">
      <h1 className="text-2xl font-bold mb-6">{t("nav.changelog")}</h1>

      {entries.length === 0 ? (
        <div className="text-muted-foreground">{t("common.noData")}</div>
      ) : (
        <div className="space-y-6">
          {entries.map((entry) => (
            <div key={entry.id} className="bg-card border border-border rounded-lg p-6">
              <div className="flex items-center gap-3 mb-3">
                <span className="text-lg font-bold">v{entry.version}</span>
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${categoryColors[entry.category] ?? "bg-muted text-muted-foreground"}`}>
                  {entry.category}
                </span>
                <span className="text-xs text-muted-foreground ml-auto">
                  {entry.publishedAt ? new Date(entry.publishedAt).toLocaleDateString() : ""}
                </span>
              </div>
              <h2 className="text-base font-semibold mb-2">{entry.title}</h2>
              <div className="text-sm text-muted-foreground whitespace-pre-wrap">
                {entry.content}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
