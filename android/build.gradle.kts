// Standalone contract build for the plugin sources (the app build compiles
// android/src/main/kotlin through pam-native.plugin.json instead).
// -PpamPluginApi=<path to pam-native-android-plugin-api-<version>.aar>
plugins { id("com.android.library") version "9.2.0" }

android {
    namespace = "dev.pam.svg"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    compileOnly(files(providers.gradleProperty("pamPluginApi").get()))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(files(providers.gradleProperty("pamPluginApi").get()))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
