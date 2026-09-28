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

### Missing Values

`Env[key]`, `Env.lazy(key)` and an `env(name)` property without a default are required. When the
variable has no value, because it is unset, set to `_NoValue`, or refers to a secret that could not
be read, they throw an `IllegalStateException` naming it:

```
java.lang.IllegalStateException: Environment variable 'APIKey' is not set
```

Through 3.1.5 this was a bare `NullPointerException` that did not name the variable. Use
`Env.optional(key)`, or `env(name).optional`, for a variable that may be absent.

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