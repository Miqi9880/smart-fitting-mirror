plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.smartfitting.mirror.tryon"
    compileSdk = 35
    defaultConfig { minSdk = 23; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    sourceSets.getByName("test").resources.apply {
        srcDir("../../backend/assets/studio")
        exclude("*.png") // Validate shared calibration metadata without distributing local brand images.
    }
}
dependencies {
    api(project(":core-contracts"))
    implementation(project(":core-vision"))
    implementation(project(":core-network"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
