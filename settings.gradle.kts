pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "neurelix-virtual-camera"
include(":core", ":engine", ":integration-api", ":app", ":consumer-sample")
// Fork de teste do Open Camera (GPL v3) com câmera virtual NEURELIX; ver third_party/README.md
// Só entra no build se o clone existir (ver third_party/README.md); sem ele o restante do projeto compila normalmente.
if (file("third_party/opencamera/app/build.gradle").exists()) {
    include(":opencamera")
    project(":opencamera").projectDir = file("third_party/opencamera/app")
}
