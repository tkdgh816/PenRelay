plugins {
    id("com.android.library")
}

android {
    namespace = "dev.penrelay.session"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val vectors = rootProject.file("../tests/fixtures/p1-frames.tsv")
    inputs.file(vectors)
    systemProperty("p1.vectors", vectors.absolutePath)
    providers.gradleProperty("p1Host").orNull?.let { host ->
        val executable = rootProject.file(host)
        // 각 요청에서 새 프로세스와 자격 증명으로 실제 연결을 검사한다.
        outputs.upToDateWhen { false }
        systemProperty("p1.host", executable.absolutePath)
    }
}
