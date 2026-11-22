plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "fr.suiviformateur.app"; compileSdk = 35
 defaultConfig { applicationId = "fr.suiviformateur.app"; minSdk = 26; targetSdk = 34; versionCode = 6; versionName = "2.1" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core:1.15.0") }
