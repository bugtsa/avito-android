package com.avito.instrumentation

import com.avito.instrumentation.configuration.ImpactAnalysisPolicy
import com.avito.instrumentation.configuration.InstrumentationConfiguration
import com.avito.instrumentation.configuration.InstrumentationPluginConfiguration.GradleInstrumentationPluginConfiguration
import com.avito.instrumentation.configuration.target.TargetConfiguration
import com.avito.instrumentation.configuration.target.scheduling.SchedulingConfiguration
import com.avito.instrumentation.configuration.target.scheduling.quota.QuotaConfiguration
import com.avito.instrumentation.configuration.target.scheduling.reservation.StaticDeviceReservationConfiguration
import com.avito.instrumentation.configuration.target.scheduling.reservation.TestsBasedDevicesReservationConfiguration
import com.avito.instrumentation.reservation.request.Device.Emulator
import com.avito.instrumentation.reservation.request.Device.Emulator.Emulator24Cores2
import com.avito.kotlin.dsl.getMandatoryIntProperty
import com.avito.kotlin.dsl.getMandatoryStringProperty
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class InstrumentationDefaultConfigPlugin : Plugin<Project> {

    override fun apply(project: Project) {

        val performanceNamespace = project.getMandatoryStringProperty("performanceNamespace")
        val performanceMinimumSuccessCount = project.getMandatoryIntProperty("performanceMinimumSuccessCount")

        project.plugins.withType<InstrumentationTestsPlugin> {
            project.extensions.getByType<GradleInstrumentationPluginConfiguration>().apply {

                output = project.rootProject.file("outputs/${project.name}/instrumentation").path

                logcatTags = setOf(
                    "UITestRunner:*",
                    "ActivityManager:*",
                    "ReportTestListener:*",
                    "StorageJsonTransport:*",
                    "TestReport:*",
                    "VideoCaptureListener:*",
                    "TestRunner:*",
                    "SystemDialogsManager:*",
                    "ito.android.de:*", //по этому тэгу система пишет логи об использовании hidden/restricted api https://developer.android.com/distribute/best-practices/develop/restrictions-non-sdk-interfaces
                    "*:E"
                )

                instrumentationParams = mapOf(
                    "videoRecording" to "failed",
                    "jobSlug" to "FunctionalTests"
                )

                configurationsContainer.register(
                    "ui",
                    registerUiConfig(TestsFilter.ui, hasE2eTests = true)
                )
                configurationsContainer.register(
                    "uiNoE2e",
                    registerUiConfig(TestsFilter.uiNoE2E, hasE2eTests = false)
                )

                configurationsContainer.register(
                    "newUi",
                    registerNewUiConfig(TestsFilter.ui)
                )
                configurationsContainer.register(
                    "newUiNoE2e",
                    registerNewUiConfig(TestsFilter.uiNoE2E)
                )

                configurationsContainer.register(
                    "allUi",
                    registerAllUI(TestsFilter.regression)
                )
                configurationsContainer.register(
                    "allUiNoE2e",
                    registerAllUI(TestsFilter.regressionNoE2E)
                )

                configurationsContainer.register(
                    "regression",
                    registerRegressionConfig(TestsFilter.regression)
                )
                configurationsContainer.register(
                    "regressionNoE2e",
                    registerRegressionConfig(TestsFilter.regressionNoE2E)
                )

                //todo перенести в performance модуль?
                configurationsContainer.register(
                    "performance", registerPerformanceConfig(
                        annotatedWith = TestsFilter.performance.annotatedWith,
                        k8sNamespace = performanceNamespace,
                        performanceMinimumSuccessCount = performanceMinimumSuccessCount,
                        performanceType = InstrumentationConfiguration.PerformanceType.SIMPLE
                    )
                )
                configurationsContainer.register(
                    "performanceNoE2e",
                    registerPerformanceConfig(
                        annotatedWith = TestsFilter.performanceNoE2E.annotatedWith,
                        k8sNamespace = performanceNamespace,
                        performanceMinimumSuccessCount = performanceMinimumSuccessCount,
                        performanceType = InstrumentationConfiguration.PerformanceType.SIMPLE
                    )
                )
                configurationsContainer.register(
                    "performanceMde", registerPerformanceConfig(
                        annotatedWith = TestsFilter.performance.annotatedWith,
                        k8sNamespace = performanceNamespace,
                        performanceMinimumSuccessCount = performanceMinimumSuccessCount,
                        performanceType = InstrumentationConfiguration.PerformanceType.MDE
                    )
                )
            }
        }
    }

    private fun registerUiConfig(
        testsFilter: TestsFilter,
        hasE2eTests: Boolean
    ): Action<InstrumentationConfiguration> {

        fun NamedDomainObjectContainer<TargetConfiguration>.registerDevice(emulator: Emulator) {
            register("api${emulator.api}") { target ->
                target.deviceName = "API${emulator.api}"

                target.scheduling = SchedulingConfiguration().apply {
                    quota = QuotaConfiguration().apply {
                        retryCount = 3
                        minimumSuccessCount = 1
                    }

                    reservation = TestsBasedDevicesReservationConfiguration.create(
                        device = emulator,
                        min = 2,
                        max = 130
                    )
                }

                target.rerunScheduling = SchedulingConfiguration().apply {
                    quota = QuotaConfiguration().apply {
                        retryCount = 0
                        minimumFailedCount = 1
                    }

                    reservation = TestsBasedDevicesReservationConfiguration.create(
                        device = emulator,
                        min = 2,
                        max = 130
                    )
                }
            }
        }

        return Action { config ->
            config.annotatedWith = testsFilter.annotatedWith
            config.tryToReRunOnTargetBranch = hasE2eTests
            config.reportSkippedTests = true
            config.rerunFailedTests = true
            config.reportFlakyTests = true
            config.impactAnalysisPolicy = ImpactAnalysisPolicy.On.RunAffectedTests

            EmulatorSet.fast.forEach { config.targetsContainer.registerDevice(it) }
        }
    }

    private fun registerPerformanceConfig(
        annotatedWith: Collection<String>,
        k8sNamespace: String,
        performanceMinimumSuccessCount: Int,
        performanceType: InstrumentationConfiguration.PerformanceType
    ) = Action<InstrumentationConfiguration> { config ->
        config.annotatedWith = annotatedWith
        config.rerunFailedTests = false

        config.performanceType = performanceType

        config.kubernetesNamespace = k8sNamespace

        config.instrumentationParams = mapOf("jobSlug" to "PerformanceTests")

        config.targetsContainer.register("api24") { target ->
            target.deviceName = "API24"

            target.scheduling = SchedulingConfiguration().apply {
                quota = QuotaConfiguration().apply {
                    retryCount = performanceMinimumSuccessCount + 20
                    minimumSuccessCount = performanceMinimumSuccessCount
                }

                reservation = TestsBasedDevicesReservationConfiguration.create(
                    device = Emulator24Cores2,
                    min = 12,
                    max = 42,
                    testsPerEmulator = 1
                )
            }
        }
    }

    private fun registerNewUiConfig(testsFilter: TestsFilter): Action<InstrumentationConfiguration> {

        fun NamedDomainObjectContainer<TargetConfiguration>.registerDevice(emulator: Emulator) {
            register("api${emulator.api}") { target ->
                target.deviceName = "API${emulator.api}"

                target.scheduling = SchedulingConfiguration().apply {
                    quota = QuotaConfiguration().apply {
                        retryCount = 2
                        minimumSuccessCount = 3
                    }

                    reservation = TestsBasedDevicesReservationConfiguration.create(
                        device = emulator,
                        min = 2,
                        max = 30
                    )
                }
            }
        }

        return Action { config ->
            config.annotatedWith = testsFilter.annotatedWith
            config.tryToReRunOnTargetBranch = false
            config.reportSkippedTests = false
            config.reportFlakyTests = true
            config.rerunFailedTests = false
            config.impactAnalysisPolicy = ImpactAnalysisPolicy.On.RunNewTests

            config.instrumentationParams = mapOf("jobSlug" to "NewFunctionalTests")

            EmulatorSet.fast.forEach { config.targetsContainer.registerDevice(it) }
        }
    }

    private fun registerAllUI(testsFilter: TestsFilter): Action<InstrumentationConfiguration> {

        fun NamedDomainObjectContainer<TargetConfiguration>.registerDevice(emulator: Emulator) {
            register("api${emulator.api}") { target ->
                target.deviceName = "API${emulator.api}"

                target.scheduling = SchedulingConfiguration().apply {
                    quota = QuotaConfiguration().apply {
                        retryCount = 3
                        minimumSuccessCount = 1
                    }

                    reservation = TestsBasedDevicesReservationConfiguration.create(
                        device = emulator,
                        min = 16,
                        max = 36
                    )
                }
            }
        }
        return Action { config ->
            config.annotatedWith = testsFilter.annotatedWith
            config.tryToReRunOnTargetBranch = false
            config.rerunFailedTests = true
            config.reportSkippedTests = true

            EmulatorSet.fast.forEach { config.targetsContainer.registerDevice(it) }
        }
    }

    private fun registerRegressionConfig(testsFilter: TestsFilter): Action<InstrumentationConfiguration> {

        fun NamedDomainObjectContainer<TargetConfiguration>.registerDevice(emulator: Emulator) =
            register(emulator.name) { target ->
                target.deviceName = "functional-${emulator.api}"

                target.scheduling = SchedulingConfiguration().apply {
                    quota = QuotaConfiguration().apply {
                        retryCount = 3
                        minimumSuccessCount = 1
                    }

                    reservation = StaticDeviceReservationConfiguration().apply {
                        device = emulator
                        count = 50
                    }
                }
            }

        return Action { config ->
            config.annotatedWith = testsFilter.annotatedWith
            config.reportSkippedTests = true
            config.rerunFailedTests = true

            EmulatorSet.full.forEach { config.targetsContainer.registerDevice(it) }
        }
    }
}
