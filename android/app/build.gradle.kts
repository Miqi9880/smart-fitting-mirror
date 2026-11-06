import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.smartfitting.mirror"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.smartfitting.mirror"
        minSdk = 23
        targetSdk = 35
        versionCode = 15
        versionName = "0.4.2-mock"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/garment3dAssets"))
}

// Optional local sample pack. Never download or commit brand images in CI.
// A source-only checkout builds normally and shows an explicit missing-pack state.
val garmentSource = rootProject.file("../tools/garment3d")
val garmentStatic = listOf("index.html", "style.css", "viewer.mjs", "viewer-lifecycle.mjs", "rig.mjs", "motion-player.mjs", "live-view.mjs", "live-driver.mjs", "cloth.mjs")
val garmentData = listOf("blue-tee.glb", "source.png", "sample.json", "motion.json", "motion.bin")
val garmentVendor = mapOf(
    "build/three.module.js" to "vendor/three.module.js",
    "build/three.core.js" to "vendor/three.core.js",
    "examples/jsm/controls/OrbitControls.js" to "vendor/addons/controls/OrbitControls.js",
    "examples/jsm/loaders/GLTFLoader.js" to "vendor/addons/loaders/GLTFLoader.js",
    "examples/jsm/utils/BufferGeometryUtils.js" to "vendor/addons/utils/BufferGeometryUtils.js",
    "LICENSE" to "THREE-LICENSE.txt",
)
val garmentPackAvailable = providers.gradleProperty("includeGarment3d").orNull != "false" &&
    garmentData.all { garmentSource.resolve(".local/$it").isFile } &&
    garmentVendor.keys.all { garmentSource.resolve("node_modules/three/$it").isFile }
val prepareGarment3dAssets = tasks.register<Sync>("prepareGarment3dAssets") {
    val output = layout.buildDirectory.dir("generated/garment3dAssets")
    into(output)
    from(garmentSource) { include(garmentStatic); into("garment3d") }
    if (garmentPackAvailable) {
        from(garmentSource.resolve(".local")) { include(garmentData); into("garment3d/assets") }
        garmentVendor.forEach { (source, target) ->
            from(garmentSource.resolve("node_modules/three/$source")) {
                into("garment3d/${target.substringBeforeLast('/', "")}")
                rename { target.substringAfterLast('/') }
            }
        }
    }
    doFirst {
        check(output.get().asFile.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        if (garmentPackAvailable) {
            fun field(file: String, name: String): String = Regex("\"$name\"\\s*:\\s*\"([a-f0-9]{64})\"")
                .find(garmentSource.resolve(".local/$file").readText())?.groupValues?.get(1)
                ?: throw GradleException("Invalid garment3d $file/$name; rebuild the sample pack")
            fun digest(file: String) = MessageDigest.getInstance("SHA-256")
                .digest(garmentSource.resolve(".local/$file").readBytes()).joinToString("") { "%02x".format(it) }
            check(field("sample.json", "geometry_sha256") == field("motion.json", "geometry_sha256")) { "Stale garment3d motion; rebuild model and motions together" }
            check(field("sample.json", "glb_sha256") == digest("blue-tee.glb")) { "Garment3d GLB hash mismatch" }
            check(field("motion.json", "motion_sha256") == digest("motion.bin")) { "Garment3d motion hash mismatch" }
            check(field("sample.json", "source_sha256") == digest("source.png")) { "Garment3d source hash mismatch" }
        } else logger.lifecycle("Garment3d local pack absent: APK will show the missing-pack state; see tools/garment3d/README.md")
    }
}
tasks.named("preBuild") { dependsOn(prepareGarment3dAssets) }
dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-vision"))
    implementation(project(":core-network"))
    implementation(project(":feature-tryon"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
