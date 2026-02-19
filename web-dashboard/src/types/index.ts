// ==================== Auth ====================

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  name: string;
}

export interface LoginResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  userId: string;
  email: string;
}

export interface RegisterResponse {
  userId: string;
  email: string;
  message: string;
}

// ==================== User ====================

export interface UserProfile {
  userId: string;
  email: string;
  name: string;
  role: string;
  createdAt: string;
}

export interface SaveApiKeyRequest {
  exchange: string;
  apiKey: string;
  secretKey: string;
}

export interface ApiKeyMetadata {
  exchange: string;
  hasApiKey: boolean;
  updatedAt: string;
}

// ==================== Dashboard Overview ====================

export interface DashboardOverview {
  account: AccountSummary;
  riskBudget: RiskBudget;
  subscription: SubscriptionInfo;
  positions: OpenPositionSummary[];
}

export interface AccountSummary {
  availableBalance: number;
  openPositionCount: number;
  todayPnl: number;
  todayTradeCount: number;
}

export interface RiskBudget {
  dailyLossLimit: number;
  todayLossUsed: number;
  remainingBudget: number;
  circuitBreakerActive: boolean;
}

export interface SubscriptionInfo {
  plan: string;
  active: boolean;
  expiresAt: string | null;
}

export interface OpenPositionSummary {
  symbol: string;
  side: string;
  entryPrice: number;
  stopLoss: number | null;
  riskAmount: number | null;
  dcaCount: number | null;
  signalSource: string | null;
  entryTime: string | null;
}

// ==================== Performance ====================

export interface PerformanceStats {
  summary: PerformanceSummary;
  exitReasonBreakdown: Record<string, number>;
  signalSourceRanking: SignalSourceStats[];
  pnlCurve: PnlDataPoint[];
  symbolStats: SymbolStats[];
  sideComparison: SideComparison;
  weeklyStats: WeeklyStats[];
  monthlyStats: MonthlyStats[];
  dayOfWeekStats: DayOfWeekStats[];
  dcaAnalysis: DcaAnalysis;
}

export interface PerformanceSummary {
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRate: number;
  profitFactor: number;
  totalNetProfit: number;
  avgProfitPerTrade: number;
  totalCommission: number;
  maxWin: number;
  maxLoss: number;
  avgWin: number;
  avgLoss: number;
  riskRewardRatio: number;
  expectancy: number;
  maxConsecutiveWins: number;
  maxConsecutiveLosses: number;
  maxDrawdown: number;
  maxDrawdownPercent: number;
  maxDrawdownDays: number;
  avgHoldingHours: number;
}

export interface SignalSourceStats {
  source: string;
  trades: number;
  winRate: number;
  netProfit: number;
}

export interface PnlDataPoint {
  date: string;
  dailyPnl: number;
  cumulativePnl: number;
  drawdown: number;
  drawdownPercent: number;
}

export interface SymbolStats {
  symbol: string;
  trades: number;
  wins: number;
  winRate: number;
  netProfit: number;
  avgProfit: number;
}

export interface SideComparison {
  longStats: SideStats;
  shortStats: SideStats;
}

export interface SideStats {
  trades: number;
  wins: number;
  winRate: number;
  netProfit: number;
  avgProfit: number;
  profitFactor: number;
}

export interface WeeklyStats {
  weekStart: string;
  weekEnd: string;
  trades: number;
  netProfit: number;
  winRate: number;
}

export interface MonthlyStats {
  month: string;
  trades: number;
  netProfit: number;
  winRate: number;
}

export interface DayOfWeekStats {
  dayOfWeek: string;
  trades: number;
  netProfit: number;
  winRate: number;
}

export interface DcaAnalysis {
  noDcaTrades: number;
  noDcaWinRate: number;
  noDcaAvgProfit: number;
  dcaTrades: number;
  dcaWinRate: number;
  dcaAvgProfit: number;
}

// ==================== Trade History ====================

export interface TradeHistoryResponse {
  trades: TradeRecord[];
  pagination: Pagination;
}

export interface TradeRecord {
  tradeId: string;
  symbol: string;
  side: string;
  entryPrice: number | null;
  exitPrice: number | null;
  entryQuantity: number | null;
  netProfit: number | null;
  exitReason: string | null;
  signalSource: string | null;
  dcaCount: number | null;
  entryTime: string | null;
  exitTime: string | null;
  status: string;
}

export interface Pagination {
  page: number;
  size: number;
  totalPages: number;
  totalElements: number;
}

// ==================== Trade Events ====================

export interface TradeEvent {
  id: number;
  tradeId: string;
  eventType: string;
  binanceOrderId: string | null;
  orderSide: string | null;
  orderType: string | null;
  price: number | null;
  quantity: number | null;
  success: boolean;
  errorMessage: string | null;
  detail: string | null;
  timestamp: string;
}

// ==================== Auto Trade ====================

export interface AutoTradeStatus {
  userId: string;
  autoTradeEnabled: boolean;
}

export interface AutoTradeUpdateRequest {
  enabled: boolean;
}

export interface AutoTradeUpdateResponse {
  userId: string;
  autoTradeEnabled: boolean;
  message: string;
}

// ==================== Discord Webhook ====================

export interface UserDiscordWebhook {
  webhookId: string;
  userId: string;
  webhookUrl: string;
  name: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface WebhooksResponse {
  userId: string;
  webhooks: UserDiscordWebhook[];
  primaryWebhookId: string | null;
}

export interface CreateWebhookRequest {
  webhookUrl: string;
  name?: string;
}

export interface CreateWebhookResponse {
  webhookId: string;
  userId: string;
  name: string;
  enabled: boolean;
  message: string;
}

// ==================== System Status ====================

export interface MonitorStatus {
  monitorConnected: boolean;
  lastHeartbeat: string | null;
  secondsSinceLastHeartbeat: number | null;
  aiParserAvailable: boolean;
}

export interface StreamStatus {
  connected: boolean;
  listenKey: string | null;
  lastEventTime: string | null;
}
