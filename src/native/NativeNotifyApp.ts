/**
 * TurboModule Spec for NativeModules / TurboModuleRegistry `NotifyApp`.
 *
 * File name MUST start with `Native` for RN codegen.
 * Without this Spec, New Arch (bridgeless) leaves `NativeModules.NotifyApp` undefined
 * even when the RCT_EXTERN / GeoWorkerPackage module is linked in the binary.
 */
import type { TurboModule } from "react-native";
import { TurboModuleRegistry } from "react-native";
import type { UnsafeObject } from "react-native/Libraries/Types/CodegenTypes";

export interface Spec extends TurboModule {
  show(json: string): Promise<boolean>;

  handleRemote(data: UnsafeObject): Promise<boolean>;

  cancel(id: string): Promise<boolean>;

  snooze(id: string, minutes: number): Promise<boolean>;

  /**
   * Required for NativeEventEmitter under TurboModule / New Arch.
   * iOS: RCTEventEmitter; Android: explicit @ReactMethod on NotifyAppModule.
   */
  addListener(eventName: string): void;

  removeListeners(count: number): void;
}

/**
 * TurboModule on New Arch; null → fallback to NativeModules.NotifyApp (old bridge).
 */
export default TurboModuleRegistry.get<Spec>("NotifyApp");
