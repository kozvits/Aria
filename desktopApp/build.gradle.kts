import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "app.aria.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Aria"
            packageVersion = "1.0.0"
            description = "Голосовая виртуальная ассистентка"
            // includeAllModules — чтобы в установщик попали TLS-провайдеры и javax.sound без ручного списка модулей
            includeAllModules = true
            windows {
                menuGroup = "Aria"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "5d0c1f7e-6c63-4b0e-9a4e-3b1f0c2f8a11"
            }
        }
    }
}
