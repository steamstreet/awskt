import com.steamstreet.aws.lambda.MockLambdaContext
import com.steamstreet.aws.lambda.apigateway.ApiGatewayProxyResponse
import com.steamstreet.aws.lambda.apigateway.ktor.APIGatewayLambdaServer
import com.steamstreet.aws.lambda.apigateway.ktor.apiGatewayRequest
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import java.io.ByteArrayOutputStream
import kotlin.test.Test

class APIGatewayLambdaServerTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun basics() {
        var receivedBody: String? = null
        val handler = object : APIGatewayLambdaServer() {
            context(Application) override fun module() {
                routing {
                    route("my") {
                        route("path") {
                            get {
                                receivedBody = call.receiveText()

                                call.apiGatewayRequest.path.shouldBeEqualTo("/my/path")
                                call.respondText("Got it")
                            }
                        }
                    }
                }
            }
        }
        val output = ByteArrayOutputStream()
        handler.execute(example1.byteInputStream(), output, MockLambdaContext())

        receivedBody.shouldNotBeNull().shouldBeEqualTo("Hello from Lambda!")
        val response = Json.decodeFromStream(
            ApiGatewayProxyResponse.serializer(),
            output.toByteArray().inputStream()
        )

        response.body.shouldNotBeNull().shouldBeEqualTo("Got it")
    }
}


val example1 = """
    {
      "resource": "/my/path",
      "path": "/my/path",
      "httpMethod": "GET",
      "headers": {
        "header1": "value1",
        "header2": "value1,value2"
      },
      "multiValueHeaders": {
        "header1": [
          "value1"
        ],
        "header2": [
          "value1",
          "value2"
        ]
      },
      "queryStringParameters": {
        "parameter1": "value1",
        "parameter2": "value"
      },
      "multiValueQueryStringParameters": {
        "parameter1": [
          "value1",
          "value2"
        ],
        "parameter2": [
          "value"
        ]
      },
      "requestContext": {
        "accountId": "123456789012",
        "apiId": "id",
        "authorizer": {
          "claims": null,
          "scopes": null
        },
        "domainName": "id.execute-api.us-east-1.amazonaws.com",
        "domainPrefix": "id",
        "extendedRequestId": "request-id",
        "httpMethod": "GET",
        "identity": {
          "accessKey": null,
          "accountId": null,
          "caller": null,
          "cognitoAuthenticationProvider": null,
          "cognitoAuthenticationType": null,
          "cognitoIdentityId": null,
          "cognitoIdentityPoolId": null,
          "principalOrgId": null,
          "sourceIp": "IP",
          "user": null,
          "userAgent": "user-agent",
          "userArn": null,
          "clientCert": {
            "clientCertPem": "CERT_CONTENT",
            "subjectDN": "www.example.com",
            "issuerDN": "Example issuer",
            "serialNumber": "a1:a1:a1:a1:a1:a1:a1:a1:a1:a1:a1:a1:a1:a1:a1:a1",
            "validity": {
              "notBefore": "May 28 12:30:02 2019 GMT",
              "notAfter": "Aug  5 09:36:04 2021 GMT"
            }
          }
        },
        "path": "/my/path",
        "protocol": "HTTP/1.1",
        "requestId": "id=",
        "requestTime": "04/Mar/2020:19:15:17 +0000",
        "requestTimeEpoch": 1583349317135,
        "resourceId": null,
        "resourcePath": "/my/path",
        "stage": "default"
      },
      "pathParameters": null,
      "stageVariables": null,
      "body": "Hello from Lambda!",
      "isBase64Encoded": false
    }

""".trimIndent()