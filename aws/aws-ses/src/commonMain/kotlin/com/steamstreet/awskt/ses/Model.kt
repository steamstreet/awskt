package com.steamstreet.awskt.ses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request and response types for Amazon SES v2.
 *
 * ### There is no internal wire type here, and that is the difference from `aws-scheduler`
 *
 * Scheduler's `Name` rides in the path while everything else rides in the body, so its public
 * request types are not `@Serializable` and a separate internal `ScheduleBody` exists to keep the
 * two shapes honest. `SendEmail` has **no path parameters and no query parameters** — every input is
 * body-bound — so the public request type *is* the wire type and serializes directly. Nothing is
 * gained by interposing a second one.
 *
 * ### Nothing here carries a `ByteArray`, so everything is a `data class`
 *
 * True only because of the scope taken in [Ses]: `EmailContent.Raw` and `Simple.Attachments` are the
 * two places SES v2 puts a blob, and both are out of scope. Adding either means a base64
 * `KSerializer` and hand-written `equals`/`hashCode` over the array — see `aws-dynamodb`'s
 * `AttributeValue` for why a `data class` over a `ByteArray` is a trap — so neither is a
 * field-and-done addition.
 *
 * ### The names are SES's own, transliterated
 *
 * `Content`, `Body`, `Message` and `Destination` are generic enough to look like poor choices in
 * isolation. They are the service's shape names, they are what the AWS SDK calls the same types, and
 * a caller porting from `aws.sdk.kotlin.services.sesv2` should not have to translate a vocabulary.
 * The package qualifies them.
 */

/**
 * A subject line or one body part: the text, and optionally the charset it is in.
 *
 * @param charset an IANA name — `UTF-8`, `ISO-8859-1`. **Leave it null unless you mean it.** SES
 *   defaults to 7-bit ASCII when it is absent, which is fine for anything that *is* ASCII and
 *   mangles anything that is not. A subject containing an em dash, a curly quote or an emoji needs
 *   `"UTF-8"` here.
 */
@Serializable
public data class Content(
    @SerialName("Data") public val data: String,
    @SerialName("Charset") public val charset: String? = null,
)

/**
 * The body of a simple message, in one or both representations.
 *
 * Sending **both** [text] and [html] is the norm rather than a nicety: SES assembles them into a
 * `multipart/alternative` message, so a client that cannot render HTML — and a spam filter scoring
 * the message — still has something to read. An HTML-only transactional mail scores worse for it.
 */
@Serializable
public data class Body(
    @SerialName("Text") public val text: Content? = null,
    @SerialName("Html") public val html: Content? = null,
)

/**
 * A custom header on a simple message.
 *
 * SES rejects headers it considers its own to set — `From`, `To`, `Subject`, `Message-ID` and the
 * rest — so this is for the ones it does not, such as `List-Unsubscribe` or an `X-` header a
 * downstream system reads.
 */
@Serializable
public data class MessageHeader(
    @SerialName("Name") public val name: String,
    @SerialName("Value") public val value: String,
)

/**
 * A **simple** message: a subject and a body, with SES assembling the MIME.
 *
 * The `Attachments` member of this shape is deliberately absent — see [Ses] for the scope, and the
 * file KDoc for why a blob is not a field-and-done addition.
 */
@Serializable
public data class Message(
    @SerialName("Subject") public val subject: Content,
    @SerialName("Body") public val body: Body,
    @SerialName("Headers") public val headers: List<MessageHeader>? = null,
)

/**
 * What to send. A union in SES's model, of which only [simple] is in scope.
 *
 * Modelled as a class with one nullable member rather than a sealed hierarchy because that is what
 * it will still be when `Raw` and `Template` are added: SES's own shape is a structure whose members
 * are mutually exclusive by validation, not by type, and a sealed class here would have to be
 * flattened back into one on the way out. See [Ses] for why the other two are out of scope and how
 * to reach them without this module.
 */
@Serializable
public data class EmailContent(
    @SerialName("Simple") public val simple: Message? = null,
)

/**
 * The recipients.
 *
 * Every list is optional at the protocol level — SES validates that *some* recipient exists rather
 * than requiring a `To` specifically — so a mail addressed only to [bccAddresses] is legal.
 */
@Serializable
public data class Destination(
    @SerialName("ToAddresses") public val toAddresses: List<String>? = null,
    @SerialName("CcAddresses") public val ccAddresses: List<String>? = null,
    @SerialName("BccAddresses") public val bccAddresses: List<String>? = null,
)

/**
 * A name/value pair published with the sending events for this message.
 *
 * Only useful alongside [SendEmailRequest.configurationSetName]: the configuration set is what
 * routes send, delivery, bounce and complaint events somewhere, and these tags are the dimensions
 * those events are grouped by.
 */
@Serializable
public data class MessageTag(
    @SerialName("Name") public val name: String,
    @SerialName("Value") public val value: String,
)

/**
 * Ties the message to a contact list, so an unsubscribe applies to something.
 *
 * **Setting this makes SES suppress the send** if the recipient has already unsubscribed from
 * [topicName], which is the point of it and is also a way for a message to be silently not sent.
 */
@Serializable
public data class ListManagementOptions(
    @SerialName("ContactListName") public val contactListName: String,
    @SerialName("TopicName") public val topicName: String? = null,
)

/**
 * `SendEmail`.
 *
 * @param content required. See [EmailContent] — [Message] is the only member in scope.
 * @param fromEmailAddress the `From` address. Either bare (`no-reply@example.com`) or in
 *   display-name form (`Acme <no-reply@example.com>`); SES accepts both, and **only the bare address
 *   is the verified identity**. That distinction matters outside this client: an IAM policy scoping
 *   `ses:SendEmail` by identity ARN, and the identity itself, both name the bare address, so a
 *   deployment that puts the display-name form in either place denies its own sends.
 *
 *   Nullable because the API declares it optional — it is inferred from
 *   [fromEmailAddressIdentityArn] under a sending-authorization policy — not because a plain send
 *   can omit it.
 * @param replyToAddresses where a reply goes, if not to [fromEmailAddress]. Worth setting on mail
 *   sent from a `no-reply` identity that a human might nonetheless answer.
 * @param feedbackForwardingEmailAddress where bounce and complaint notifications go. Ignored when
 *   the [configurationSetName] routes them to SNS or EventBridge instead, which is the better
 *   arrangement for anything automated.
 * @param configurationSetName the configuration set. Without one, a send emits no events, so
 *   nothing downstream can observe a bounce.
 * @param emailTags dimensions for the events a [configurationSetName] emits. Inert without one.
 * @param listManagementOptions **can cause a send to be suppressed** — see [ListManagementOptions].
 * @param fromEmailAddressIdentityArn sending authorization only: the ARN of the identity whose
 *   policy permits this account to send as [fromEmailAddress].
 * @param feedbackForwardingEmailAddressIdentityArn the same, for
 *   [feedbackForwardingEmailAddress].
 * @param tenantName restricts the send to resources associated with one SES tenant.
 * @param endpointId a multi-region (global) endpoint id.
 */
@Serializable
public data class SendEmailRequest(
    @SerialName("Content") public val content: EmailContent,
    @SerialName("FromEmailAddress") public val fromEmailAddress: String? = null,
    @SerialName("Destination") public val destination: Destination? = null,
    @SerialName("ReplyToAddresses") public val replyToAddresses: List<String>? = null,
    @SerialName("FeedbackForwardingEmailAddress")
    public val feedbackForwardingEmailAddress: String? = null,
    @SerialName("ConfigurationSetName") public val configurationSetName: String? = null,
    @SerialName("EmailTags") public val emailTags: List<MessageTag>? = null,
    @SerialName("ListManagementOptions") public val listManagementOptions: ListManagementOptions? = null,
    @SerialName("FromEmailAddressIdentityArn") public val fromEmailAddressIdentityArn: String? = null,
    @SerialName("FeedbackForwardingEmailAddressIdentityArn")
    public val feedbackForwardingEmailAddressIdentityArn: String? = null,
    @SerialName("TenantName") public val tenantName: String? = null,
    @SerialName("EndpointId") public val endpointId: String? = null,
)

/**
 * `SendEmail`'s result.
 *
 * @property messageId the id SES assigned. **Means accepted, not delivered, and not even
 *   necessarily sent** — AWS documents both cases, a message carrying a virus in an attachment and a
 *   templated message whose personalization is invalid, where an id comes back for a message that is
 *   then dropped. Delivery is only observable through a configuration set's events. Nullable because
 *   every field on every response in this library is; a real `SendEmail` 200 always carries one.
 */
@Serializable
public data class SendEmailResponse(
    @SerialName("MessageId") public val messageId: String? = null,
)
