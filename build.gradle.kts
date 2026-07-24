plugins {
    id("java")
}

group = "it.listaservers"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Dipendenza dal file server di Hytale (da inserire manualmente nella cartella libs/)
    compileOnly(files("libs/HytaleServer.jar"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}
