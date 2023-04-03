# Kotlin/AWS Commons

A set of libraries that make building AWS applications in Kotlin more joyous.

Most libraries are designed for Lambda, though they are likely useful in other
contexts.

### standards

Mostly language level extensions that work with in an AWS environment.

### env

Standardizes access to environment variables, secrets, application configuration. A variable
can be stored as an environment variable, a system property, or an AWS secret, and the correct
value will be returned.

### logging

Some logging utilities. Explicit, structure logging is favored throughout. No Logger implementation
is required, simply call:

```kotlin
logInfo("Some info")
logWarning("Some warning", t)
```

Kotlin Serialization is built in, so it's also possible to log objects:

```kotlin
@Serializable
data class SomeClass(val name: String, val age: Int)

logValue("We got some info", "user", SomeClass("Steve", 22))
```

The Lambda-Core package provides a built-in logging configuration that writes these values
correctly to the Cloudwatch Log Group.