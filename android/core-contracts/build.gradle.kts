plugins { `java-library`; kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
sourceSets.test { resources.srcDir("../../contracts/examples") }
