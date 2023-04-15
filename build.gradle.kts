val MAJOR_VERSION = 1
val MINOR_VERSION = 0

val spaceUserName =
    (project.findProperty("steamstreet.space.username") as? String) ?: System.getenv("JB_SPACE_CLIENT_ID")
val spacePassword =
    (project.findProperty("steamstreet.space.password") as? String) ?: System.getenv("JB_SPACE_CLIENT_SECRET")

allprojects {
    group = "com.steamstreet.awskt"
    version = "$MAJOR_VERSION.$MINOR_VERSION${this.findProperty("BUILD_NUMBER")?.let { ".$it" } ?: ".0-SNAPSHOT"}"
}

subprojects {
    apply(plugin = "maven-publish")

    configure<PublishingExtension> {
        repositories {
            maven {
                url = uri("https://maven.pkg.jetbrains.space/steamstreet/p/vg/vegasful")

                credentials {
                    username = spaceUserName
                    password = spacePassword
                }
            }
        }
    }
}