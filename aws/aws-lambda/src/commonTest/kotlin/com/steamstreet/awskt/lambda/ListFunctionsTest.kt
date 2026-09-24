package com.steamstreet.awskt.lambda

import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A page shaped like the service's own answer, trimmed to what the assertions read. */
private fun page(vararg names: String, next: String? = null): String {
    val functions = names.joinToString(",") { name ->
        """{"FunctionName":"$name","FunctionArn":"arn:aws:lambda:us-west-2:123456789012:function:$name"}"""
    }
    val marker = next?.let { ""","NextMarker":"$it"""" } ?: ""
    return """{"Functions":[$functions]$marker}"""
}

class ListFunctionsTest {

    @Test
    fun sendsAnUnsignedBodylessGetWithTheRequestAsQueryParameters() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(page()) }.listFunctions(
            ListFunctionsRequest(
                functionVersion = FunctionVersion.ALL,
                marker = "abc",
                masterRegion = "us-east-1",
                maxItems = 25,
            ),
        )

        val request = h.requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/2015-03-31/functions", request.url.encodedPath)
        assertEquals("ALL", request.url.parameters["FunctionVersion"])
        assertEquals("abc", request.url.parameters["Marker"])
        assertEquals("us-east-1", request.url.parameters["MasterRegion"])
        assertEquals("25", request.url.parameters["MaxItems"])
        assertEquals(0, h.bodies.single().size)
    }

    @Test
    fun omitsUnsetQueryParameters() = runTest {
        val h = LambdaHarness()
        harnessLambda(h) { Answer(page()) }.listFunctions()

        assertTrue(h.requests.single().url.parameters.isEmpty())
    }

    @Test
    fun decodesEveryModelledField() = runTest {
        val body = """
            {"Functions":[{
              "FunctionName":"worker","FunctionArn":"arn:aws:lambda:us-west-2:1:function:worker",
              "Runtime":"provided.al2023","Role":"arn:aws:iam::1:role/r","Handler":"bootstrap",
              "CodeSize":1234,"Description":"d","Timeout":30,"MemorySize":512,
              "LastModified":"2026-09-24T00:00:00.000+0000","CodeSha256":"sha","Version":"${'$'}LATEST",
              "Architectures":["arm64"],"PackageType":"Zip","State":"Active","LastUpdateStatus":"Successful",
              "Environment":{"Variables":{"TOKEN":"Secret_prod/token"}},
              "EphemeralStorage":{"Size":512},
              "TracingConfig":{"Mode":"PassThrough"},
              "SnapStart":{"ApplyOn":"None","OptimizationStatus":"Off"},
              "LoggingConfig":{"LogFormat":"JSON","LogGroup":"/aws/lambda/worker"},
              "Layers":[{"Arn":"arn:aws:lambda:us-west-2:1:layer:l:1","CodeSize":99}],
              "VpcConfig":{"SubnetIds":["s-1"],"SecurityGroupIds":["sg-1"],"VpcId":"vpc-1"},
              "FileSystemConfigs":[{"Arn":"arn:fs","LocalMountPath":"/mnt/fs"}],
              "CapacityProviderConfig":{"LambdaManagedInstancesCapacityProviderConfig":
                {"CapacityProviderArn":"arn:cp","ExecutionEnvironmentMemoryGiBPerVCpu":2.0}},
              "TenancyConfig":{"TenantIsolationMode":"PER_TENANT"},
              "SomeFieldAddedNextYear":{"x":1}
            }]}
        """.trimIndent()

        val function = harnessLambda(LambdaHarness()) { Answer(body) }.listFunctions().functions!!.single()

        assertEquals("worker", function.functionName)
        assertEquals("provided.al2023", function.runtime)
        assertEquals(1234L, function.codeSize)
        assertEquals(listOf("arm64"), function.architectures)
        assertEquals("Secret_prod/token", function.environment?.variables?.get("TOKEN"))
        assertEquals(512, function.ephemeralStorage?.size)
        assertEquals("PassThrough", function.tracingConfig?.mode)
        assertEquals("JSON", function.loggingConfig?.logFormat)
        assertEquals(99L, function.layers?.single()?.codeSize)
        assertEquals("vpc-1", function.vpcConfig?.vpcId)
        assertEquals("/mnt/fs", function.fileSystemConfigs?.single()?.localMountPath)
        assertEquals(
            2.0,
            function.capacityProviderConfig?.lambdaManagedInstancesCapacityProviderConfig
                ?.executionEnvironmentMemoryGibPerVCpu,
        )
        assertEquals("PER_TENANT", function.tenancyConfig?.tenantIsolationMode)
    }

    /** Environment values are frequently secrets, and configurations get logged. */
    @Test
    fun renderingAConfigurationDoesNotPrintEnvironmentValues() {
        val function = FunctionConfiguration(
            functionName = "worker",
            environment = EnvironmentResponse(variables = mapOf("API_KEY" to "super-secret-value")),
        )

        val rendered = function.toString()

        assertTrue("API_KEY" in rendered, rendered)
        assertFalse("super-secret-value" in rendered, rendered)
    }

    @Test
    fun paginatesUntilThereIsNoMarker() = runTest {
        val h = LambdaHarness()
        val pages = listOf(page("a", "b", next = "m1"), page("c", next = "m2"), page("d"))
        val lambda = harnessLambda(h) { call -> Answer(pages[call]) }

        val names = lambda.listFunctionsPaginated().functions().map { it.functionName }.toList()

        assertEquals(listOf("a", "b", "c", "d"), names)
        assertEquals(listOf(null, "m1", "m2"), h.requests.map { it.url.parameters["Marker"] })
    }

    @Test
    fun keepsTheRequestsOtherParametersOnEveryPage() = runTest {
        val h = LambdaHarness()
        val pages = listOf(page("a", next = "m1"), page("b"))
        val lambda = harnessLambda(h) { call -> Answer(pages[call]) }

        lambda.listFunctionsPaginated(ListFunctionsRequest(maxItems = 1)).toList()

        assertEquals(listOf("1", "1"), h.requests.map { it.url.parameters["MaxItems"] })
    }

    /** The vegasful pattern: find one function by name and stop. */
    @Test
    fun stoppingEarlyRequestsNoFurtherPages() = runTest {
        val h = LambdaHarness()
        val pages = listOf(page("a", "target", next = "m1"), page("never"))
        val lambda = harnessLambda(h) { call -> Answer(pages[call]) }

        val found = lambda.listFunctionsPaginated().functions().firstOrNull { it.functionName == "target" }

        assertEquals("target", found?.functionName)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun anEmptyMarkerEndsPagination() = runTest {
        val h = LambdaHarness()
        val lambda = harnessLambda(h) { Answer(page("a", next = "")) }

        assertEquals(1, lambda.listFunctionsPaginated().toList().size)
    }

    @Test
    fun anEmptyAccountHasNoFunctions() = runTest {
        val response = harnessLambda(LambdaHarness()) { Answer("""{"Functions":[]}""") }.listFunctions()

        assertEquals(emptyList(), response.functions)
        assertNull(response.nextMarker)
    }

    @Test
    fun throttlingIsTyped() = runTest {
        val lambda = harnessLambda(LambdaHarness(), retryConfig = com.steamstreet.awskt.core.RetryConfig(maxAttempts = 1)) {
            Answer(
                """{"Type":"User","message":"Rate exceeded","Reason":"CallerRateLimitExceeded"}""",
                HttpStatusCode.TooManyRequests,
                headersOf("x-amzn-ErrorType", "TooManyRequestsException"),
            )
        }

        assertFailsWith<TooManyRequestsException> { lambda.listFunctions() }
    }
}
