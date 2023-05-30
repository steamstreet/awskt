package com.steamstreet.dynamokt

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


/**
 * A kotlin property delegate for reading and writing Dynamo attributes.
 */
public open class ItemAttributeDelegate<T, in R : ItemContainer>(
    private val serializer: AttributeSerializer<T>,
    private val attributeName: String? = null
) :
    ReadWriteProperty<R, T> {
    private var hasDefault = false
    private var defaultValue: T? = null

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        return runBlocking {
            val attribute = thisRef.entity.get(attributeName ?: property.name)
            serializer.deserialize(thisRef, attribute)
        }
    }

    /**
     * Set a default value for the attribute. Works only on strings for the time being
     */
    public fun withDefault(default: T): ItemAttributeDelegate<T, R> {
        hasDefault = true
        defaultValue = default
        return this
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        val updater = thisRef.entity as MutableItem
        val attribute = serializer.serialize(thisRef, value)
        if (attribute != null) {
            updater[attributeName ?: property.name] = attribute
        } else {
            updater.delete(attributeName ?: property.name)
        }
    }
}

/**
 * Defined for classes that can serialize between dynamo attributes and objects.
 */
public interface AttributeSerializer<T> {
    public fun serialize(container: ItemContainer, value: T): AttributeValue?
    public fun deserialize(container: ItemContainer, attribute: AttributeValue?): T

    public fun <R : ItemContainer> attribute(attributeName: String? = null): ItemAttributeDelegate<T, R> {
        return ItemAttributeDelegate(this, attributeName)
    }
}

/**
 * Class to make it easier to build custom attribute serializer
 */
public class AttributeSerializerBuilder<T> {
    public var f: ((AttributeValue?) -> T)? = null
    public var t: ((T) -> AttributeValue?)? = null

    public fun from(f: (AttributeValue?) -> T) {
        this.f = f
    }

    public fun to(t: (T) -> AttributeValue?) {
        this.t = t
    }

    public fun build(): AttributeSerializer<T> {
        return object : AttributeSerializer<T> {
            override fun serialize(container: ItemContainer, value: T): AttributeValue? = t!!(value)
            override fun deserialize(container: ItemContainer, attribute: AttributeValue?): T = f!!(attribute)
        }
    }
}

/**
 * Helper for attaching attribute serializers
 */
public fun <T, R : ItemContainer> attribute(
    attributeName: String? = null,
    builder: (AttributeSerializerBuilder<T>.() -> Unit)
): ItemAttributeDelegate<T, R> {
    val b = AttributeSerializerBuilder<T>()
    b.builder()
    return ItemAttributeDelegate(b.build(), attributeName)
}

/**
 * Uses both the pk and sk to build a single string identifier
 */
public class IdAttribute<in R : ItemContainer> : ReadOnlyProperty<R, String> {
    override fun getValue(thisRef: R, property: KProperty<*>): String {
        return "${thisRef.entity.attributes[thisRef.entity.dynamo.pkName]?.asS()}/${thisRef.entity.attributes[thisRef.entity.dynamo.skName]?.asS()}"
    }
}

/**
 * A string attribute delegate
 */
@Suppress("UNCHECKED_CAST")
public fun <R : ItemContainer, S : String?> R.stringAttribute(
    attributeName: String? = null,
    defaultValue: String? = null
): ItemAttributeDelegate<S, R> = attribute<S, R>(attributeName) {
    from {
        (it?.asSOrNull() ?: defaultValue) as S
    }
    to {
        it?.attributeValue()
    }
}

public class Converter<C, T> {
    internal var f: ((T) -> C)? = null
    internal var t: ((C) -> T)? = null
    public fun from(from: (T) -> C) {
        f = from
    }

    public fun to(to: (C) -> T) {
        t = to
    }
}

@Suppress("UNCHECKED_CAST")
public fun <R : ItemContainer, C> R.stringBacked(
    attributeName: String? = null,
    converter: Converter<C, String?>.() -> Unit
): ItemAttributeDelegate<C, R> = attribute<C, R>(attributeName) {
    from {
        val c = Converter<C, String?>().apply(converter)
        it?.asS().let {
            c.f?.invoke(it) as C
        }
    }
    to {
        Converter<C, String?>().apply(converter).t?.invoke(it)?.let { str ->
            AttributeValue.S(str)
        }
    }
}

/**
 * An integer attribute delegate (supports null and not-null integers)
 */
@Suppress("UNCHECKED_CAST")
public fun <R : ItemContainer, S : Int?> R.intAttribute(attributeName: String? = null): ItemAttributeDelegate<S, R> =
    attribute<S, R>(attributeName) {
        from {
            it?.asNOrNull()?.toInt() as S
        }
        to {
            it?.let { AttributeValue.N(it.toString()) }
        }
    }

public fun <R : ItemContainer> R.intAttribute(
    default: Int,
    attributeName: String? = null
): ItemAttributeDelegate<Int, R> = attribute<Int, R>(attributeName) {
    from {
        it?.asNOrNull()?.toInt() ?: default
    }
    to {
        AttributeValue.N(it.toString())
    }
}

public fun <R : ItemContainer> R.boolAttribute(
    default: Boolean,
    attributeName: String? = null
): ItemAttributeDelegate<Boolean, R> = attribute<Boolean, R>(attributeName) {
    from {
        it?.asBoolOrNull() ?: default
    }
    to {
        AttributeValue.Bool(it)
    }
}

/**
 * Delegate for a string list.
 */
public fun <R : ItemContainer> R.stringListAttribute(
    default: List<String> = emptyList(),
    attributeName: String? = null
): ItemAttributeDelegate<List<String>, R> = attribute<List<String>, R>(attributeName) {
    from {
        it?.let {
            it.asLOrNull()?.map { it.asS() }
        } ?: default
    }
    to { strList ->
        if (strList.isEmpty())
            null
        else
            AttributeValue.L(strList.map { it.attributeValue() })
    }
}

/**
 * Delegate for a string set.
 */
public fun <R : ItemContainer> R.stringSetAttribute(
    default: Set<String> = emptySet(),
    attributeName: String? = null
): ItemAttributeDelegate<Set<String>, R> = attribute<Set<String>, R>(attributeName) {
    from {
        it?.asSsOrNull()?.toSet() ?: default
    }
    to { strList ->
        if (strList.isEmpty())
            null
        else
            AttributeValue.Ss(strList.toList())
    }
}

/**
 * Delegate for an enum attribute. There appears to be a bug in the Kotlin compiler that prevented use of the
 * 'attribute' helper function, so a Serializer class had to be created.
 */
public inline fun <reified T : Enum<T>, R : ItemContainer> enumAttribute(
    default: T,
    attributeName: String? = null
): ItemAttributeDelegate<T, R> {
    return ItemAttributeDelegate(EnumSerializer<T>(T::class, default), attributeName)
}

public inline fun <reified T : Enum<T>, R : ItemContainer> enumAttribute(): ItemAttributeDelegate<T?, R> {
    return ItemAttributeDelegate(NullableEnumSerializer(T::class))
}

public class EnumSerializer<T : Enum<T>>(private val cls: KClass<T>, private val default: T) : AttributeSerializer<T> {
    override fun serialize(container: ItemContainer, value: T): AttributeValue? {
        return value.name.attributeValue()
    }

    override fun deserialize(container: ItemContainer, attribute: AttributeValue?): T {
        return attribute?.let { attr ->
            cls.java.enumConstants.find { it.name == attr.asSOrNull() }
        } ?: default
    }
}


public class NullableEnumSerializer<T : Enum<T>>(private val cls: KClass<T>) : AttributeSerializer<T?> {
    override fun serialize(container: ItemContainer, value: T?): AttributeValue? {
        return value?.name?.attributeValue()
    }

    override fun deserialize(container: ItemContainer, attribute: AttributeValue?): T? {
        return attribute?.let { attr ->
            cls.java.enumConstants.find { it.name == attr.asSOrNull() }
        }
    }
}