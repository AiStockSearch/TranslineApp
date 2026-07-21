import UserNotifications

class IOSNotificationHelper {

    /// N1 geo success shade — OFF for product (roadmap). Enable only for DEV harness.
    static var enableGeoSuccessShade: Bool = false
    
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func showSuccessNotification(lat: Double, lon: Double) {
        guard enableGeoSuccessShade else { return }
        let content = UNMutableNotificationContent()
        content.title = "Транслайн Гео"
        content.body = "Геокоординаты успешно отправлены на сервер (\(lat), \(lon))"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil // Показывать немедленно
        )

        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    static func showOfflineNotification() {
        let content = UNMutableNotificationContent()
        content.title = "Транслайн Гео"
        content.body = "Нет сети. Данные сохранены локально и будут отправлены позже."
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}
