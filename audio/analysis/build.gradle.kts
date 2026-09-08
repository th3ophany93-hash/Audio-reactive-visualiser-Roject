plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:time"))
    implementation(project(":core:diagnostics"))
    implementation(project(":audio:beat"))
    implementation(project(":audio:cache"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Decision D-2: §119's fixtures are consumed from the test configuration only. A
    // production configuration declaring this same edge is a named build failure.
    testImplementation(project(":testing:audio"))
}
