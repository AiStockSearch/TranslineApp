/**
 * Notify Manager bootstrap для хост RN-приложения.
 * Подставьте свой navigationRef и путь к src/native.
 *
 * См. docs/15-notify-manager.md (Host go-live / Coexistence).
 */
import React, { useEffect } from "react";
import { NativeModules } from "react-native";
import {
  NotifyRouter,
  handleRemoteNotify,
  isNotifyAppLinked,
  requestNotifyPermission,
  shouldHandleRemoteNotify,
  showNotify,
} from "../../../src/native"; // поправьте путь

/** Заглушка: замените на navigationRef из React Navigation / вашего роутера. */
type NavRef = {
  navigate: (name: string, params?: Record<string, unknown>) => void;
  isReady?: () => boolean;
};

type Props = {
  navigationRef: NavRef;
  /** Если true — один раз показать тестовую нотификацию после attach */
  smokeTest?: boolean;
};

/**
 * Вешать рядом с корнем навигации (после NavigationContainer / когда ref ready).
 */
export function NotifyAppBootstrap({ navigationRef, smokeTest = false }: Props) {
  useEffect(() => {
    if (!isNotifyAppLinked()) {
      console.warn(
        "[NotifyApp] NativeModules.NotifyApp missing — check GeoWorkerPackage / iOS Target Membership",
      );
      return undefined;
    }

    const unsubs = [
      // Хаб агрегации (summary OPEN → app://sbc с params.ids)
      NotifyRouter.register("app://sbc", ({ params }) => {
        if (navigationRef.isReady && !navigationRef.isReady()) return;
        const ids = (params.ids ?? "")
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
        navigationRef.navigate("SbcHub", {
          ids,
          count: params.count ?? String(ids.length),
        });
      }),
      NotifyRouter.register("app://sbc/:id", ({ params }) => {
        if (navigationRef.isReady && !navigationRef.isReady()) return;
        navigationRef.navigate("SbcDocument", { id: params.id });
      }),
      NotifyRouter.register("app://sbc/:id/read", ({ params }) => {
        if (navigationRef.isReady && !navigationRef.isReady()) return;
        navigationRef.navigate("SbcReader", {
          id: params.id,
          mode: params.mode,
        });
      }),
    ];

    const detach = NotifyRouter.attach(); // ACTION_OPEN + ACTION_READ (+ stats)

    const unsubReport = NotifyRouter.subscribeReport((report) => {
      // debug: агрегация по pattern роутеров
      console.log("[NotifyRouter] report", JSON.stringify(report));
    });

    (async () => {
      await requestNotifyPermission(); // Android 13+ POST_NOTIFICATIONS
      if (!smokeTest) return;
      await showNotify({
        id: "test-1",
        title: "Документ",
        body: "Готов к подписанию",
        deepLink: "app://sbc/42",
        actions: [
          { id: "open", title: "Перейти", deepLink: "app://sbc/42" },
          {
            id: "read",
            title: "Прочитать",
            deepLink: "app://sbc/42/read",
            route: "SbcReader",
            params: { id: "42", mode: "preview" },
          },
          { id: "close", title: "Закрыть" },
        ],
      });
      console.log("[NotifyRouter] getReport()", NotifyRouter.getReport());
    })().catch((e) => console.error("[NotifyApp] bootstrap failed", e));

    return () => {
      unsubReport();
      detach();
      unsubs.forEach((u) => u());
      NotifyRouter.clear();
      NotifyRouter.resetReport();
    };
  }, [navigationRef, smokeTest]);

  return null;
}

/**
 * Единый вход FCM/APNs: geo → host geo; notify_app / tl_notify=1 → Notify; иначе — Firebase/host.
 *
 * @example
 * messaging().onMessage(async (msg) => {
 *   await onHostPushMessage(msg.data as Record<string, string>);
 * });
 */
export async function onHostPushMessage(
  data: Record<string, string> | undefined,
): Promise<void> {
  if (!data) return;
  const type = data.type ?? "";
  if (type.startsWith("geo_")) {
    // geo_start / geo_stop → LocationServiceController / startLocationService на хосте
    console.log("[NotifyApp] geo push — handle in geo layer", type);
    return;
  }
  if (!shouldHandleRemoteNotify(data)) {
    // Firebase / внутренний натив хоста — не трогаем
    return;
  }
  if (!NativeModules.NotifyApp) {
    console.warn("[NotifyApp] not linked — skip handleRemoteNotify");
    return;
  }
  await handleRemoteNotify(data);
}
