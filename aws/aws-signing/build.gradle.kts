import groovy.json.JsonSlurper

plugins {
    id("steamstreet-common.multiplatform-library-conventions")
}

description = "AWS SigV4 request signing for Kotlin Multiplatform. No HTTP client, no AWS SDK."

kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain {
            dependencies {
                // SigV4 needs exactly two primitives: SHA-256 and HMAC-SHA-256. `macs:hmac-sha2`
                // does not re-export the digest, so the hash artifact is declared explicitly.
                implementation(libs.kotlincrypto.hash.sha2)
                implementation(libs.kotlincrypto.hmac.sha2)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SigV4 test-vector code generation.
//
// Kotlin Multiplatform has no built-in commonTest resource loading, so the
// vendored AWS corpus is compiled into a Kotlin source file instead. This is
// what lets the same assertions run on jvm, macosArm64 and linuxX64 — smithy
// -kotlin hit this same wall and left its native signer untested against its
// own vectors (see the plan, Decision 7).
// ---------------------------------------------------------------------------

val vectorsDir: Directory = layout.projectDirectory.dir("src/commonTest/vectors")
val vectorsOutDir: Provider<Directory> = layout.buildDirectory.dir("generated/sigv4-vectors/kotlin")

fun String.asKotlinLiteral(): String = buildString {
    append('"')
    for (c in this@asKotlinLiteral) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(c)
        }
    }
    append('"')
}

fun String?.asNullableKotlinLiteral(): String = this?.asKotlinLiteral() ?: "null"

val generateSigV4Vectors by tasks.registering {
    description = "Generates SigV4TestVectors.kt from the vendored AWS signing test-suite corpus."
    val inputDir = vectorsDir
    val outputDir = vectorsOutDir

    inputs.dir(inputDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDir)

    doLast {
        val caseDirs = inputDir.asFile.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()

        require(caseDirs.isNotEmpty()) {
            "No SigV4 vector cases found in ${inputDir.asFile}. Was the corpus vendored?"
        }

        fun textOrNull(dir: File, name: String): String? =
            File(dir, name).takeIf { it.isFile }?.readText()

        val entries = caseDirs.joinToString(",\n") { dir ->
            @Suppress("UNCHECKED_CAST")
            val ctx = JsonSlurper().parse(File(dir, "context.json")) as Map<String, Any?>
            val creds = ctx["credentials"] as Map<String, Any?>

            val fields = listOf(
                "name" to dir.name.asKotlinLiteral(),
                "request" to File(dir, "request.txt").readText().asKotlinLiteral(),
                "accessKeyId" to (creds["access_key_id"] as String).asKotlinLiteral(),
                "secretAccessKey" to (creds["secret_access_key"] as String).asKotlinLiteral(),
                "sessionToken" to (creds["token"] as String?).asNullableKotlinLiteral(),
                "region" to (ctx["region"] as String).asKotlinLiteral(),
                "service" to (ctx["service"] as String).asKotlinLiteral(),
                "timestamp" to (ctx["timestamp"] as String).asKotlinLiteral(),
                "normalize" to ((ctx["normalize"] as Boolean?) ?: true).toString(),
                "doubleUriEncode" to ((ctx["double_uri_encode"] as Boolean?) ?: true).toString(),
                "signBody" to ((ctx["sign_body"] as Boolean?) ?: false).toString(),
                "omitSessionToken" to ((ctx["omit_session_token"] as Boolean?) ?: false).toString(),
                "expirationInSeconds" to "${(ctx["expiration_in_seconds"] as Number?)?.toLong() ?: 3600L}L",
                "headerCanonicalRequest" to textOrNull(dir, "header-canonical-request.txt").asNullableKotlinLiteral(),
                "headerStringToSign" to textOrNull(dir, "header-string-to-sign.txt").asNullableKotlinLiteral(),
                "headerSignature" to textOrNull(dir, "header-signature.txt").asNullableKotlinLiteral(),
                "queryCanonicalRequest" to textOrNull(dir, "query-canonical-request.txt").asNullableKotlinLiteral(),
                "queryStringToSign" to textOrNull(dir, "query-string-to-sign.txt").asNullableKotlinLiteral(),
                "querySignature" to textOrNull(dir, "query-signature.txt").asNullableKotlinLiteral(),
            )

            "    SigV4VectorCase(\n" +
                fields.joinToString(",\n") { (k, v) -> "        $k = $v" } +
                "\n    )"
        }

        val out = File(outputDir.get().asFile, "SigV4TestVectors.kt")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("// GENERATED by the `generateSigV4Vectors` Gradle task. DO NOT EDIT.")
                appendLine("// Source corpus: aws-signing-test-suite/v4 — see src/commonTest/vectors/README.md")
                appendLine("package com.steamstreet.awskt.signing")
                appendLine()
                appendLine("internal class SigV4VectorCase(")
                appendLine("    val name: String,")
                appendLine("    val request: String,")
                appendLine("    val accessKeyId: String,")
                appendLine("    val secretAccessKey: String,")
                appendLine("    val sessionToken: String?,")
                appendLine("    val region: String,")
                appendLine("    val service: String,")
                appendLine("    val timestamp: String,")
                appendLine("    val normalize: Boolean,")
                appendLine("    val doubleUriEncode: Boolean,")
                appendLine("    val signBody: Boolean,")
                appendLine("    val omitSessionToken: Boolean,")
                appendLine("    val expirationInSeconds: Long,")
                appendLine("    val headerCanonicalRequest: String?,")
                appendLine("    val headerStringToSign: String?,")
                appendLine("    val headerSignature: String?,")
                appendLine("    val queryCanonicalRequest: String?,")
                appendLine("    val queryStringToSign: String?,")
                appendLine("    val querySignature: String?,")
                appendLine(")")
                appendLine()
                appendLine("internal val sigV4Vectors: List<SigV4VectorCase> = listOf(")
                appendLine(entries)
                appendLine(")")
            },
        )

        logger.lifecycle("Generated ${caseDirs.size} SigV4 vector cases into ${out.absolutePath}")
    }
}

kotlin.sourceSets.commonTest.get().kotlin.srcDir(generateSigV4Vectors)

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("AWS SigV4 request signing for Kotlin Multiplatform.")
            }
        }
    }
}
