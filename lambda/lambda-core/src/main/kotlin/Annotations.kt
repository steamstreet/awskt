package com.steamstreet.aws.lambda

/**
 * A useful annotation to attach to a class or secondary constructor to
 * indicate that it's a lambda and won't be flagged as unused.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.SOURCE)
public annotation class AWSLambdaConstructor

