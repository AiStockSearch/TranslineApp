# ==============================================================================
# KMP Automation Makefile (TranslineGeoWorker)
# ==============================================================================

.PHONY: help test test-android test-ios build-apk build-xcframework publish build-all clean

.DEFAULT_GOAL := help

# Цветовая разметка
CYAN  := \033[36m
GREEN := \033[32m
YELLOW:= \033[33m
RED   := \033[31m
RESET := \033[0m

## help: Отобразить список доступных команд
help:
	@echo ""
	@echo "$(CYAN)Команды управления сборкой TranslineGeoWorker:$(RESET)"
	@echo ""
	@echo "  $(GREEN)make test$(RESET)             - Прогнать все тесты во всех модулях"
	@echo "  $(GREEN)make test-android$(RESET)     - Прогнать Unit-тесты для Android"
	@echo "  $(GREEN)make test-ios$(RESET)         - Прогнать Unit-тесты для iOS"
	@echo "  $(GREEN)make build-apk$(RESET)         - Собрать Android APK (Release)"
	@echo "  $(GREEN)make build-xcframework$(RESET) - Собрать iOS XCFramework"
	@echo "  $(GREEN)make publish$(RESET)           - Опубликовать модуль :app:shared"
	@echo "  $(GREEN)make build-all$(RESET)         - Полная сборка (APK + XCFramework)"
	@echo "  $(GREEN)make clean$(RESET)             - Очистить артефакты сборки"
	@echo ""

## test: Запуск всех юнит-тестов
test:
	@echo "$(YELLOW)--> Запуск всех тестов KMP...$(RESET)"
	./gradlew allTests --continue
	@echo "$(GREEN)✓ Все тесты выполнены успешно!$(RESET)"

## test-android: Запуск юнит-тестов для Android
test-android:
	@echo "$(YELLOW)--> Запуск Android тестов...$(RESET)"
	./gradlew :app:androidApp:testDebugUnitTest
	@echo "$(GREEN)✓ Android тесты пройдены!$(RESET)"

## test-ios: Запуск тестов для iOS
test-ios:
	@echo "$(YELLOW)--> Запуск iOS тестов (Simulator)...$(RESET)"
	./gradlew :app:shared:iosSimulatorArm64Test
	@echo "$(GREEN)✓ iOS тесты пройдены!$(RESET)"

## build-apk: Сборка Android Release APK
build-apk:
	@echo "$(YELLOW)--> Сборка Android Release APK...$(RESET)"
	./gradlew :app:androidApp:assembleRelease
	@echo "$(GREEN)✓ Android APK успешно собран!$(RESET)"
	@echo "$(CYAN)Файл в: app/androidApp/build/outputs/apk/release/$(RESET)"

## build-xcframework: Сборка iOS XCFramework
build-xcframework:
	@echo "$(YELLOW)--> Сборка XCFramework для iOS...$(RESET)"
	./gradlew :app:shared:assembleSharedLocationTrackerXCFramework
	@echo "$(GREEN)✓ XCFramework успешно создан!$(RESET)"
	@echo "$(CYAN)Фреймворк в: app/shared/build/XCFrameworks/release/$(RESET)"

## publish: Публикация модуля shared
publish:
	@echo "$(YELLOW)--> Публикация модуля :app:shared...$(RESET)"
	./gradlew :app:shared:publish
	@echo "$(GREEN)✓ Модуль :app:shared успешно опубликован!$(RESET)"

## build-all: Полная сборка проекта (APK + XCFramework)
build-all: build-apk build-xcframework
	@echo "$(GREEN)★ Все целевые артефакты (APK и XCFramework) успешно собраны!$(RESET)"

## clean: Очистка временных файлов
clean:
	@echo "$(RED)--> Очистка каталогов сборки...$(RESET)"
	./gradlew clean
	rm -rf .gradle
	rm -rf build
	@echo "$(GREEN)✓ Проект очищен!$(RESET)"