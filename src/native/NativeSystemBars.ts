/**
 * TurboModule Spec для `NativeModules.SystemBars` (Android only).
 */
import type { TurboModule } from "react-native";
import { TurboModuleRegistry } from "react-native";

export interface Spec extends TurboModule {
  /**
   * @param light стиль иконок
   * @param flags зарезервировано (старый API передавал 3)
   */
  setModeStyle(light: boolean, flags: number): void;
}

export default TurboModuleRegistry.get<Spec>("SystemBars");
