"use client";

import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
} from "react";
import translations, {
  type Locale,
  locales,
  htmlLangMap,
} from "./translations";

interface I18nContextType {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}

const I18N_STORAGE_KEY = "locale";
const DEFAULT_LOCALE: Locale = "en";

const I18nContext = createContext<I18nContextType | null>(null);

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(DEFAULT_LOCALE);

  // Read from localStorage on mount
  useEffect(() => {
    const stored = localStorage.getItem(I18N_STORAGE_KEY) as Locale | null;
    if (stored && locales.includes(stored)) {
      setLocaleState(stored);
    }
  }, []);

  // Update html lang attribute whenever locale changes
  useEffect(() => {
    document.documentElement.lang = htmlLangMap[locale];
  }, [locale]);

  const setLocale = useCallback((newLocale: Locale) => {
    localStorage.setItem(I18N_STORAGE_KEY, newLocale);
    setLocaleState(newLocale);
  }, []);

  // Flatten nested translations into "module.key" -> value map
  const flatMap = useMemo(() => {
    const map: Record<string, string> = {};
    for (const [module, keys] of Object.entries(translations)) {
      for (const [key, localeValues] of Object.entries(keys)) {
        const value = (localeValues as Record<Locale, string>)[locale];
        map[`${module}.${key}`] = value;
      }
    }
    return map;
  }, [locale]);

  const t = useCallback(
    (key: string, params?: Record<string, string | number>): string => {
      let value = flatMap[key];
      if (!value) {
        console.warn(`[i18n] Missing translation: "${key}" for locale "${locale}"`);
        return key;
      }
      if (params) {
        for (const [paramKey, paramValue] of Object.entries(params)) {
          value = value.replace(`{${paramKey}}`, String(paramValue));
        }
      }
      return value;
    },
    [flatMap, locale],
  );

  return (
    <I18nContext.Provider value={{ locale, setLocale, t }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useT() {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useT must be used within I18nProvider");
  return ctx;
}
