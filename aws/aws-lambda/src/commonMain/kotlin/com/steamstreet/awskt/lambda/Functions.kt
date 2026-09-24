package com.steamstreet.awskt.lambda

import com.steamstreet.awskt.core.OperationSafety
import com.steamstreet.awskt.core.callRestJsonNoBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `ListFunctions`: the one resource-plane operation this module carries.
 *
 * It is here because a running function needs it. A scheduler or an admin API finds its sibling
 * functions by name before scheduling or invoking them, and that is a runtime lookup rather than
 * provisioning. The rest of the resource plane is still out of scope; see the KDoc on [Lambda].
 *
 * The shapes and names follow the AWS SDK for Kotlin, so a caller moving off the SDK changes
 * imports, not logic:
 *
 * | SDK | awskt |
 * |---|---|
 * | `lambda.listFunctions { maxItems = 50 }` | `lambda.listFunctions(ListFunctionsRequest(maxItems = 50))` |
 * | `lambda.listFunctionsPaginated { }` | `lambda.listFunctionsPaginated()` |
 * | `.functions()` | `.functions()` |
 *
 * These are extensions rather than members of [Lambda], so adding them breaks no implementation of
 * that interface.
 *
 * ### Service enums are `String`
 *
 * `Runtime`, `State`, `PackageType`, `Architectures` and the other enumerations on the response are
 * plain strings, as in `aws-kms`. Lambda adds runtimes several times a year, and an `enum class` on
 * a response field turns each one into a deserialization failure for the whole page. The single
 * request-side enumeration, [FunctionVersion], is closed and stays typed.
 */

/** Which versions [listFunctions] returns. */
public enum class FunctionVersion(internal val wire: String) {
    /** Every published version of each function, in addition to `$LATEST`. */
    ALL("ALL"),
}

/**
 * The input to [listFunctions]. `GET /2015-03-31/functions`.
 *
 * @property functionVersion set to [FunctionVersion.ALL] to include every published version.
 *   Omitted, only the unpublished `$LATEST` of each function is returned.
 * @property marker the [ListFunctionsResponse.nextMarker] of the previous page.
 * @property masterRegion for Lambda@Edge replicas: the region of the source function, or `ALL`.
 *   When set, [functionVersion] must be [FunctionVersion.ALL].
 * @property maxItems the page size, from 1 to 10,000. The service's default is 50.
 */
public data class ListFunctionsRequest(
    val functionVersion: FunctionVersion? = null,
    val marker: String? = null,
    val masterRegion: String? = null,
    val maxItems: Int? = null,
)

/** One page of functions. [nextMarker] is null on the last page. */
@Serializable
public data class ListFunctionsResponse(
    @SerialName("Functions") val functions: List<FunctionConfiguration>? = null,
    @SerialName("NextMarker") val nextMarker: String? = null,
)

/**
 * A function's configuration, as `ListFunctions` reports it. Every field the AWS SDK for Kotlin
 * models is carried, with the same names.
 *
 * [environment] holds the function's environment variables, which often include secrets or
 * pointers to them. [EnvironmentResponse.toString] therefore prints only the variable names, so
 * logging a [FunctionConfiguration] does not leak the values.
 */
@Serializable
public data class FunctionConfiguration(
    @SerialName("Architectures") val architectures: List<String>? = null,
    @SerialName("CapacityProviderConfig") val capacityProviderConfig: CapacityProviderConfig? = null,
    @SerialName("CodeSha256") val codeSha256: String? = null,
    @SerialName("CodeSize") val codeSize: Long = 0,
    @SerialName("ConfigSha256") val configSha256: String? = null,
    @SerialName("DeadLetterConfig") val deadLetterConfig: DeadLetterConfig? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("DurableConfig") val durableConfig: DurableConfig? = null,
    @SerialName("Environment") val environment: EnvironmentResponse? = null,
    @SerialName("EphemeralStorage") val ephemeralStorage: EphemeralStorage? = null,
    @SerialName("FileSystemConfigs") val fileSystemConfigs: List<FileSystemConfig>? = null,
    @SerialName("FunctionArn") val functionArn: String? = null,
    @SerialName("FunctionName") val functionName: String? = null,
    @SerialName("Handler") val handler: String? = null,
    @SerialName("ImageConfigResponse") val imageConfigResponse: ImageConfigResponse? = null,
    @SerialName("KMSKeyArn") val kmsKeyArn: String? = null,
    @SerialName("LastModified") val lastModified: String? = null,
    @SerialName("LastUpdateStatus") val lastUpdateStatus: String? = null,
    @SerialName("LastUpdateStatusReason") val lastUpdateStatusReason: String? = null,
    @SerialName("LastUpdateStatusReasonCode") val lastUpdateStatusReasonCode: String? = null,
    @SerialName("Layers") val layers: List<Layer>? = null,
    @SerialName("LoggingConfig") val loggingConfig: LoggingConfig? = null,
    @SerialName("MasterArn") val masterArn: String? = null,
    @SerialName("MemorySize") val memorySize: Int? = null,
    @SerialName("PackageType") val packageType: String? = null,
    @SerialName("RevisionId") val revisionId: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Runtime") val runtime: String? = null,
    @SerialName("RuntimeVersionConfig") val runtimeVersionConfig: RuntimeVersionConfig? = null,
    @SerialName("SigningJobArn") val signingJobArn: String? = null,
    @SerialName("SigningProfileVersionArn") val signingProfileVersionArn: String? = null,
    @SerialName("SnapStart") val snapStart: SnapStartResponse? = null,
    @SerialName("State") val state: String? = null,
    @SerialName("StateReason") val stateReason: String? = null,
    @SerialName("StateReasonCode") val stateReasonCode: String? = null,
    @SerialName("TenancyConfig") val tenancyConfig: TenancyConfig? = null,
    @SerialName("Timeout") val timeout: Int? = null,
    @SerialName("TracingConfig") val tracingConfig: TracingConfigResponse? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("VpcConfig") val vpcConfig: VpcConfigResponse? = null,
)

/** Where a function on Lambda Managed Instances runs. */
@Serializable
public data class CapacityProviderConfig(
    @SerialName("LambdaManagedInstancesCapacityProviderConfig")
    val lambdaManagedInstancesCapacityProviderConfig: LambdaManagedInstancesCapacityProviderConfig? = null,
)

@Serializable
public data class LambdaManagedInstancesCapacityProviderConfig(
    @SerialName("CapacityProviderArn") val capacityProviderArn: String,
    @SerialName("ExecutionEnvironmentMemoryGiBPerVCpu") val executionEnvironmentMemoryGibPerVCpu: Double? = null,
    @SerialName("PerExecutionEnvironmentMaxConcurrency") val perExecutionEnvironmentMaxConcurrency: Int? = null,
)

@Serializable
public data class DeadLetterConfig(
    @SerialName("TargetArn") val targetArn: String? = null,
)

@Serializable
public data class DurableConfig(
    @SerialName("ExecutionTimeout") val executionTimeout: Int? = null,
    @SerialName("RetentionPeriodInDays") val retentionPeriodInDays: Int? = null,
)

/**
 * The function's environment variables, or the error that stopped Lambda from reading them.
 *
 * [toString] lists the variable names without their values; read [variables] directly for those.
 */
@Serializable
public data class EnvironmentResponse(
    @SerialName("Error") val error: EnvironmentError? = null,
    @SerialName("Variables") val variables: Map<String, String>? = null,
) {
    override fun toString(): String =
        "EnvironmentResponse(error=$error, variables=${variables?.keys})"
}

@Serializable
public data class EnvironmentError(
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
)

/** The size of `/tmp`, in MB. */
@Serializable
public data class EphemeralStorage(
    @SerialName("Size") val size: Int,
)

@Serializable
public data class FileSystemConfig(
    @SerialName("Arn") val arn: String,
    @SerialName("LocalMountPath") val localMountPath: String,
)

@Serializable
public data class ImageConfigResponse(
    @SerialName("Error") val error: ImageConfigError? = null,
    @SerialName("ImageConfig") val imageConfig: ImageConfig? = null,
)

@Serializable
public data class ImageConfig(
    @SerialName("Command") val command: List<String>? = null,
    @SerialName("EntryPoint") val entryPoint: List<String>? = null,
    @SerialName("WorkingDirectory") val workingDirectory: String? = null,
)

@Serializable
public data class ImageConfigError(
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
)

@Serializable
public data class Layer(
    @SerialName("Arn") val arn: String? = null,
    @SerialName("CodeSize") val codeSize: Long = 0,
    @SerialName("SigningJobArn") val signingJobArn: String? = null,
    @SerialName("SigningProfileVersionArn") val signingProfileVersionArn: String? = null,
)

@Serializable
public data class LoggingConfig(
    @SerialName("ApplicationLogLevel") val applicationLogLevel: String? = null,
    @SerialName("LogFormat") val logFormat: String? = null,
    @SerialName("LogGroup") val logGroup: String? = null,
    @SerialName("SystemLogLevel") val systemLogLevel: String? = null,
)

@Serializable
public data class RuntimeVersionConfig(
    @SerialName("Error") val error: RuntimeVersionError? = null,
    @SerialName("RuntimeVersionArn") val runtimeVersionArn: String? = null,
)

@Serializable
public data class RuntimeVersionError(
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
)

@Serializable
public data class SnapStartResponse(
    @SerialName("ApplyOn") val applyOn: String? = null,
    @SerialName("OptimizationStatus") val optimizationStatus: String? = null,
)

@Serializable
public data class TenancyConfig(
    @SerialName("TenantIsolationMode") val tenantIsolationMode: String,
)

@Serializable
public data class TracingConfigResponse(
    @SerialName("Mode") val mode: String? = null,
)

@Serializable
public data class VpcConfigResponse(
    @SerialName("Ipv6AllowedForDualStack") val ipv6AllowedForDualStack: Boolean? = null,
    @SerialName("SecurityGroupIds") val securityGroupIds: List<String>? = null,
    @SerialName("SubnetIds") val subnetIds: List<String>? = null,
    @SerialName("VpcId") val vpcId: String? = null,
)

/**
 * Returns one page of functions. `GET /2015-03-31/functions`.
 *
 * Use [listFunctionsPaginated] to walk every page. The caller's role needs `lambda:ListFunctions`
 * on `*`; the action does not support resource-level permissions.
 *
 * @throws TooManyRequestsException if the call was throttled and the retries ran out.
 * @throws ServiceException if Lambda failed internally.
 */
public suspend fun Lambda.listFunctions(request: ListFunctionsRequest = ListFunctionsRequest()): ListFunctionsResponse =
    mapErrors {
        client.callRestJsonNoBody(
            method = "GET",
            path = "/2015-03-31/functions",
            responseSerializer = ListFunctionsResponse.serializer(),
            query = buildList {
                request.functionVersion?.let { add("FunctionVersion" to it.wire) }
                request.marker?.let { add("Marker" to it) }
                request.masterRegion?.let { add("MasterRegion" to it) }
                request.maxItems?.let { add("MaxItems" to it.toString()) }
            },
            operation = "ListFunctions",
            safety = OperationSafety.IDEMPOTENT,
        )
    }

/**
 * Walks every page of [listFunctions], starting from [request]'s marker and following
 * [ListFunctionsResponse.nextMarker] until the service stops returning one.
 *
 * The flow is cold: nothing is requested until it is collected, and each collection starts over.
 * Stopping early, for example with `firstOrNull`, requests no further pages. Use [functions] to
 * flatten the pages into functions.
 */
public fun Lambda.listFunctionsPaginated(request: ListFunctionsRequest = ListFunctionsRequest()): Flow<ListFunctionsResponse> =
    flow {
        var marker = request.marker
        do {
            val page = listFunctions(request.copy(marker = marker))
            emit(page)
            // An empty marker is treated as the end too, so a service quirk cannot loop forever.
            marker = page.nextMarker?.takeIf { it.isNotEmpty() }
        } while (marker != null)
    }

/** Flattens pages from [listFunctionsPaginated] into their functions. */
public fun Flow<ListFunctionsResponse>.functions(): Flow<FunctionConfiguration> =
    transform { page -> page.functions?.forEach { emit(it) } }
