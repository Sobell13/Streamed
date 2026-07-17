dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Streamed.pk extension"
    authors = listOf("Vaellys")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 3 // Will be 3 if unspecified

    tvTypes = listOf("Live")

    requiresResources = true
    language = "en"

    // Random CC logo I found
    iconUrl = "https://streamed.pk/favicon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}