"use client";

import { useT } from "@/lib/i18n/i18n-context";
import { useScrollReveal } from "@/hooks/use-scroll-reveal";

/**
 * Lido-style two-column feature blocks with huge bold typography.
 */
export function FeaturesSection() {
  const { t } = useT();
  const ref = useScrollReveal();

  const blocks = [
    {
      bigLabelKey: "landing.featBigAutoExec",
      bigDesc: t("landing.featureAutoExecDesc"),
      rightTitle: t("landing.featureNonCustodialTitle"),
      rightDesc: t("landing.featureNonCustodialDesc"),
    },
    {
      bigLabelKey: "landing.featBigSmartRisk",
      bigDesc: t("landing.featureRiskMgmtDesc"),
      rightTitle: t("landing.featureDcaTitle"),
      rightDesc: t("landing.featureDcaDesc"),
    },
  ];

  return (
    <section id="features" className="relative z-10 px-6 md:px-10">
      <div ref={ref} className="scroll-reveal mx-auto max-w-[1400px]">
        {blocks.map((block, i) => (
          <div
            key={i}
            className="grid grid-cols-1 items-center gap-10 border-t border-black/[0.08] py-20 md:grid-cols-2 md:gap-10"
          >
            {/* Left — huge typography */}
            <div>
              <div
                className="whitespace-pre-line text-6xl font-extrabold leading-[0.95] tracking-tight text-black sm:text-7xl lg:text-8xl"
                style={{ letterSpacing: "-0.01em" }}
              >
                {t(block.bigLabelKey)}
              </div>
              <p className="mt-4 max-w-[480px] text-base leading-relaxed text-gray-500 lg:text-lg">
                {block.bigDesc}
              </p>
            </div>

            {/* Right — supporting info */}
            <div>
              <h3
                className="text-2xl font-normal leading-tight tracking-tight text-black sm:text-3xl lg:text-4xl"
                style={{ letterSpacing: "-0.01em" }}
              >
                {block.rightTitle}
              </h3>
              <p className="mt-4 text-sm leading-relaxed text-gray-400 lg:text-base">
                {block.rightDesc}
              </p>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
