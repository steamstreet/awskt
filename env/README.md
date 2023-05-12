### AWSKT: Env

Provides simplified access to environment variables, secrets and AppConfig data. Code that
uses this library doesn't have to know where the data is coming from. Instead, a variable
object is used, and environment variables determine where the data is found. 

Imagine we want to look up an API key. In our code, we define the property:

```kotlin
val apiKey = env("APIKey")


fun myCode() {
    someFunction(apiKey.value)
}
```

### Environment Variables

The easiest way to set the value is just by setting an environment variable.

### System Property

Environment variables are problematic for testing, since they cannot be changed after starting
the program. Set a system property by calling:

```kotlin
registerEnvironmentVariable("APIKey", "some-key")
```

or by setting a system property directly (note the `ENV.` prefix:

```kotlin
System.setProperty("ENV.APIKey", "some-key")
```

### Secrets

If the value of an environment variable starts with `Secret_`, it will be retrieved by
making a call to the Secrets Manager. The prefix should be followed with the id of the key,
followed by a dot and the key in the json of the secret:

```
ApiKey: Secret_my/api/key.apiKey 
```