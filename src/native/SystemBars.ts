import { NativeModules, Platform } from "react-native";
import { useEffect } from "react";
import NativeSystemBars from "./NativeSystemBars";

const SystemBars = NativeSystemBars ?? NativeModules.SystemBars ?? null;

/**
 * Стиль системных панелей Android (игнорируется на iOS).
 * @param light true — светлые иконки на тёмном фоне / зависит от нативной реализации
 */
export function setSystemBarsStyle(light: boolean) {
  if (Platform.OS !== "android") {
    return;
  }

  if (!SystemBars?.setModeStyle) {
    console.warn("[SystemBars] Native module is not linked");
    return;
  }

  SystemBars.setModeStyle(light, 3);
}

/**
 * Автоматически применяет стиль панелей на Android API < 32 (Android 12L), как в ТЗ.
 */
export function useSystemBarStyle() {
  useEffect(() => {
    if (Platform.OS !== "android") {
      return;
    }

    const osVer =
      typeof Platform.Version === "string"
        ? Number.parseInt(Platform.Version, 10)
        : Number(Platform.Version);

    if (osVer < 32) {
      setSystemBarsStyle(false);
    }
  }, []);
}
