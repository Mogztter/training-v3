plugins {
  id("com.gradle.plugin-publish") version "0.11.0"
  `java-gradle-plugin`
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  google()
  jcenter()
}

dependencies {
  implementation("org.asciidoctor:asciidoctorj:2.2.0")
  implementation("org.yaml:snakeyaml:1.25")
  implementation(gradleApi())
}

version = "0.0.1"

val pluginsEnabled = project.findProperty("plugins-enabled") as String?

fun isPluginEnabled(name: String): Boolean = pluginsEnabled == null || pluginsEnabled.isEmpty() || (pluginsEnabled.contains(name))

gradlePlugin {
  plugins {
    if (isPluginEnabled("asciidoctorModuleDescriptor")) {
      create("asciidoctorModuleDescriptorPlugin") {
        id = "com.neo4j.gradle.asciidoctor.AsciidoctorModuleDescriptorPlugin"
        implementationClass = "com.neo4j.gradle.asciidoctor.AsciidoctorModuleDescriptorPlugin"
      }
    }
  }
}

pluginBundle {
  website = "https://neo4j.com/"
  vcsUrl = "https://github.com/neo4j-contrib/training-v3"

  (plugins) {
    // should be a private plugin
    if (isPluginEnabled("asciidoctorModuleDescriptor")) {
      "asciidoctorModuleDescriptorPlugin" {
        id = "com.neo4j.gradle.asciidoctor.AsciidoctorModuleDescriptorPlugin"
        displayName = "Generate a very specific module descriptor from an AsciiDoc file"
        description = "A plugin to generate a very specific module descriptor file in a YAML format from and AsciiDoc file."
        version = "0.0.1"
        tags = listOf("neo4j")
      }
    }
  }
}
