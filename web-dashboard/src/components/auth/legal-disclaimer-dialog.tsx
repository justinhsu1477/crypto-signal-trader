"use client";

import { useState, useRef, useCallback } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { AlertTriangle, Shield, FileText, ChevronDown } from "lucide-react";

interface LegalDisclaimerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAgree: () => void;
}

export function LegalDisclaimerDialog({
  open,
  onOpenChange,
  onAgree,
}: LegalDisclaimerDialogProps) {
  const [hasScrolledToBottom, setHasScrolledToBottom] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const threshold = 50;
    const isAtBottom =
      el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
    if (isAtBottom) {
      setHasScrolledToBottom(true);
    }
  }, []);

  const scrollToBottom = () => {
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: "smooth",
    });
  };

  const handleAgree = () => {
    onAgree();
    onOpenChange(false);
    setHasScrolledToBottom(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      setHasScrolledToBottom(false);
    }
    onOpenChange(newOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="sm:max-w-2xl max-h-[90vh] flex flex-col bg-zinc-950 border-white/10"
      >
        <DialogHeader>
          <DialogTitle className="text-xl font-bold flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-amber-400" />
            服務條款與風險聲明
          </DialogTitle>
          <DialogDescription>
            請仔細閱讀以下條款，滾動至底部後方可同意
          </DialogDescription>
        </DialogHeader>

        {/* Scrollable content */}
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="flex-1 overflow-y-auto pr-2 space-y-6 text-sm text-zinc-300 leading-relaxed max-h-[55vh] scrollbar-thin scrollbar-thumb-white/10"
        >
          {/* Section 1: Risk Warning */}
          <section>
            <h3 className="flex items-center gap-2 text-base font-semibold text-amber-400 mb-3 sticky top-0 bg-zinc-950 py-2">
              <AlertTriangle className="h-4 w-4" />
              一、投資風險警告
            </h3>
            <div className="space-y-2 pl-1">
              <p>
                <strong>1.1</strong>{" "}
                加密貨幣交易具有極高風險，價格可能在短時間內劇烈波動。您可能損失部分或全部投資本金。請僅使用您能承受損失的閒置資金進行交易。
              </p>
              <p>
                <strong>1.2</strong>{" "}
                本平台提供槓桿交易功能（最高可達 20
                倍），槓桿會同時放大收益與虧損。在極端市場條件下，您的損失可能超過初始保證金。
              </p>
              <p>
                <strong>1.3</strong>{" "}
                本平台提供的交易訊號、AI
                分析及任何市場資訊，均不構成投資建議、財務建議或交易推薦。所有交易決定應由您自行判斷並承擔全部責任。
              </p>
              <p>
                <strong>1.4</strong>{" "}
                過去的交易績效不代表未來表現。任何歷史數據、回測結果或績效統計僅供參考，不保證未來能獲得相同或類似的結果。
              </p>
              <p>
                <strong>1.5</strong>{" "}
                自動跟單交易系統可能因技術故障、網路延遲、API
                斷線、交易所維護或其他不可預見的技術問題而無法正常執行，導致下單失敗、延遲成交或異常交易。
              </p>
              <p>
                <strong>1.6</strong>{" "}
                止損（Stop
                Loss）與止盈（Take Profit）訂單不保證在設定價格成交。在市場劇烈波動時，可能出現滑點（Slippage），實際成交價格可能與預設價格有顯著差異。
              </p>
            </div>
          </section>

          {/* Section 2: Terms of Service */}
          <section>
            <h3 className="flex items-center gap-2 text-base font-semibold text-blue-400 mb-3 sticky top-0 bg-zinc-950 py-2">
              <FileText className="h-4 w-4" />
              二、服務條款
            </h3>
            <div className="space-y-2 pl-1">
              <p>
                <strong>2.1 年齡限制：</strong>
                您必須年滿 18
                歲（或您所在司法管轄區的法定成年年齡）方可使用本平台服務。
              </p>
              <p>
                <strong>2.2 地區限制：</strong>
                加密貨幣交易在某些國家或地區可能受到限制或禁止。您有責任確認您所在地區的法律法規是否允許使用本平台服務及進行加密貨幣交易。
              </p>
              <p>
                <strong>2.3 監管聲明：</strong>
                本平台並非持牌金融機構，不受任何國家或地區金融監管機構（包括但不限於台灣金融監督管理委員會）的監管。本平台不提供受監管的金融服務。
              </p>
              <p>
                <strong>2.4 帳號責任：</strong>
                您有責任妥善保管帳號資料及密碼。因帳號被盜用或未經授權使用所造成的任何損失，本平台概不負責。
              </p>
              <p>
                <strong>2.5 API Key 使用：</strong>
                您提供的交易所 API Key
                僅用於執行交易指令。強烈建議您在交易所設定 API Key
                時，僅開啟期貨交易權限，並關閉提幣權限，同時設定 IP
                白名單以降低風險。
              </p>
              <p>
                <strong>2.6 免責範圍：</strong>
                本平台不對以下情形承擔責任：（a）因市場波動造成的任何交易損失；（b）因技術故障、系統維護或第三方服務中斷導致的交易執行異常；（c）因交易所規則變更影響的交易結果；（d）因您未正確設定交易參數而產生的損失。
              </p>
              <p>
                <strong>2.7 服務變更：</strong>
                本平台保留隨時修改、暫停或終止全部或部分服務的權利，且無需事先通知。
              </p>
              <p>
                <strong>2.8 帳號終止：</strong>
                本平台有權在以下情形終止您的帳號：違反服務條款、從事欺詐行為、長期未使用帳號，或基於法律要求。
              </p>
            </div>
          </section>

          {/* Section 3: Privacy Policy */}
          <section>
            <h3 className="flex items-center gap-2 text-base font-semibold text-emerald-400 mb-3 sticky top-0 bg-zinc-950 py-2">
              <Shield className="h-4 w-4" />
              三、隱私權政策
            </h3>
            <div className="space-y-2 pl-1">
              <p>
                <strong>3.1 資料收集：</strong>
                本平台收集以下資料以提供服務：電子郵件地址、姓名、交易所
                API Key（加密儲存）、交易紀錄及系統使用記錄。
              </p>
              <p>
                <strong>3.2 資料加密：</strong>
                您的交易所 API Key 採用 AES-256-GCM
                工業級加密標準儲存，每次加密使用隨機初始化向量（IV），確保即使相同的
                Key 在資料庫中也呈現不同的密文。
              </p>
              <p>
                <strong>3.3 資料使用：</strong>
                您的資料僅用於：（a）提供及改善本平台服務；（b）執行交易指令；（c）發送系統通知及交易報告。
              </p>
              <p>
                <strong>3.4 第三方分享：</strong>
                本平台不會將您的個人資料出售或分享給任何第三方，除非：（a）獲得您的明確同意；（b）基於法律要求或司法命令。
              </p>
              <p>
                <strong>3.5 資料保留：</strong>
                您的帳號資料將在帳號存續期間保留。帳號刪除後，相關資料將在合理期限內從系統中移除。
              </p>
              <p>
                <strong>3.6 Cookie：</strong>
                本平台使用必要的 Cookie 及 Local Storage
                以維持您的登入狀態及偏好設定，不使用第三方追蹤 Cookie。
              </p>
            </div>
          </section>

          {/* Final statement */}
          <div className="border-t border-white/10 pt-4 pb-2">
            <p className="text-zinc-400 text-xs">
              最後更新日期：2025 年 2 月 | 版本 1.0
            </p>
            <p className="text-zinc-400 text-xs mt-1">
              如您對以上條款有任何疑問，請透過 support@hook-fi.com 與我們聯繫。
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="flex flex-col gap-3 pt-2 border-t border-white/10">
          {!hasScrolledToBottom && (
            <button
              onClick={scrollToBottom}
              className="flex items-center justify-center gap-1 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
            >
              <ChevronDown className="h-3 w-3 animate-bounce" />
              請向下滾動閱讀完整條款
            </button>
          )}
          <div className="flex gap-3">
            <Button
              variant="outline"
              className="flex-1 border-white/10 hover:bg-white/5"
              onClick={() => handleOpenChange(false)}
            >
              不同意
            </Button>
            <Button
              className="flex-1 bg-emerald-600 hover:bg-emerald-500 text-white font-medium"
              disabled={!hasScrolledToBottom}
              onClick={handleAgree}
            >
              {hasScrolledToBottom
                ? "我已閱讀並同意全部條款"
                : "請先閱讀完整條款"}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
