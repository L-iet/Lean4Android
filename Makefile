SHELL := /bin/bash
.DEFAULT_GOAL := help

REPO_ROOT := $(CURDIR)
SDK_ROOT ?= $(REPO_ROOT)/.android-sdk
JOBS ?= 4
export ANDROID_SDK_ROOT := $(SDK_ROOT)

.PHONY: help doctor doctor-toolchain configure-sdk test ui apk toolchain all

help:
	@echo "Lean4Android newcomer build targets"
	@echo "  make doctor           Check APK/UI build prerequisites"
	@echo "  make doctor-toolchain Check full cross-toolchain prerequisites"
	@echo "  make configure-sdk    Write local.properties for SDK_ROOT"
	@echo "  make test             Run all host unit tests"
	@echo "  make ui               Run app UI tests and Kotlin compilation"
	@echo "  make apk              Run tests and build the Android1 debug APK"
	@echo "  make toolchain        Build and audit Android1 Lean from source"
	@echo "  make all              Build the toolchain, tests, and APK"
	@echo
	@echo "Overrides: SDK_ROOT=/path/to/sdk JOBS=4"

doctor:
	@set -euo pipefail; \
	for command in bash git java python3 jq make; do \
	  command -v "$$command" >/dev/null || { echo "Missing command: $$command" >&2; exit 1; }; \
	done; \
	java_version="$$(java -version 2>&1)"; \
	echo "$$java_version" | head -n 1; \
	echo "$$java_version" | grep -Eq 'version "17([.]|\")' || { echo "JDK 17 is required" >&2; exit 1; }; \
	test -x "$(SDK_ROOT)/platform-tools/adb" || { echo "Missing $(SDK_ROOT)/platform-tools/adb" >&2; exit 1; }; \
	test -d "$(SDK_ROOT)/platforms/android-36" || { echo "Missing Android SDK platform 36" >&2; exit 1; }; \
	test -d "$(SDK_ROOT)/build-tools/35.0.0" || { echo "Missing Android Build Tools 35.0.0" >&2; exit 1; }; \
	test -f "toolchain/output/lean-4.32.1-android1/manifest.json" || { \
	  echo "Missing audited toolchain/output/lean-4.32.1-android1; obtain the trusted prebuilt distribution or run 'make toolchain'." >&2; \
	  exit 1; \
	}; \
	echo "APK/UI prerequisites are present."

doctor-toolchain:
	@set -euo pipefail; \
	for command in bash git cc cmake make ninja pkg-config perl python3 jq; do \
	  command -v "$$command" >/dev/null || { echo "Missing command: $$command" >&2; exit 1; }; \
	done; \
	test -f "$(SDK_ROOT)/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" || { echo "Missing Android NDK 28.2.13676358" >&2; exit 1; }; \
	echo "Full toolchain prerequisites are present."

configure-sdk:
	@set -euo pipefail; \
	test -d "$(SDK_ROOT)" || { echo "SDK_ROOT does not exist: $(SDK_ROOT)" >&2; exit 1; }; \
	printf 'sdk.dir=%s\n' "$(SDK_ROOT)" > local.properties; \
	echo "Wrote local.properties for $(SDK_ROOT)"

test: doctor
	@scripts/run-ui-gradle.sh testDebugUnitTest

ui: doctor
	@scripts/run-ui-gradle.sh :app:testDebugUnitTest :app:compileDebugKotlin

apk: doctor
	@scripts/run-ui-gradle.sh

toolchain: doctor-toolchain
	@LEAN4ANDROID_JOBS="$(JOBS)" toolchain/scripts/fetch-sources.sh
	@LEAN4ANDROID_JOBS="$(JOBS)" toolchain/scripts/build-host.sh
	@LEAN4ANDROID_JOBS="$(JOBS)" toolchain/scripts/build-android-deps.sh
	@LEAN4ANDROID_JOBS="$(JOBS)" toolchain/scripts/build-android.sh
	@LEAN4ANDROID_JOBS="$(JOBS)" toolchain/scripts/assemble-distribution.sh

all: toolchain apk
