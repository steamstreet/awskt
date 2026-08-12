package com.steamstreet.awskt.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for [NativeLambdaPlugin], available as the `nativeLambda { }` block.
 *
 * The defaults describe the only combination AWS actually offers for Kotlin/Native: a `linuxArm64`
 * release executable running on the `provided.al2023` runtime on Graviton.
 */
public abstract class NativeLambdaExtension {
    /**
     * The Kotlin/Native target whose executable is packaged. Changing this is unusual — Lambda's
     * arm64 architecture is what makes `linuxArm64` the right answer — but `linuxX64` is valid if
     * the function is deployed to x86_64.
     */
    public abstract val target: Property<String>

    /** `release` or `debug`. Release is the only sensible choice for a deployed function. */
    public abstract val buildType: Property<String>

    /**
     * Container image the libcrypt layer is extracted from.
     *
     * `amazonlinux:2` rather than `amazonlinux:2023`, deliberately: 2023 dropped the standalone
     * `libcrypt.so.1` that Kotlin/Native binaries link against, which is the whole reason the layer
     * has to exist.
     */
    public abstract val layerImage: Property<String>

    /** Platform passed to `docker`, so an x86_64 CI host extracts arm64 libraries under emulation. */
    public abstract val layerPlatform: Property<String>

    /**
     * Absolute paths inside [layerImage] to copy into the layer's `lib/` directory.
     *
     * Only `libcrypt.so.1` is needed today. It is a list because the set of libraries the Kotlin/Native
     * runtime expects has changed before and will change again on a toolchain bump.
     */
    public abstract val layerLibraries: ListProperty<String>
}
