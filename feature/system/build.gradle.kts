plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.system"
    buildFeatures.buildConfig = true
    defaultConfig {
        // A test build can point at the dev relay with -PnotifyRelayURL=https://notify.dev.opencode.ai
        val relay = providers.gradleProperty("notifyRelayURL").getOrElse("https://opencode-notify-relay.anomalyco.workers.dev")
        buildConfigField("String", "NOTIFY_RELAY_URL", "\"$relay\"")
    }
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.datastore.preferences)
    // Share encoder pixels, AppSearch documents, MimeTypeMap and the live inbox's Context in the ported JVM tests.
    testImplementation(libs.robolectric)
}
