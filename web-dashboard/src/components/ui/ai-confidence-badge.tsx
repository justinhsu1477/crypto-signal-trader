"use client";

import { Badge } from "@/components/ui/badge";

interface AiConfidenceBadgeProps {
  confidence: number | null | undefined;
  reasoning: string | null | undefined;
}

function getConfidenceColor(confidence: number): string {
  if (confidence >= 80) return "bg-emerald-500/15 text-emerald-500 border-emerald-500/25";
  if (confidence >= 60) return "bg-yellow-500/15 text-yellow-500 border-yellow-500/25";
  if (confidence >= 40) return "bg-orange-500/15 text-orange-500 border-orange-500/25";
  return "bg-red-500/15 text-red-500 border-red-500/25";
}

export function AiConfidenceBadge({ confidence, reasoning }: AiConfidenceBadgeProps) {
  if (confidence == null) return null;

  return (
    <Badge
      className={`${getConfidenceColor(confidence)} cursor-default`}
      title={reasoning ?? undefined}
    >
      AI {confidence}
    </Badge>
  );
}
