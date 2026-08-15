package com.steamstreet.awskt.sns

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mobile push: registering devices as SNS **platform endpoints**, and building the per-platform
 * payloads a push notification needs.
 *
 * ### What this covers, and what it does not
 *
 * A **platform application** is the registration of *your app* with APNs or FCM, created once with
 * a signing key or server credential. It is provisioning, it holds a secret, and it is out of scope
 * here for the same reason `CreateTopic` is — see [Sns]'s KDoc.
 *
 * A **platform endpoint** is the registration of *one device*, created at runtime every time a user
 * installs the app, and again whenever the operating system rotates the device token. That is
 * unambiguously runtime work, it is in scope, and it is where all the difficulty lives.
 *
 * ### The two traps, both of which this file exists to absorb
 *
 * 1. **Registering a device is not one call, and the error path is load-bearing.** See
 *    [registerDevice]. `CreatePlatformEndpoint` reports "this token is already registered" as an
 *    *exception whose message contains the ARN you need*, and AWS's own documented procedure is to
 *    parse it out.
 * 2. **A mobile push payload is double-encoded JSON.** See [mobilePushMessage]. The per-platform
 *    payloads are JSON documents carried as *strings* inside a JSON envelope, and writing them as
 *    nested objects — the obvious thing — is rejected.
 */

/**
 * The platform names SNS uses.
 *
 * ### `GCM` means FCM, and SNS never renamed it
 *
 * Google retired "Google Cloud Messaging" in 2018 and replaced it with Firebase Cloud Messaging.
 * **SNS's platform name is still `GCM`**, in the platform application ARN, in the endpoint ARN, and
 * as the key in a [mobilePushMessage] envelope. There is no `FCM` value and sending one is an
 * `InvalidParameter`. [FCM] is provided as an alias spelled the way people think of it, with the
 * correct value behind it.
 *
 * ### Sandbox is a different platform, not a flag
 *
 * `APNS` and `APNS_SANDBOX` are separate platform applications with separate endpoints and separate
 * certificates. A development build's device token registered against production APNs does not fail
 * at registration — it fails silently at delivery, which is the single most common "push doesn't
 * work on my debug build" cause.
 */
public object PushPlatform {
    /** Apple Push Notification service, production. */
    public const val APNS: String = "APNS"

    /** APNs sandbox — a **separate platform application**, for development builds. */
    public const val APNS_SANDBOX: String = "APNS_SANDBOX"

    /** APNs VoIP, production. Requires a VoIP certificate and delivers to `PushKit`. */
    public const val APNS_VOIP: String = "APNS_VOIP"

    /** APNs VoIP sandbox. */
    public const val APNS_VOIP_SANDBOX: String = "APNS_VOIP_SANDBOX"

    /** Firebase Cloud Messaging. **Spelled `GCM` on the wire** — see the object KDoc. */
    public const val GCM: String = "GCM"

    /** Alias for [GCM], spelled the way Google spells it. Same value; SNS only knows `GCM`. */
    public const val FCM: String = GCM

    /** Amazon Device Messaging, for Fire devices. */
    public const val ADM: String = "ADM"

    /** Baidu Cloud Push. */
    public const val BAIDU: String = "BAIDU"

    /** Windows Push Notification Services. */
    public const val WNS: String = "WNS"

    /** Microsoft Push Notification Service. */
    public const val MPNS: String = "MPNS"

    /** macOS, production. */
    public const val MACOS: String = "MACOS"

    /** macOS sandbox. */
    public const val MACOS_SANDBOX: String = "MACOS_SANDBOX"
}

/**
 * The attribute keys SNS uses on a platform endpoint.
 *
 * All three are **strings on the wire**, [ENABLED] included: it carries `"true"` or `"false"` as
 * text, not a JSON boolean, and comparing it to a Kotlin `Boolean` without parsing is a bug that
 * reads as correct. [EndpointAttributes.enabled] does the parse.
 */
public object EndpointAttribute {
    /** The device token from APNs or FCM. */
    public const val TOKEN: String = "Token"

    /** `"true"` or `"false"`, as text. SNS clears this itself when a push is rejected. */
    public const val ENABLED: String = "Enabled"

    /** Arbitrary caller data, at most 2048 bytes. A user id is the usual content. */
    public const val CUSTOM_USER_DATA: String = "CustomUserData"
}

// -- Endpoint operations -------------------------------------------------------------------------

/**
 * `CreatePlatformEndpoint`: registers one device's token.
 *
 * **Not a data class** — it carries a device token, which [toString] redacts.
 *
 * Most callers want [registerDevice] rather than this: called directly, this operation's behaviour
 * on an already-registered token is an exception rather than a result. See [Sns.createPlatformEndpoint].
 *
 * @param token the device token: a hex string from APNs, an opaque string from FCM. Pass it exactly
 *   as the operating system gave it — APNs tokens in particular are often logged with spaces and
 *   angle brackets by older iOS code, and SNS accepts the mangled form and then fails to deliver.
 * @param customUserData at most 2048 bytes, echoed back by [Sns.getEndpointAttributes]. A user id
 *   here is what lets a later "which endpoints belong to this user" question be answered without a
 *   second datastore. It is **not** encrypted and appears in the SNS console.
 */
public class CreatePlatformEndpointRequest(
    public val platformApplicationArn: String,
    public val token: String,
    public val customUserData: String? = null,
    public val attributes: Map<String, String>? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CreatePlatformEndpointRequest &&
                platformApplicationArn == other.platformApplicationArn &&
                token == other.token &&
                customUserData == other.customUserData &&
                attributes == other.attributes
            )

    override fun hashCode(): Int {
        var result = platformApplicationArn.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + (customUserData?.hashCode() ?: 0)
        return result
    }

    /**
     * Redacts the token, reporting only its length.
     *
     * A device token is a capability: combined with the platform application's credential it
     * addresses one person's phone. It is also a stable pseudonymous device identifier, which makes
     * it personal data in most privacy regimes. The length is kept because "the token changed
     * length" is the useful signal when a registration starts failing — a 64-character APNs token
     * that arrives at 71 has been logged with spaces and brackets somewhere upstream.
     */
    override fun toString(): String =
        "CreatePlatformEndpointRequest(platformApplicationArn=$platformApplicationArn, " +
            "token=<redacted, ${token.length} chars>, customUserData=$customUserData)"
}

/** `CreatePlatformEndpoint`'s result. */
public data class CreatePlatformEndpointResponse(
    public val endpointArn: String? = null,
)

/**
 * A platform endpoint's attributes, as read by [Sns.getEndpointAttributes].
 *
 * **Not a data class** — it carries the device token.
 *
 * @property raw every attribute SNS returned, unparsed. The three typed accessors read from this,
 *   and it is kept whole so an attribute AWS adds later is reachable without a release.
 */
public class EndpointAttributes(
    public val raw: Map<String, String>,
) {
    /** The device token SNS currently holds for this endpoint. */
    public val token: String? get() = raw[EndpointAttribute.TOKEN]

    /**
     * Whether SNS will deliver to this endpoint.
     *
     * **Parsed from the string `"true"`/`"false"`**, because that is what is on the wire. Null when
     * absent, which SNS does not do in practice — a null here means the attribute map was built by
     * hand rather than read from a response.
     *
     * SNS clears this itself when APNs or FCM reports the token as invalid, which is why
     * [registerDevice] checks it on every registration rather than only on first sight.
     */
    public val enabled: Boolean? get() = raw[EndpointAttribute.ENABLED]?.toBooleanStrictOrNull()

    /** Whatever was passed as [CreatePlatformEndpointRequest.customUserData]. */
    public val customUserData: String? get() = raw[EndpointAttribute.CUSTOM_USER_DATA]

    override fun equals(other: Any?): Boolean =
        this === other || (other is EndpointAttributes && raw == other.raw)

    override fun hashCode(): Int = raw.hashCode()

    /** Redacts the token — see [CreatePlatformEndpointRequest.toString]. */
    override fun toString(): String =
        "EndpointAttributes(enabled=$enabled, customUserData=$customUserData, " +
            "token=${token?.let { "<redacted, ${it.length} chars>" }})"
}

/** One endpoint from [Sns.listEndpointsByPlatformApplication]. */
public class PlatformEndpoint(
    public val endpointArn: String? = null,
    public val attributes: EndpointAttributes = EndpointAttributes(emptyMap()),
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PlatformEndpoint &&
                endpointArn == other.endpointArn &&
                attributes == other.attributes
            )

    override fun hashCode(): Int = 31 * (endpointArn?.hashCode() ?: 0) + attributes.hashCode()

    override fun toString(): String = "PlatformEndpoint(endpointArn=$endpointArn, attributes=$attributes)"
}

/**
 * `ListEndpointsByPlatformApplication`'s result.
 *
 * **[nextToken] non-null means there are more pages**, and SNS pages this at 100 endpoints. See
 * [listAllEndpoints].
 */
public data class ListEndpointsResponse(
    public val endpoints: List<PlatformEndpoint> = emptyList(),
    public val nextToken: String? = null,
)

// -- Request encoding ----------------------------------------------------------------------------

internal fun CreatePlatformEndpointRequest.toForm(): ByteArray = FormBody()
    .put("Action", "CreatePlatformEndpoint")
    .put("Version", SNS_API_VERSION)
    .put("PlatformApplicationArn", platformApplicationArn)
    .put("Token", token)
    .put("CustomUserData", customUserData)
    .also { it.putStringMap("Attributes", attributes) }
    .encode()

internal fun getEndpointAttributesForm(endpointArn: String): ByteArray = FormBody()
    .put("Action", "GetEndpointAttributes")
    .put("Version", SNS_API_VERSION)
    .put("EndpointArn", endpointArn)
    .encode()

internal fun setEndpointAttributesForm(endpointArn: String, attributes: Map<String, String>): ByteArray =
    FormBody()
        .put("Action", "SetEndpointAttributes")
        .put("Version", SNS_API_VERSION)
        .put("EndpointArn", endpointArn)
        .also { it.putStringMap("Attributes", attributes) }
        .encode()

internal fun deleteEndpointForm(endpointArn: String): ByteArray = FormBody()
    .put("Action", "DeleteEndpoint")
    .put("Version", SNS_API_VERSION)
    .put("EndpointArn", endpointArn)
    .encode()

internal fun listEndpointsForm(platformApplicationArn: String, nextToken: String?): ByteArray = FormBody()
    .put("Action", "ListEndpointsByPlatformApplication")
    .put("Version", SNS_API_VERSION)
    .put("PlatformApplicationArn", platformApplicationArn)
    .put("NextToken", nextToken)
    .encode()

internal fun parseListEndpoints(xml: String): ListEndpointsResponse = ListEndpointsResponse(
    endpoints = Xml.members(Xml.block(xml, "Endpoints")).map { member ->
        PlatformEndpoint(
            endpointArn = Xml.text(member, "EndpointArn"),
            attributes = EndpointAttributes(Xml.attributeMap(Xml.block(member, "Attributes"))),
        )
    },
    nextToken = Xml.text(xml, "NextToken"),
)

// -- Registering a device ------------------------------------------------------------------------

/**
 * Matches the ARN out of the `InvalidParameter` that means "this token is already registered".
 *
 * SNS's message is
 * `Invalid parameter: Token Reason: Endpoint <arn> already exists with the same Token, but
 * different attributes.`
 *
 * **Parsing an error message for a value is fragile and there is no alternative.** SNS does not put
 * the existing ARN in a structured field, and AWS's own documented registration procedure — the
 * pseudo-code on the "Creating a platform endpoint" page of the SNS developer guide — instructs
 * callers to extract it from exactly this string. If AWS ever rewords it, [registerDevice] stops
 * recognising the case and rethrows the original `InvalidParameterException` rather than doing
 * something clever, which is the correct failure: a caller sees the real service error instead of a
 * silently-wrong endpoint.
 */
private val EXISTING_ENDPOINT_ARN = Regex("""Endpoint (arn:[^\s]+) already exists""")

/**
 * Registers a device, and returns an endpoint ARN that is **guaranteed to carry this token and to
 * be enabled**.
 *
 * ### Why this is not just `createPlatformEndpoint`
 *
 * Device registration is the one genuinely awkward flow in SNS, and every mobile backend
 * re-implements it. Three things conspire:
 *
 * 1. `CreatePlatformEndpoint` with a token that is **already registered with different attributes**
 *    does not return the existing endpoint — it raises `InvalidParameter`, **with the ARN you need
 *    inside the message text**. AWS's own documented procedure is to parse it out.
 * 2. `CreatePlatformEndpoint` with a token already registered with *matching* attributes *does*
 *    return the existing ARN — but does **not** re-enable it. So even the success path can hand
 *    back an endpoint SNS will not deliver to.
 * 3. SNS **disables an endpoint by itself** when APNs or FCM rejects the token. An app that
 *    registers once at install and never again stops receiving notifications permanently, and
 *    nothing in the registration code ever errors.
 *
 * ### What it does
 *
 * ```
 * createPlatformEndpoint(token)                    ← may throw with the existing ARN in the message
 *   └ on that specific InvalidParameter: extract the ARN and carry on
 * getEndpointAttributes(arn)                       ← always, because (2) and (3)
 *   └ if the token differs, or Enabled is not true:
 *       setEndpointAttributes(Token = token, Enabled = "true")
 * ```
 *
 * **Two API calls in the steady state, three when a repair is needed.** The second call is not
 * skippable: nothing in the create response distinguishes "created new and enabled" from "returned
 * an existing endpoint that APNs disabled last week".
 *
 * This mirrors the pseudo-code in the SNS developer guide, with one deliberate difference: AWS's
 * version starts from an endpoint ARN the caller has stored and falls back to creating one. This
 * starts from the token, because a stateless handler that has just received a token from a device
 * is the common case and it removes the need to store the ARN at all.
 *
 * @param platformApplicationArn the platform application, which pins the platform — note
 *   [PushPlatform.APNS] and [PushPlatform.APNS_SANDBOX] are *different applications*.
 * @param token the device token, exactly as the operating system supplied it.
 * @param customUserData applied on creation. **Not** repaired on an existing endpoint: changing it
 *   would silently overwrite whatever a previous registration stored, and this function's contract
 *   is about deliverability, not about owning that field. Set it with
 *   [Sns.setEndpointAttributes] if it needs to change.
 * @return the endpoint ARN, ready for [Sns.publish] with `targetArn`.
 * @throws InvalidParameterException if the create failed for any reason other than the
 *   already-registered case — a malformed token, most often.
 */
public suspend fun Sns.registerDevice(
    platformApplicationArn: String,
    token: String,
    customUserData: String? = null,
): String {
    val endpointArn = try {
        createPlatformEndpoint(
            CreatePlatformEndpointRequest(platformApplicationArn, token, customUserData),
        ).endpointArn ?: throw SnsException(
            "MissingEndpointArn",
            "CreatePlatformEndpoint returned no EndpointArn.",
            200,
        )
    } catch (alreadyRegistered: InvalidParameterException) {
        // The one error we can act on. Anything else — and any reworded version of this one —
        // propagates unchanged rather than being guessed at.
        EXISTING_ENDPOINT_ARN.find(alreadyRegistered.message.orEmpty())?.groupValues?.get(1)
            ?: throw alreadyRegistered
    }

    // Always, and not an optimization target: the create response cannot tell us whether this
    // endpoint is enabled or which token it currently holds. See the KDoc.
    val attributes = getEndpointAttributes(endpointArn)
    if (attributes.token != token || attributes.enabled != true) {
        setEndpointAttributes(
            endpointArn,
            mapOf(
                EndpointAttribute.TOKEN to token,
                // A string, not a boolean — SNS's attribute maps are Map<String, String>.
                EndpointAttribute.ENABLED to "true",
            ),
        )
    }
    return endpointArn
}

/**
 * Every endpoint of a platform application, following the pagination to the end.
 *
 * @param maxPages a ceiling on requests issued. Reaching it returns what was collected rather than
 *   throwing — a truncated listing is a legitimate partial answer, and the caller can tell by
 *   comparing against [maxPages]. SNS pages this at 100 endpoints, so the default covers 10,000.
 */
public suspend fun Sns.listAllEndpoints(
    platformApplicationArn: String,
    maxPages: Int = 100,
): List<PlatformEndpoint> {
    val all = mutableListOf<PlatformEndpoint>()
    var token: String? = null
    var page = 0
    do {
        val response = listEndpointsByPlatformApplication(platformApplicationArn, token)
        all += response.endpoints
        token = response.nextToken
        page++
    } while (token != null && page < maxPages)
    return all
}

// -- Push payloads -------------------------------------------------------------------------------

/**
 * A push message with a different payload per platform.
 *
 * Built by [mobilePushMessage]. Publish it with [Sns.publishToDevice], or by hand with
 * `PublishRequest(message = it.toMessageJson(), messageStructure = "json", targetArn = …)`.
 */
public class MobilePushMessage internal constructor(
    /** Delivered to any protocol with no payload of its own, and **required** by SNS. */
    public val default: String,
    /** Platform name to raw payload JSON. See [mobilePushMessage] for why the values are strings. */
    public val payloads: Map<String, String>,
) {
    /**
     * The `Message` string to publish, with `MessageStructure = "json"`.
     *
     * ### The double encoding, which is the whole point of this class
     *
     * SNS's per-protocol format is a JSON object whose **values are strings containing JSON**, not
     * nested objects:
     *
     * ```json
     * {"default":"You have a message","APNS":"{\"aps\":{\"alert\":\"You have a message\"}}"}
     * ```
     *
     * Writing the natural thing — `{"APNS": {"aps": …}}` — is an `InvalidParameter` whose message
     * does not explain the shape. Building the envelope by string concatenation is the other
     * common approach and breaks the first time a message body contains a quote or a newline, which
     * for user-generated content is immediately.
     *
     * This builds it through a real JSON encoder, so the escaping is not this library's opinion.
     */
    public fun toMessageJson(): String = buildJsonObject {
        put("default", default)
        payloads.forEach { (platform, payload) -> put(platform, payload) }
    }.toString()

    override fun toString(): String =
        "MobilePushMessage(platforms=${payloads.keys}, default=${default.length} chars)"
}

/** Builds the per-platform payloads of a [MobilePushMessage]. */
public class MobilePushMessageBuilder internal constructor() {
    private val payloads = mutableMapOf<String, String>()

    /**
     * Sets the raw payload for one platform.
     *
     * [payloadJson] is a **complete JSON document as a string** — APNs' `aps` envelope, FCM's
     * message body. Its schema is Apple's or Google's, not AWS's and not this library's, which is
     * why it is passed through untouched.
     *
     * @param platform one of [PushPlatform]'s values. It must match the platform of the endpoint
     *   being published to; a payload under the wrong key is ignored and the endpoint receives
     *   [MobilePushMessage.default] instead.
     */
    public fun platform(platform: String, payloadJson: String) {
        payloads[platform] = payloadJson
    }

    /** [platform] taking a [JsonObject], for callers already building JSON. */
    public fun platform(platform: String, payload: JsonObject) {
        payloads[platform] = payload.toString()
    }

    internal fun build(default: String): MobilePushMessage =
        MobilePushMessage(default, payloads.toMap())
}

/**
 * Builds a push message with a different payload per platform.
 *
 * ```kotlin
 * val message = mobilePushMessage("Alice sent you a message") {
 *     platform(PushPlatform.APNS, apnsAlert(title = "Alice", body = "Hey there", badge = 1))
 *     platform(PushPlatform.GCM, fcmNotification(title = "Alice", body = "Hey there"))
 * }
 * sns.publishToDevice(endpointArn, message)
 * ```
 *
 * @param default **required by SNS**, not by this function's convenience. It is delivered to any
 *   protocol with no payload of its own, and a message-structure publish without it is rejected.
 *   It is also what an SMS or email subscriber to the same topic receives, so it should read as a
 *   complete notification rather than as a placeholder.
 */
public fun mobilePushMessage(
    default: String,
    build: MobilePushMessageBuilder.() -> Unit = {},
): MobilePushMessage = MobilePushMessageBuilder().apply(build).build(default)

/**
 * A minimal APNs alert payload, as a JSON string for [MobilePushMessageBuilder.platform].
 *
 * Covers the common case and nothing more. The `aps` dictionary is **Apple's schema**, it has more
 * in it than this (`content-available`, `mutable-content`, `thread-id`, `interruption-level`,
 * custom keys alongside `aps`), and it changes on Apple's schedule rather than this library's — so
 * anything beyond a plain alert should be built as a [JsonObject] and passed to
 * [MobilePushMessageBuilder.platform] directly. This helper exists so the 80% case does not require
 * knowing that, not to model APNs.
 *
 * @param badge the number on the app icon. **Zero clears it**, and null leaves it unchanged — the
 *   two are different, which is why this is a nullable `Int` rather than defaulting to 0.
 * @param sound `"default"` for the standard tone, or a bundled filename.
 */
public fun apnsAlert(
    title: String? = null,
    body: String,
    badge: Int? = null,
    sound: String? = null,
    contentAvailable: Boolean = false,
): String = buildJsonObject {
    put(
        "aps",
        buildJsonObject {
            put(
                "alert",
                buildJsonObject {
                    title?.let { put("title", it) }
                    put("body", body)
                },
            )
            badge?.let { put("badge", it) }
            sound?.let { put("sound", it) }
            // 1 rather than true: APNs takes an integer here, and a JSON boolean is ignored.
            if (contentAvailable) put("content-available", 1)
        },
    )
}.toString()

/**
 * A minimal FCM notification payload, as a JSON string for [MobilePushMessageBuilder.platform].
 *
 * ### On FCM versions
 *
 * This emits the shape SNS has always accepted under the [PushPlatform.GCM] key —
 * `{"notification":{"title":…,"body":…}}`. AWS migrated the transport behind it to FCM's HTTP v1
 * API in 2024 and translates this payload, so it is not tied to the retired legacy API.
 *
 * **It is also the limit of what a convenience can safely promise.** FCM v1 has features this shape
 * cannot express — `android.priority`, `android.ttl`, `data`-only messages, per-platform overrides
 * — and reaching them means building the payload yourself and passing it to
 * [MobilePushMessageBuilder.platform]. This helper is for the plain notification.
 *
 * @param data additional key/value pairs delivered alongside the notification. FCM requires these
 *   to be strings; anything structured has to be serialized by the caller.
 */
public fun fcmNotification(
    title: String? = null,
    body: String,
    data: Map<String, String>? = null,
): String = buildJsonObject {
    put(
        "notification",
        buildJsonObject {
            title?.let { put("title", it) }
            put("body", body)
        },
    )
    data?.let { entries ->
        put("data", buildJsonObject { entries.forEach { (k, v) -> put(k, v) } })
    }
}.toString()

/**
 * Publishes a [MobilePushMessage] to one device endpoint.
 *
 * Sets `MessageStructure = "json"` and `TargetArn`, which together are what make SNS read the
 * per-platform payloads rather than treating the envelope as literal text.
 *
 * @param endpointArn from [registerDevice].
 * @return the message id.
 * @throws EndpointDisabledException if APNs or FCM has rejected this device's token since it was
 *   registered. That is a durable condition — see the type — and the repair is to re-register with
 *   a fresh token from the device, not to retry.
 */
public suspend fun Sns.publishToDevice(
    endpointArn: String,
    message: MobilePushMessage,
): String? = publish(
    PublishRequest(
        message = message.toMessageJson(),
        targetArn = endpointArn,
        messageStructure = "json",
    ),
).messageId
