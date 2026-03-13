// ==================== Auth ====================

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  name: string;
  termsAccepted: boolean;
}

export interface LoginResponse {
  userId: string;
  email: string;
  role: string;
  expiresIn: number;
}

export interface RegisterResponse {
  userId: string;
  email: string;
  message: string;
  needsVerification: boolean;
}

export interface VerifyEmailRequest {
  email: string;
  code: string;
}

export interface ResendCodeRequest {
  email: string;
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
  autoTradeEnabled: boolean;
  discordNotificationEnabled: boolean;
  hasBinanceApiKey: boolean;
  hasDiscordWebhook: boolean;
  positions: OpenPositionSummary[];
}

export interface AccountSummary {
  availableBalance: number;
  openPositionCount: number;
  todayPnl: number;
  todayTradeCount: number;
  totalMarginUsed: number;
  marginRatio: number;
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
  // AI 訊號評分
  aiConfidence: number | null;
  aiReasoning: string | null;
  // 即時市場數據
  markPrice: number | null;
  unrealizedPnl: number | null;
  positionValue: number | null;
  marginUsed: number | null;
  entryQuantity: number | null;
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
  // 手續費明細
  grossProfit: number | null;
  entryCommission: number | null;
  exitCommission: number | null;
  totalCommission: number | null;
  leverage: number | null;
  // AI 訊號評分
  aiConfidence: number | null;
  aiReasoning: string | null;
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

// ==================== LINE Binding ====================

export interface LineBindingStatus {
  userId: string;
  bound: boolean;
  lineNotificationEnabled: boolean;
  displayName?: string;
  linkedAt?: string;
}

export interface LineLinkingCodeResponse {
  code: string;
  expiresInMinutes: number;
  message: string;
}

// ==================== Trade Settings ====================

export interface UserTradeSettings {
  userId: string;
  riskPercent: number;
  maxLeverage: number;
  maxDcaLayers: number;
  maxPositionSizeUsdt: number;
  dailyLossLimitUsdt?: number;
  dcaRiskMultiplier?: number;
  dailyLossPercent?: number;
  maxPositionPercent?: number;
  allowedSymbols: string[];
  autoSlEnabled: boolean;
  autoTpEnabled: boolean;
  updatedAt?: string;
}

export interface UpdateTradeSettingsRequest {
  riskPercent?: number;
  maxLeverage?: number;
  maxDcaLayers?: number;
  maxPositionSizeUsdt?: number;
  dailyLossLimitUsdt?: number;
  dcaRiskMultiplier?: number;
  dailyLossPercent?: number;
  maxPositionPercent?: number;
  allowedSymbols?: string[];
  autoSlEnabled?: boolean;
  autoTpEnabled?: boolean;
}

export interface TradeSettingsDefaults {
  planId: string;
  maxRiskPercent: number;
  maxPositions: number;
  maxSymbols: number;
  dcaLayersAllowed: number;
}

// ==================== Subscription ====================

export interface PlanInfo {
  planId: string;
  name: string;
  priceMonthly: number;
  priceUsdt: number | null;
  maxPositions: number;
  maxSymbols: number;
  dcaLayersAllowed: number;
  maxRiskPercent: number;
  paymentLinkUrl: string | null;
  current: boolean;
}

export interface SubscriptionStatusDetail {
  planId: string | null;
  planName: string | null;
  status: string;
  currentPeriodEnd: string | null;
  active: boolean;
  network: string | null;
}

export interface CryptoCheckoutInfo {
  planId: string;
  planName: string;
  amountUsdt: number;
  walletAddress: string;
  network: string;
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

// ==================== Referral ====================

export type ReferralStatusEnum = "NOT_STARTED" | "PENDING" | "VERIFIED";

export interface ReferralStatusResponse {
  status: ReferralStatusEnum;
  exchangeUid: string | null;
  verifiedAt: string | null;
  referralLink: string;
  referralCode: string;
}

export interface SubmitUidRequest {
  exchangeUid: string;
}

// ==================== Admin ====================

export interface AdminSystemOverview {
  totalUsers: number;
  activeUsers: number;
  usersWithOpenPositions: number;
  totalOpenPositions: number;
  totalClosedTrades: number;
  totalNetProfit: number;
  todayNetProfit: number;
  weekNetProfit: number;
  monthNetProfit: number;
  todayTradeCount: number;
  userSummaries: UserTradingSummary[];
}

export interface UserTradingSummary {
  userId: string;
  email: string;
  name: string;
  enabled: boolean;
  autoTradeEnabled: boolean;
  openPositionCount: number;
  closedTradeCount: number;
  totalNetProfit: number;
  todayPnl: number;
  weekPnl: number;
  monthPnl: number;
  todayTradeCount: number;
  // 健康度指標
  hasBinanceApiKey: boolean;
  circuitBreakerActive: boolean;
  lastTradeAt: string | null;
  consecutiveLosses: number;
}

export interface AdminUserListResponse {
  users: AdminUserSummary[];
  totalUsers: number;
  activeUsers: number;
  adminUsers: number;
}

export interface AdminUserSummary {
  userId: string;
  email: string;
  name: string;
  role: string;
  enabled: boolean;
  emailVerified: boolean;
  autoTradeEnabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  loginMethods: string[];
}

export interface AdminUpdateUserRequest {
  enabled?: boolean;
  autoTradeEnabled?: boolean;
  role?: string;
}

// ─── Admin User Detail ───

export interface AdminUserDetailResponse {
  userId: string;
  email: string;
  name: string;
  role: string;
  enabled: boolean;
  emailVerified: boolean;
  autoTradeEnabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  passwordChangedAt: string | null;
  hasPassword: boolean;
  loginMethods: string[];
  oauthProviders: OAuthProviderInfo[];
  lineBinding: LineBindingDetailInfo | null;
  apiKeys: ApiKeyDetailInfo[];
  discordWebhooks: DiscordWebhookDetailInfo[];
  notificationPreferences: NotificationPreferencesDetailInfo | null;
  tradeSettings: UserTradeSettings;
}

export interface OAuthProviderInfo {
  provider: string;
  displayName: string;
  email: string | null;
  createdAt: string | null;
}

export interface LineBindingDetailInfo {
  displayName: string;
  enabled: boolean;
  linkedAt: string | null;
}

export interface ApiKeyDetailInfo {
  exchange: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface DiscordWebhookDetailInfo {
  webhookId: string;
  name: string;
  enabled: boolean;
  webhookUrlPreview: string;
  createdAt: string | null;
}

export interface NotificationPreferencesDetailInfo {
  tradeExecution: boolean;
  slTpTriggered: boolean;
  protectionLost: boolean;
  dailyReport: boolean;
  streamStatus: boolean;
  systemAlert: boolean;
}

export interface AdminPendingReferral {
  userId: string;
  name: string;
  email: string | null;
  exchangeUid: string;
  submittedAt: string;
}

export interface AdminVerifyRequest {
  userId: string;
  approved: boolean;
  notes?: string;
}

// ==================== Admin Subscriptions ====================

export interface AdminSubscriptionListResponse {
  subscriptions: AdminSubscriptionSummary[];
  totalUsers: number;
  activeSubscriptions: number;
  lifetimeSubscriptions: number;
}

export interface AdminSubscriptionSummary {
  userId: string;
  email: string;
  name: string;
  enabled: boolean;
  planId: string | null;
  planName: string | null;
  status: string;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  subscriptionCreatedAt: string | null;
  totalPayments: number;
  totalAmountPaid: number;
}

export interface AdminPaymentHistoryResponse {
  userId: string;
  email: string;
  name: string;
  payments: AdminPaymentRecord[];
  totalPayments: number;
  totalAmountPaid: number;
}

export interface AdminPaymentRecord {
  id: number;
  txHash: string;
  network: string;
  walletAddress: string;
  amount: number;
  currency: string;
  status: string;
  paidAt: string | null;
  createdAt: string;
  subscriptionId: number | null;
  planId: string | null;
}

export interface AdminActivateRequest {
  planId: string;
  days?: number;
}

export interface AdminSubscriptionActionResponse {
  userId: string;
  planId: string;
  status: string;
  currentPeriodEnd: string | null;
  message: string;
}

// ==================== Public Status ====================

export interface PublicServiceStatus {
  name: string;
  status: string;       // UP / DEGRADED / DOWN
  description: string;
}

export interface PublicStatusResponse {
  overallStatus: string; // UP / DEGRADED
  services: PublicServiceStatus[];
  checkedAt: string;     // ISO 8601
}

// ==================== System Health ====================

export interface SystemHealthResponse {
  status: string;
  database: {
    status: string;
    latencyMs: number;
    error?: string;
  };
  binanceApi: {
    status: string;
    weightUsed: number;
    weightRemaining: number;
    usagePercent: string;
    warning?: string;
  };
}

export interface StreamStatusResponse {
  mode: string;
  connected: boolean;
  totalStreams: number;
  shuttingDown: boolean;
  streams: Record<string, {
    userId: string;
    connected: boolean;
    listenKey: string | null;
    lastEventTime: string | null;
    reconnectCount: number;
  }>;
}

// ─── Database Stats ───

export interface DatabaseStatsResponse {
  totalSizeBytes: number;
  storageLimitBytes: number;
  usagePercent: number;
  tables: DatabaseTableStats[];
}

export interface DatabaseTableStats {
  tableName: string;
  rowCount: number;
  totalBytes: number;
}

// ==================== Announcements ====================

export interface AnnouncementResponse {
  id: number;
  title: string;
  content: string;
  category: "GENERAL" | "MAINTENANCE" | "UPDATE" | "URGENT" | "PROMOTION";
  priority: "LOW" | "NORMAL" | "HIGH" | "CRITICAL";
  channels: string;
  imageUrl?: string;
  status: "DRAFT" | "PUBLISHED" | "ARCHIVED";
  publishedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  readCount?: number;
  isRead?: boolean;
}

export interface CreateAnnouncementRequest {
  title: string;
  content: string;
  category: string;
  priority: string;
  channels: string;
  imageUrl?: string;
}

export interface AnnouncementListResponse {
  announcements: AnnouncementResponse[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  unreadCount: number;
}

// ==================== Admin Send Notification ====================

export interface AdminSendNotificationRequest {
  userIds: string[];
  title: string;
  message: string;
  color?: "GREEN" | "BLUE" | "YELLOW" | "RED";
}

export interface AdminSendNotificationResponse {
  message: string;
  totalUsers: number;
  successCount: number;
  failCount: number;
  invalidUserIds: string[];
}

// ==================== Admin Metrics ====================

export interface AdminMetricsResponse {
  orders: {
    total: number;
    success: number;
    failed: number;
    successRate: number;
  };
  signals: {
    total: number;
    byType: Record<string, number>;
  };
  notifications: {
    total: number;
    byChannel: Record<string, number>;
    failRate: number;
  };
  api: {
    avgLatencyMs: number;
    p99LatencyMs: number;
    totalCalls: number;
  };
  system: {
    uptimeSeconds: number;
  };
}

// ─── Broadcast Logs ───

export interface BroadcastLogSummary {
  id: number;
  signalAction: string;
  symbol: string;
  side: string | null;
  sourceAuthor: string | null;
  totalUsers: number;
  successCount: number;
  failCount: number;
  skippedNoSub: number;
  skippedNoKey: number;
  status: string;
  aiConfidence: number | null;
  durationMs: number | null;
  createdAt: string;
}

export interface BroadcastLogListResponse {
  content: BroadcastLogSummary[];
  page: number;
  size: number;
  totalPages: number;
  totalElements: number;
}

export interface BroadcastUserResult {
  userId: string;
  email: string;
  success: boolean;
  errorMessage: string | null;
}

// ==================== Funnel Stats (Admin Insights) ====================

export interface FunnelStatsResponse {
  totalUsers: number;
  emailVerified: number;
  referralVerified: number;
  hasApiKey: number;
  hasTraded: number;
  activeSubscription: number;
  registrationsByDate: DateCount[];
  recentUsers: RecentUser[];
}

export interface DateCount {
  date: string;
  count: number;
}

export interface RecentUser {
  userId: string;
  name: string;
  email: string | null;
  createdAt: string;
  stage: string;
}

export interface BroadcastLogDetail extends BroadcastLogSummary {
  entryPrice: number | null;
  stopLoss: number | null;
  takeProfit: number | null;
  closeRatio: number | null;
  newStopLoss: number | null;
  newTakeProfit: number | null;
  isDca: boolean | null;
  sourceAuthor: string | null;
  aiReasoning: string | null;
  userResults: BroadcastUserResult[];
}

// ─── Signal Source Management ───

export type TradeMode = "AUTO" | "SHADOW" | "MANUAL";

export interface SignalSourceResponse {
  id: number;
  name: string;
  displayName: string;
  channelId: string | null;
  guildId: string | null;
  description: string | null;
  routingMode: "GLOBAL" | "ASSIGNED";
  tradeMode: TradeMode;
  riskMultiplier: number;
  paperTradingEnabled: boolean;
  enabled: boolean;
  assignedUserCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSignalSourceRequest {
  name: string;
  displayName: string;
  channelId?: string;
  guildId?: string;
  description?: string;
  routingMode?: "GLOBAL" | "ASSIGNED";
  tradeMode?: TradeMode;
  riskMultiplier?: number;
}

export interface UpdateSignalSourceRequest {
  name?: string;
  displayName?: string;
  description?: string;
  enabled?: boolean;
  routingMode?: "GLOBAL" | "ASSIGNED";
  tradeMode?: TradeMode;
  riskMultiplier?: number;
  paperTradingEnabled?: boolean;
}

export interface MonitorStatusResponse {
  channelIds: string[];
  guildIds: string[];
  authorIds: string[];
  ignoreKeywords: string[];
  configVersion: number;
  connectedMonitors: number;
  monitorOnline: boolean;
  lastHeartbeat: string | null;
  channelLastSeen: Record<string, number> | null;
}

export interface UserAssignmentResponse {
  id: number;
  userId: string;
  email: string | null;
  name: string | null;
  enabled: boolean;
  assignedAt: string;
}

export interface SignalSourcePerformanceDto {
  sourceId: number;
  name: string;
  displayName: string;
  tradeMode: string;
  tradeCount: number;
  winCount: number;
  winRate: number;
  totalPnl: number;
  avgPnl: number;
  paperTradeCount: number;
  paperWinCount: number;
  paperWinRate: number;
  paperTotalPnl: number;
  paperAvgPnl: number;
  paperMaxWin: number;
  paperMaxLoss: number;
}

export interface SignalSourceUserResponse {
  id: number;
  displayName: string;
  description: string | null;
  enabled: boolean;
}

// ─── Daily Signal Report ───

export interface DailySignalReportSummary {
  id: number;
  reportDate: string;
  totalSignals: number;
  totalSources: number;
  longCount: number;
  shortCount: number;
  avgConfidence: number | null;
  hasAiAnalysis: boolean;
  createdAt: string;
}

export interface DailySignalReportListResponse {
  content: DailySignalReportSummary[];
  page: number;
  size: number;
  totalPages: number;
  totalElements: number;
}

export interface DailySignalReportDetail {
  id: number;
  reportDate: string;
  totalSignals: number;
  totalSources: number;
  longCount: number;
  shortCount: number;
  avgConfidence: number | null;
  reportData: string;     // JSON string
  aiAnalysis: string | null;
  aiTokensUsed: number | null;
  createdAt: string;
}
