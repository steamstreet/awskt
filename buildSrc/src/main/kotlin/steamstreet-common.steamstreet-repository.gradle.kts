/**
 * The Steamstreet Maven repository, where every release is published.
 *
 * It is an S3 bucket in the Steamstreet account, served read-only and without credentials at
 * https://repo.steamstreet.com through CloudFront. See `infrastructure/package-repository.yaml`.
 * A release to it is readable within a minute or two, where Maven Central takes about two hours,
 * so it is the default target of `scripts/release.sh`. Central releases are occasional, and also
 * publish here, so this repository holds every version.
 *
 * Publishing needs AWS credentials that can write the bucket. Gradle reads them from the default
 * chain, so `scripts/release.sh` exports the `steamstreet` profile's credentials into the
 * environment first: Gradle's S3 support does not read an SSO profile itself.
 *
 * `-Pawskt.steamstreetRepositoryUrl=s3://steamstreet-repository/maven/<prefix>` publishes somewhere
 * else in the bucket, which is how to try the publishing path without releasing anything:
 * CloudFront serves only `maven/release`.
 */

plugins {
    id("maven-publish")
}

publishing {
    repositories {
        maven {
            name = "steamstreet"
            url = uri(
                providers.gradleProperty("awskt.steamstreetRepositoryUrl")
                    .getOrElse("s3://steamstreet-repository/maven/release")
            )
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}
