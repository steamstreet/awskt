val MAJOR_VERSION = 1
val MINOR_VERSION = 0

allprojects {
    group = "com.steamstreet"
    version = "$MAJOR_VERSION.$MINOR_VERSION${this.findProperty("BUILD_NUMBER")?.let { ".$it" } ?: ".0-SNAPSHOT"}"
}