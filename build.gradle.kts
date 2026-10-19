plugins { base }
allprojects { repositories { mavenCentral() } }
tasks.register("checkBackend") {
    dependsOn(":common-contract:check", ":processing-backend:check")
}
