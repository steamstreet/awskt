package com.steamstreet.aws.test

/**
 * The control-plane surface [EventBridgeMock] needs, declared here rather than consumed from a
 * client interface.
 *
 * `aws-eventbridge` ships exactly one operation — `PutEvents` — because that is the only one this
 * library's production code calls. Rules, targets and buses are a *test harness* concern: the mock
 * needs them to route an event to a local Lambda, and nothing in `events` or the `lambda` modules
 * ever calls them against real AWS. Modelling them in the published client purely so a mock could
 * them would have put four operations into the shipping API to serve the test module.
 *
 * The types are deliberately minimal — plain parameters instead of request/response objects — for
 * the same reason. If a caller ever needs real `PutRule` against AWS, it belongs in
 * `aws-eventbridge` as a proper operation (the extension seam makes that possible without forking),
 * not here.
 */
public interface EventBridgeAdmin {
    /** Creates a bus. Returns its ARN. */
    public suspend fun createEventBus(name: String): String

    /**
     * Registers a rule on [eventBusName] matching [eventPattern].
     *
     * @throws IllegalStateException if a rule of that name already exists on the bus, which is how
     *   real EventBridge behaves and what the duplicate-name test relies on.
     */
    public suspend fun putRule(name: String, eventPattern: String, eventBusName: String? = null): String

    /** Names of the rules registered on [eventBusName]. */
    public suspend fun listRules(eventBusName: String? = null): List<String>
}
