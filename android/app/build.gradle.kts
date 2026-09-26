plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.smartfitting.mirror"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.smartfitting.mirror"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":core-vision"))
    implementation(project(":core-network"))
    implementation(project(":feature-tryon"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
