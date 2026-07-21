/**
 * NotifyRouter — lightweight deep-link routing for NotifyApp events.
 * Does not depend on React Navigation; host registers handlers that call navigate().
 * Stats: aggregate matched events by registered pattern (getReport / subscribeReport).
 */
import { subscribeToNotifyEvents, type NotifyEventPayload } from "./NotifyApp";

export type NotifyRouteMatch = {
  pattern: string;
  path: string;
  params: Record<string, string>;
  event: NotifyEventPayload;
};

export type NotifyRouteHandler = (match: NotifyRouteMatch) => void;

export type NotifyRoutePatternStats = {
  total: number;
  byType: Record<string, number>;
};

export type NotifyRouterReport = {
  byPattern: Record<string, NotifyRoutePatternStats>;
  unmatched: number;
};

export type NotifyRouterAttachOptions = {
  actionTypes?: string[];
  /** Collect getReport() counters (default true). */
  trackStats?: boolean;
};

type RouteEntry = {
  pattern: string;
  regex: RegExp;
  paramNames: string[];
  handler: NotifyRouteHandler;
};

function compilePattern(pattern: string): { regex: RegExp; paramNames: string[] } {
  const paramNames: string[] = [];
  const escaped = pattern
    .split(/(:[A-Za-z_][A-Za-z0-9_]*)/)
    .map((part) => {
      if (part.startsWith(":")) {
        paramNames.push(part.slice(1));
        return "([^/]+)";
      }
      return part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    })
    .join("");
  return {
    regex: new RegExp(`^${escaped}$`),
    paramNames,
  };
}

/**
 * Prefer action deepLink, then route (as synthetic app://route/...), then payload deepLink.
 */
export function resolveEventTarget(event: NotifyEventPayload): string | null {
  if (event.deepLink && event.deepLink.trim()) return event.deepLink.trim();
  if (event.route && event.route.trim()) {
    const base = event.route.startsWith("app://")
      ? event.route
      : `app://route/${event.route.replace(/^\//, "")}`;
    const params = event.params ?? {};
    const qs = Object.keys(params)
      .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(params[k])}`)
      .join("&");
    return qs ? `${base}?${qs}` : base;
  }
  return null;
}

class NotifyRouterImpl {
  private routes: RouteEntry[] = [];
  private unsubscribe: (() => void) | null = null;
  private byPattern: Record<string, NotifyRoutePatternStats> = {};
  private unmatched = 0;
  private reportListeners = new Set<(report: NotifyRouterReport) => void>();
  private trackStats = true;

  register(pattern: string, handler: NotifyRouteHandler): () => void {
    const { regex, paramNames } = compilePattern(pattern);
    const entry: RouteEntry = { pattern, regex, paramNames, handler };
    this.routes.push(entry);
    return () => {
      this.routes = this.routes.filter((r) => r !== entry);
    };
  }

  unregister(pattern: string): void {
    this.routes = this.routes.filter((r) => r.pattern !== pattern);
  }

  clear(): void {
    this.routes = [];
  }

  getReport(): NotifyRouterReport {
    const byPattern: Record<string, NotifyRoutePatternStats> = {};
    for (const [pattern, stats] of Object.entries(this.byPattern)) {
      byPattern[pattern] = {
        total: stats.total,
        byType: { ...stats.byType },
      };
    }
    return { byPattern, unmatched: this.unmatched };
  }

  resetReport(): void {
    this.byPattern = {};
    this.unmatched = 0;
    this.notifyReportListeners();
  }

  subscribeReport(listener: (report: NotifyRouterReport) => void): () => void {
    this.reportListeners.add(listener);
    return () => {
      this.reportListeners.delete(listener);
    };
  }

  private notifyReportListeners(): void {
    if (this.reportListeners.size === 0) return;
    const report = this.getReport();
    this.reportListeners.forEach((listener) => {
      try {
        listener(report);
      } catch (e) {
        console.warn("[NotifyRouter] report listener failed", e);
      }
    });
  }

  private recordMatch(pattern: string, eventType: string): void {
    if (!this.trackStats) return;
    let stats = this.byPattern[pattern];
    if (!stats) {
      stats = { total: 0, byType: {} };
      this.byPattern[pattern] = stats;
    }
    stats.total += 1;
    const t = eventType || "UNKNOWN";
    stats.byType[t] = (stats.byType[t] ?? 0) + 1;
    this.notifyReportListeners();
  }

  private recordUnmatched(_eventType?: string): void {
    if (!this.trackStats) return;
    this.unmatched += 1;
    this.notifyReportListeners();
  }

  resolve(path: string, event: NotifyEventPayload): boolean {
    // strip query for pattern match; merge query into params
    const [pathname, query = ""] = path.split("?");
    const queryParams: Record<string, string> = {};
    if (query) {
      for (const part of query.split("&")) {
        const [k, v] = part.split("=");
        if (k) queryParams[decodeURIComponent(k)] = decodeURIComponent(v ?? "");
      }
    }

    for (const route of this.routes) {
      const m = pathname.match(route.regex);
      if (!m) continue;
      const params: Record<string, string> = { ...(event.params ?? {}), ...queryParams };
      route.paramNames.forEach((name, i) => {
        params[name] = decodeURIComponent(m[i + 1] ?? "");
      });
      this.recordMatch(route.pattern, String(event.type));
      route.handler({
        pattern: route.pattern,
        path,
        params,
        event,
      });
      return true;
    }
    return false;
  }

  /** Handle a single notify event (OPEN/READ by default). */
  handleEvent(
    event: NotifyEventPayload,
    options?: { actionTypes?: string[]; trackStats?: boolean },
  ): boolean {
    const prevTrack = this.trackStats;
    if (typeof options?.trackStats === "boolean") {
      this.trackStats = options.trackStats;
    }
    try {
      const types = options?.actionTypes ?? ["ACTION_OPEN", "ACTION_READ"];
      if (!types.includes(String(event.type))) return false;
      const target = resolveEventTarget(event);
      if (!target) {
        this.recordUnmatched(String(event.type));
        return false;
      }
      const matched = this.resolve(target, event);
      if (!matched) {
        this.recordUnmatched(String(event.type));
      }
      return matched;
    } finally {
      this.trackStats = prevTrack;
    }
  }

  /** Subscribe to onNotifyAppEvent and route OPEN/READ (and optional types). */
  attach(options?: NotifyRouterAttachOptions): () => void {
    this.detach();
    this.trackStats = options?.trackStats !== false;
    this.unsubscribe = subscribeToNotifyEvents((event) => {
      this.handleEvent(event, {
        actionTypes: options?.actionTypes,
        trackStats: this.trackStats,
      });
    });
    return () => this.detach();
  }

  detach(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }
}

/** Singleton router for the app. */
export const NotifyRouter = new NotifyRouterImpl();
