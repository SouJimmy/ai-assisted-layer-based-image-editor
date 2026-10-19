plugins { `java-library`; jacoco }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies {
    api(project(":common-contract"))
    implementation(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform(); systemProperty("java.awt.headless", "true"); finalizedBy(tasks.jacocoTestReport) }
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    additionalClassDirs.from(project(":common-contract").layout.buildDirectory.dir("classes/java/main"))
    additionalSourceDirs.from(project(":common-contract").file("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JavaExec>("integrationSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jp.ac.example.imageeditor.testing.IntegrationSmoke")
    systemProperty("java.awt.headless", "true")
    args(rootProject.file("python-service").absolutePath, rootProject.file("tmp/smoke.psd").absolutePath)
}
tasks.test { workingDir = rootProject.projectDir }
tasks.register("writeTestClasspath") {
    dependsOn(tasks.testClasses)
    doLast { layout.buildDirectory.file("test-classpath.txt").get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}
tasks.register<JavaExec>("benchmark") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jp.ac.example.imageeditor.testing.ProcessingBenchmark")
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    args("3")
}
tasks.register<JavaExec>("cli") {
    workingDir = rootProject.projectDir
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("jp.ac.example.imageeditor.cli.Main")
    systemProperty("java.awt.headless", "true")
    doFirst {
        args(project.property("input").toString(), project.property("output").toString(), project.property("operation").toString())
        project.findProperty("parameters")?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { args(it.split(Regex("\\s+"))) }
    }
}
