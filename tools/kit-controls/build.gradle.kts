plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    compileOnly(libs.android.lint.api)
    testImplementation(libs.android.lint.api)
    testImplementation(libs.android.lint.tests)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest.attributes["Lint-Registry-v2"] = "dev.multiprompt.lint.KitIssueRegistry"
}

tasks.test {
    workingDir = rootProject.projectDir
    systemProperty("kit.root", rootProject.projectDir.absolutePath)
}
