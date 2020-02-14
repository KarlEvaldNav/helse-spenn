val mainClass = "no.nav.helse.spenn.ApplicationKt"

dependencies {
    implementation(project(":spenn-core"))
    implementation("no.nav.helse:rapids-rivers:1.161aa05")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.3.61'")
    implementation("org.jetbrains.kotlin:kotlin-test-junit:1.3.61")
    implementation("org.eclipse.jetty:jetty-server:9.4.19.v20190610")
    implementation("org.eclipse.jetty:jetty-webapp:9.4.19.v20190610")
    implementation("org.eclipse.jetty:jetty-servlets:9.4.19.v20190610")
}

val githubUser: String by project
val githubPassword: String by project

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/navikt/helse-spleis")
        credentials {
            username = githubUser
            password = githubPassword
        }
    }
}

tasks.named<Jar>("jar") {
    archiveFileName.set("app.jar")
    manifest {
        attributes["Main-Class"] = mainClass
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") {
            it.name
        }
    }

    doLast {
        configurations.runtimeClasspath.get().forEach {
            val file = File("$buildDir/libs/${it.name}")
            if (!file.exists())
                it.copyTo(file)
        }
    }
}