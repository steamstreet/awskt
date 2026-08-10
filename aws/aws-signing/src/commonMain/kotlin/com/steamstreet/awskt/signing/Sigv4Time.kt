package com.steamstreet.awskt.signing

/**
 * The two timestamp forms SigV4 needs, derived from a single captured instant.
 *
 * Both come from one [epochMillis] deliberately: formatting them independently lets a request
 * signed at 23:59:59.999 straddle midnight and produce a credential scope that disagrees with
 * `X-Amz-Date`, which fails as `SignatureDoesNotMatch` roughly once per hundred million requests.
 *
 * This does the civil-from-days arithmetic rather than depending on kotlinx-datetime. It is ~30
 * lines, it removes a dependency from the auth path of a published artifact, and it is immune to
 * the field-accessor churn kotlinx-datetime went through across 0.6 → 0.7.
 */
internal class Sigv4Time(epochMillis: Long) {
    /** `yyyyMMdd`, for the credential scope. */
    val dateStamp: String

    /** `yyyyMMdd'T'HHmmss'Z'`, for `X-Amz-Date`. */
    val amzDate: String

    init {
        val totalSeconds = floorDiv(epochMillis, 1_000L)
        val days = floorDiv(totalSeconds, 86_400L)
        val secondOfDay = totalSeconds - days * 86_400L

        // Howard Hinnant's civil_from_days.
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val dayOfEra = z - era * 146_097L
        val yearOfEra =
            (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val yearBase = yearOfEra + era * 400L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
        val month = monthPrime + (if (monthPrime < 10L) 3L else -9L)
        val year = yearBase + (if (month <= 2L) 1L else 0L)

        val hour = secondOfDay / 3_600L
        val minute = (secondOfDay % 3_600L) / 60L
        val second = secondOfDay % 60L

        dateStamp = pad(year, 4) + pad(month, 2) + pad(day, 2)
        amzDate = dateStamp + "T" + pad(hour, 2) + pad(minute, 2) + pad(second, 2) + "Z"
    }

    private companion object {
        fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

        /** Floor division; `/` truncates toward zero, which is wrong for pre-1970 instants. */
        fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && ((a xor b) < 0L)) q - 1 else q
        }
    }
}
