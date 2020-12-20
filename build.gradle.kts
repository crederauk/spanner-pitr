val kotlin_version = "1.3.72"
val spek_version = "2.0.10"

plugins {
    java
    kotlin("jvm") version "1.3.72"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    id("org.ajoberstar.reckon") version "0.13.0"
}

group = "com.github.dmwgroup"
version = "0.1"

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    testImplementation("junit", "junit", "4.12")
    implementation("com.google.cloud:google-cloud-spanner:3.1.2")
    implementation("com.google.cloud:google-cloud-spanner-jdbc:1.18.2")
    implementation("com.github.ajalt:clikt:2.7.0")
    implementation("io.github.microutils:kotlin-logging:1.7.9")
    implementation("org.slf4j:slf4j-simple:1.7.29")
    implementation("com.google.cloud:google-cloud-logging:1.101.1")
    implementation("com.opencsv:opencsv:5.2")
    implementation("com.github.jengelman.gradle.plugins:shadow:5.2.0")

    // Testing
    //implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spek_version")
    testImplementation("org.amshove.kluent:kluent:1.61")
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:$spek_version")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlin_version")
}

reckon {
    scopeFromProp()
    stageFromProp("final", "hotfix")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_11
}
tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "11"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "11"
    }
    test {
        useJUnitPlatform {
            includeEngines("spek2")
        }
    }
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("spanner-pitr")
        mergeServiceFiles()
        manifest {
            attributes(mapOf("Main-Class" to "com.dmwgroup.spanner.pitr.MainKt"))
        }
    }
}
