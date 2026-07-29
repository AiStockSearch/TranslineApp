# ==============================================================================
# KMP Automation Makefile (TranslineGeoWorker)
# ==============================================================================
#
# Локальный релиз в GitHub:
#   export GITHUB_TOKEN=...                 # token с правами на releases (repo)
#   export GITHUB_OWNER=your-org-or-user
#   export GITHUB_REPO=TranslineGeoWorker
#   make release VERSION=0.1.0
#
# ==============================================================================

.PHONY: help test test-android test-ios \
	build-apk build-aar build-aar-rn build-xcframework build-all \
	pack-npm release release-only \
	apply-host-patches \
	publish publish-maven clean

.DEFAULT_GOAL := help

-include .env
export

# --- параметры релиза ---
VERSION ?= $(shell node -p "require('./package.json').version" 2>/dev/null || echo 0.1.0)
# GITHUB_OWNER ?=AiStockSearch
# GITHUB_REPO ?=TranslineApp
# GITHUB_TOKEN ?=
PACKAGE_REGISTRY_NAME ?= geoworker

# Цветовая разметка
CYAN  := \033[36m
GREEN := \033[32m
YELLOW:= \033[33m
RED   := \033[31m
RESET := \033[0m

## help: Отобразить список доступных команд
help:
	@echo ""
	@echo "$(CYAN)Сборка и GitHub Releases:$(RESET)"
	@echo ""
	@echo "  $(GREEN)make build-aar$(RESET)         - Android AAR (core + shared)"
	@echo "  $(GREEN)make build-xcframework$(RESET) - iOS XCFramework"
	@echo "  $(GREEN)make pack-npm$(RESET)          - AAR + XCFramework → dist/*.tgz"
	@echo "  $(GREEN)make release$(RESET)           - сборка + pack + публикация в GitHub Release"
	@echo "  $(GREEN)make release-only$(RESET)      - только upload уже собранного dist/*.tgz"
	@echo ""
	@echo "  Версия:  make release VERSION=0.1.0"
	@echo "  Env:     GITHUB_TOKEN, GITHUB_OWNER, GITHUB_REPO"
	@echo ""
	@echo "$(CYAN)Прочее:$(RESET)"
	@echo ""
	@echo "  $(GREEN)make apply-host-patches HOST=../MyApp$(RESET) - apply connect patches to RN host"
	@echo "  $(GREEN)make test$(RESET) / test-android / test-ios"
	@echo "  $(GREEN)make build-apk$(RESET)         - Android APK (sample app)"
	@echo "  $(GREEN)make build-all$(RESET)         - APK + XCFramework"
	@echo "  $(GREEN)make publish-maven$(RESET)     - Gradle publish :app:shared"
	@echo "  $(GREEN)make clean$(RESET)"
	@echo ""
	@echo "  После правок shared/notify (iOS): make pack-npm перед релизом."
	@echo ""

## build-aar: Сборка Android AAR (core + shared)
## Для RN-хоста (Kotlin 2.0.x) используйте: make build-aar-rn
build-aar:
	@echo "$(YELLOW)--> Сборка Android AAR...$(RESET)"
	./gradlew :core:assembleAndroidMain :app:shared:assembleAndroidMain
	@echo "$(GREEN)✓ AAR:$(RESET)"
	@ls -lh core/build/outputs/aar/*.aar app/shared/build/outputs/aar/*.aar 2>/dev/null || true

## build-aar-rn: AAR с Kotlin 2.0.21 (совместимо с RN 0.79 / metadata ≤ 2.2)
build-aar-rn:
	@echo "$(YELLOW)--> Сборка Android AAR для RN (Kotlin 2.0.21)...$(RESET)"
	@cp gradle/libs.versions.toml gradle/libs.versions.toml.bak
	@sed -i.bak 's/kotlin = "2.4.10"/kotlin = "2.0.21"/' gradle/libs.versions.toml || \
		sed -i '' 's/kotlin = "2.4.10"/kotlin = "2.0.21"/' gradle/libs.versions.toml
	./gradlew :core:assembleAndroidMain :app:shared:assembleAndroidMain --no-configuration-cache; \
		STATUS=$$?; \
		mv gradle/libs.versions.toml.bak gradle/libs.versions.toml 2>/dev/null || true; \
		rm -f gradle/libs.versions.toml.bak; \
		exit $$STATUS
	@echo "$(GREEN)✓ RN-compatible AAR:$(RESET)"
	@ls -lh core/build/outputs/aar/*.aar app/shared/build/outputs/aar/*.aar 2>/dev/null || true

## build-xcframework: Сборка iOS XCFramework
build-xcframework:
	@echo "$(YELLOW)--> Сборка XCFramework для iOS...$(RESET)"
	./gradlew :app:shared:assembleSharedLocationTrackerXCFramework
	@echo "$(GREEN)✓ XCFramework: app/shared/build/XCFrameworks/release/$(RESET)"

## pack-npm: AAR + XCFramework → dist/transline-geoworker-VERSION.tgz
## После изменений shared/notify (iOS forwarding и т.п.) обязательно пересобрать перед релизом.
pack-npm: build-aar build-xcframework
	@echo "$(YELLOW)--> Упаковка npm tarball (VERSION=$(VERSION))...$(RESET)"
	chmod +x scripts/pack-npm.sh
	./scripts/pack-npm.sh "$(VERSION)"
	@echo "$(GREEN)✓ Готово: dist/transline-geoworker-$(VERSION).tgz$(RESET)"

## apply-host-patches: Применить app/connect/patches к RN-хосту (HOST=путь)
## Пример: make apply-host-patches HOST=../MyRnApp
## Dry-run: make apply-host-patches HOST=../MyRnApp DRY_RUN=1
HOST ?=
DRY_RUN ?=
PLATFORM ?= all
apply-host-patches:
	@if [ -z "$(HOST)" ]; then \
		echo "$(RED)ERROR: make apply-host-patches HOST=/path/to/YourReactNativeApp$(RESET)"; exit 1; \
	fi
	@echo "$(YELLOW)--> Apply connect patches → $(HOST) (platform=$(PLATFORM))...$(RESET)"
	node scripts/apply-geoworker-patches.js \
		--root "$(CURDIR)" \
		--host "$(HOST)" \
		--platform "$(PLATFORM)" \
		$(if $(DRY_RUN),--dry-run,)
	@echo "$(GREEN)✓ Host patches done$(RESET)"

## release: Полный локальный релиз → GitHub Release
release: pack-npm release-only

## release-only: Загрузить уже собранный dist/*.tgz (+ AAR/XCF) в GitHub Release
release-only:
	@if [ -z "$(GITHUB_TOKEN)" ]; then \
		echo "$(RED)ERROR: export GITHUB_TOKEN=...$(RESET)"; exit 1; \
	fi
	@if [ -z "$(GITHUB_OWNER)" ]; then \
		echo "$(RED)ERROR: export GITHUB_OWNER=...$(RESET)"; exit 1; \
	fi
	@if [ -z "$(GITHUB_REPO)" ]; then \
		echo "$(RED)ERROR: export GITHUB_REPO=...$(RESET)"; exit 1; \
	fi
	@echo "$(YELLOW)--> Публикация v$(VERSION) в GitHub ($(GITHUB_OWNER)/$(GITHUB_REPO))...$(RESET)"
	chmod +x scripts/publish-github-release.sh
	GITHUB_TOKEN="$(GITHUB_TOKEN)" \
	GITHUB_OWNER="$(GITHUB_OWNER)" \
	GITHUB_REPO="$(GITHUB_REPO)" \
	PACKAGE_REGISTRY_NAME="$(PACKAGE_REGISTRY_NAME)" \
	./scripts/publish-github-release.sh "$(VERSION)"
	@echo "$(GREEN)✓ Release v$(VERSION) опубликован$(RESET)"

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

## publish-maven: Публикация модуля shared через Gradle
publish-maven:
	@echo "$(YELLOW)--> Публикация модуля :app:shared (Maven)...$(RESET)"
	./gradlew :app:shared:publish
	@echo "$(GREEN)✓ Модуль :app:shared успешно опубликован!$(RESET)"

## publish: alias → release (GitHub)
publish: release

## build-all: Полная сборка sample (APK + XCFramework)
build-all: build-apk build-xcframework
	@echo "$(GREEN)★ APK и XCFramework собраны$(RESET)"

## clean: Очистка временных файлов
clean:
	@echo "$(RED)--> Очистка каталогов сборки...$(RESET)"
	./gradlew clean
	rm -rf .gradle build dist
	@echo "$(GREEN)✓ Проект очищен!$(RESET)"
