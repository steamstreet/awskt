package com.steamstreet.awskt.signing

import kotlin.test.Test
import kotlin.test.assertEquals

class Sigv4TimeTest {

    @Test
    fun formatsTheCorpusTimestamp() {
        val time = Sigv4Time(parseIso8601Utc("2015-08-30T12:36:00Z"))
        assertEquals("20150830", time.dateStamp)
        assertEquals("20150830T123600Z", time.amzDate)
    }

    @Test
    fun formatsTheEpoch() {
        val time = Sigv4Time(0)
        assertEquals("19700101", time.dateStamp)
        assertEquals("19700101T000000Z", time.amzDate)
    }

    /**
     * The reason both values come from one captured instant.
     *
     * Formatting the date stamp and `X-Amz-Date` independently lets a request signed in this
     * millisecond straddle midnight, producing a credential scope for one day and an `X-Amz-Date`
     * for the next. That fails as `SignatureDoesNotMatch` at a rate low enough to be dismissed as
     * a network blip.
     */
    @Test
    fun doesNotStraddleMidnight() {
        val lastMillisOfDay = parseIso8601Utc("2024-02-29T23:59:59Z") + 999
        val time = Sigv4Time(lastMillisOfDay)

        assertEquals("20240229", time.dateStamp)
        assertEquals("20240229T235959Z", time.amzDate)
        assertEquals(time.dateStamp, time.amzDate.substringBefore('T'))
    }

    @Test
    fun handlesLeapDays() {
        assertEquals("20240229T000000Z", Sigv4Time(parseIso8601Utc("2024-02-29T00:00:00Z")).amzDate)
        // 2000 is a leap year; 1900 and 2100 are not.
        assertEquals("20000229T000000Z", Sigv4Time(parseIso8601Utc("2000-02-29T00:00:00Z")).amzDate)
        assertEquals("21000301T000000Z", Sigv4Time(parseIso8601Utc("2100-03-01T00:00:00Z")).amzDate)
    }

    @Test
    fun handlesPreEpochInstants() {
        // Not reachable in production, but it is what proves the floor division is right rather
        // than truncating toward zero.
        assertEquals("19691231T235959Z", Sigv4Time(-1_000).amzDate)
        assertEquals("19691231T000000Z", Sigv4Time(-86_400_000).amzDate)
    }

    @Test
    fun roundTripsAgainstTheTestHelper() {
        for (iso in listOf(
            "1999-12-31T23:59:59Z",
            "2000-01-01T00:00:00Z",
            "2015-08-30T12:36:00Z",
            "2026-08-09T07:05:03Z",
            "2038-01-19T03:14:07Z",
        )) {
            val expected = iso.filter { it.isDigit() }.let {
                "${it.substring(0, 8)}T${it.substring(8, 14)}Z"
            }
            assertEquals(expected, Sigv4Time(parseIso8601Utc(iso)).amzDate, iso)
        }
    }
}
