plugins {
    kotlin("jvm") version "2.0.20"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.json:json:20240303")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("oracle/**", "android/**", "com/krstock/v3/data/model/**", "com/krstock/v3/data/generated/**", "com/krstock/v3/data/repository/**", "com/krstock/v3/data/peer/**", "com/krstock/v3/data/ranking/**", "com/krstock/v3/data/analysis/**", "com/krstock/v3/data/stage/**", "com/krstock/v3/data/candidate/**", "com/krstock/v3/data/history/**", "com/krstock/v3/data/evidence/**", "com/krstock/v3/data/update/SnapshotAutoUpdater.kt", "com/krstock/v3/data/price/PriceHistoryRepository.kt", "com/krstock/v3/data/price/PriceHistoryAggregation.kt", "com/krstock/v3/data/price/PriceAdjustmentProvenance.kt")
    }
}
application { mainClass.set("oracle.ExportKt") }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

// Separate entry point; the original ranking/publication exporter remains unchanged.
tasks.register<JavaExec>("priceOracle") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("oracle.price.PriceOracleKt")
    providers.gradleProperty("priceManifest").orNull?.let { args(it) }
    providers.gradleProperty("priceOutput").orNull?.let { args(it) }
}
