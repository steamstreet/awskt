package com.steamstreet.awskt.scheduler

import com.steamstreet.awskt.core.awsEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof against real EventBridge Scheduler.
 *
 * See `aws-kms`'s `LiveKmsTest` for why these exist. Self-skips without credentials:
 *
 * ```
 * eval "$(aws configure export-credentials --profile <profile> --format env)" \
 *   && AWS_REGION=us-west-2 \
 *      SMOKE_SCHEDULER_TARGET_ARN=arn:aws:sqs:us-west-2:123456789012:awskt-smoke \
 *      SMOKE_SCHEDULER_ROLE_ARN=arn:aws:iam::123456789012:role/awskt-smoke-scheduler \
 *      ./gradlew :aws:aws-scheduler:jvmTest
 * ```
 *
 * The role needs a trust policy naming `scheduler.amazonaws.com` and permission on the target —
 * though **nothing here waits for a schedule to fire**, so a role that cannot actually invoke the
 * target still passes. The schedules created are dated far in the future and deleted in the same
 * test.
 *
 * ### Every test cleans up after itself, and that is load-bearing rather than tidy
 *
 * Schedules count against a per-group account quota, so a live suite that leaked one per run would
 * eventually break the account it runs in — which is exactly what
 * [ServiceQuotaExceededException]'s KDoc warns about. Deletion runs in `finally`.
 */
class LiveSchedulerTest {

    private fun target(): Pair<String, String>? {
        if (awsEnv("AWS_ACCESS_KEY_ID").isNullOrEmpty()) return null
        val arn = awsEnv("SMOKE_SCHEDULER_TARGET_ARN")?.takeIf { it.isNotBlank() } ?: return null
        val role = awsEnv("SMOKE_SCHEDULER_ROLE_ARN")?.takeIf { it.isNotBlank() } ?: return null
        return arn to role
    }

    private fun scheduler() = Scheduler { caInfo = awsEnv("SMOKE_CA_BUNDLE") }

    /**
     * Create, read back, update, delete — the whole per-schedule lifecycle.
     *
     * The read-back is the interesting half: it proves `GetSchedule`'s **lowercase `groupName`**
     * query parameter and the restJson1 GET-with-no-body path, neither of which the create
     * exercises.
     */
    @Test
    fun createsReadsUpdatesAndDeletesASchedule() = runTest {
        val (targetArn, roleArn) = target() ?: run {
            println("[live] skipped — set AWS credentials, SMOKE_SCHEDULER_TARGET_ARN and _ROLE_ARN")
            return@runTest
        }

        val name = "awskt-live-${(0..Int.MAX_VALUE).random()}"
        scheduler().use { scheduler ->
            try {
                val created = scheduler.createSchedule(
                    CreateScheduleRequest(
                        name = name,
                        // Far enough out that it cannot fire during the test, whatever the clock says.
                        scheduleExpression = "at(2099-01-01T00:00:00)",
                        target = Target(arn = targetArn, roleArn = roleArn, input = """{"smoke":true}"""),
                        description = "awskt live smoke",
                        actionAfterCompletion = ActionAfterCompletion.DELETE,
                        state = ScheduleState.DISABLED,
                    ),
                )
                assertTrue(!created.scheduleArn.isNullOrBlank(), "no ScheduleArn came back")

                val read = scheduler.getSchedule(GetScheduleRequest(name))
                assertEquals(name, read.name)
                assertEquals("at(2099-01-01T00:00:00)", read.scheduleExpression)
                assertEquals("awskt live smoke", read.description)
                assertEquals(targetArn, read.target?.arn)
                assertEquals(ScheduleState.DISABLED, read.state)

                // The round trip the module KDoc argues for: UpdateSchedule is a replace, so
                // changing one field without `toUpdateRequest` would clear the description. This
                // asserts that it does not.
                scheduler.updateSchedule(
                    read.toUpdateRequest().copy(scheduleExpression = "at(2099-06-01T00:00:00)"),
                )
                val updated = scheduler.getSchedule(GetScheduleRequest(name))
                assertEquals("at(2099-06-01T00:00:00)", updated.scheduleExpression)
                assertEquals(
                    "awskt live smoke",
                    updated.description,
                    "toUpdateRequest() failed to preserve a field UpdateSchedule would otherwise clear",
                )
                println("[live] Scheduler created, read, updated and will delete '$name'")
            } finally {
                // In `finally` because a leaked schedule counts against the account quota forever.
                runCatching { scheduler.deleteSchedule(DeleteScheduleRequest(name)) }
            }
        }
    }

    /** `ListSchedules` uses `ScheduleGroup` where the others use `groupName`; only a live call proves it. */
    @Test
    fun listsSchedulesWithItsOwnQueryParameterSpelling() = runTest {
        target() ?: run {
            println("[live] skipped — set AWS credentials, SMOKE_SCHEDULER_TARGET_ARN and _ROLE_ARN")
            return@runTest
        }

        scheduler().use { scheduler ->
            // A bounded listing of whatever is there. The assertion is that the call is accepted at
            // all — a wrong query parameter name is a ValidationException, not an empty list.
            val all = scheduler.listAllSchedules(ListSchedulesRequest(maxResults = 10), maxPages = 2)
            println("[live] Scheduler listed ${all.size} schedule(s)")
        }
    }
}
