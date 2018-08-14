package variant0

import kotlin.reflect.KClass
import kotlin.reflect.KProperty

// property id type
data class TypedKey<T>(val name: String, val defaultValue: T? = null)

class TypedKeyDelegate<T>(val defaultValue: T? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TypedKey<T> = TypedKey(property.name, defaultValue)
}

fun <T> typedKey(defaultValue: T? = null) = TypedKeyDelegate(defaultValue)


// base class for properties groups - used for generic invoke
interface PropertiesGroup {
}

// different groups of properties

object PropertiesGroup1 : PropertiesGroup {
    val group1prop1 = TypedKey<Int>("g1p1")
    val group1prop2 = TypedKey<String>("g1p2")
}

object PropertiesGroup2 : PropertiesGroup {
    val group3prop1 = TypedKey<Boolean>("g3p1")
}


// properties bag/builder
open class ScriptingProperties<DefaultGroup: PropertiesGroup>(
        body: ScriptingProperties<DefaultGroup>.() -> Unit = {} // for usages via constructor (1)
) {
    val data = HashMap<TypedKey<*>, Any?>()

    init {
        // usages with init in the derived class/object (2)
        body()
    }

    open fun DefaultGroup.setup() {}

    open val setupFn: DefaultGroup.() -> Unit = {}

    // generic invoke for properties groups
    inline operator fun <T: PropertiesGroup> T.invoke(body: T.() -> Unit) = body()

    // generic builder for all properties
    inline operator fun <reified T> TypedKey<T>.invoke(v: T) {
        data[this] = v
    }
}


@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KotlinScriptProperties(
        val properties: KClass<out ScriptingProperties<*>> // object or class filled in 0-ary constructor
)

// --------------------------
// Examples:

// -- (1)

object SampleProps1 : ScriptingProperties<PropertiesGroup1>({
    PropertiesGroup1.group1prop1(42)
    PropertiesGroup2 {
        group3prop1(false)
    }
})

@KotlinScriptProperties(SampleProps1::class)
abstract class SampleScript1

// -- (2)

object SampleProps2 : ScriptingProperties<PropertiesGroup1>() {
    init {
        PropertiesGroup1.group1prop1(42)
        PropertiesGroup2 {
            group3prop1(false)
        }
    }
}

@KotlinScriptProperties(SampleProps2::class)
abstract class SampleScript2

// -- (3)

class SampleProps3 : ScriptingProperties<PropertiesGroup1>() {
    override val setupFn: PropertiesGroup1.() -> Unit = {
        group1prop1(42)
        PropertiesGroup2 {
            group3prop1(false)
        }
    }
}

@KotlinScriptProperties(SampleProps3::class)
abstract class SampleScript3
