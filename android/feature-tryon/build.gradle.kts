plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.smartfitting.mirror.tryon"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { api(project(":core-contracts")); implementation(project(":core-vision")); implementation(project(":core-network")) }
