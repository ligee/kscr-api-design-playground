
package variant1

import kotlin.reflect.KProperty

typealias PropertyBag = Map<PropertyKey<*>, Any>

// -------------------------------------------------------------------------------
// property key

data class PropertyKey<T>(val name: String, val defaultValue: T? = null)

class PropertyKeyDelegate<T>(private val defaultValue: T? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): PropertyKey<T> =
            PropertyKey(property.name, defaultValue)
}

fun <T> propertyKey(defaultValue: T? = null) = PropertyKeyDelegate(defaultValue)

//

// -------------------------------------------------------------------------------
// properties builder base class (DSL)

open class ScriptingPropertiesBuilder {

    // generic builder for all properties
    operator fun <T: Any> PropertyKey<T>.invoke(v: T) {
        set(this, v)
    }

    // builder for lists
    operator fun <T> PropertyKey<List<T>>.invoke(vararg vals: T) {
        set(this, vals.asList())
    }

    // include other builder
    fun include(other: ScriptingPropertiesBuilder) {
        data.putAll(other.data)
    }

    operator fun <T: ScriptingPropertiesBuilder> T.invoke(body: T.() -> Unit) {
        this.body()
        include(this)
    }

    // direct manipulation - public - for usage in inline dsl methods and for extending dsl

    operator fun <T, V : Any> set(key: PropertyKey<T>, value: V) {
        data[key] = value
    }

    @JvmName("getStrict")
    operator fun <T, V> get(key: PropertyKey<T>): V? = data[key]?.let { it as V }

    // implementation details

    private val data = LinkedHashMap<PropertyKey<*>, Any>()

    internal fun build(): PropertyBag = data
}

// -------------------------------------------------------------------------------
// script definition - primary usage of the builder base class

abstract class ScriptDefinition : ScriptingPropertiesBuilder() {

    abstract fun scriptDefinition(): Unit

    // the way to use keys outside of DSL
    object keys : ScriptDefinition() {
        override fun scriptDefinition() {}
    }
}

// builder for script definition

fun buildScriptCompileConfiguration(body: ScriptDefinition.() -> Unit): PropertyBag {
    val builder = object : ScriptDefinition() {
        override fun scriptDefinition() {
            body()
        }
    }
    return builder.build()
}

// script definition properties

val ScriptDefinition.name by propertyKey<String>()
val ScriptDefinition.fileNameExtension by propertyKey<String>()
val ScriptDefinition.dependencies by propertyKey<List<String>>()
val ScriptDefinition.defaultImports by propertyKey<List<String>>()
val ScriptDefinition.refineConfigurationHandler by propertyKey<String>()
val ScriptDefinition.refineConfigurationBeforeParsing by propertyKey<Boolean>()

// -------------------------------------------------------------------------------
// builder for complex property - another usage of the builder base class

class RefineConfigurationBuilder : ScriptingPropertiesBuilder() {
    inline operator fun invoke(body: RefineConfigurationBuilder.() -> Unit) {
        body()
    }

    fun handler(fn: String) {
        set(ScriptDefinition.keys.refineConfigurationHandler, fn)
    }

    fun triggerBeforeParsing(value: Boolean = true) {
        set(ScriptDefinition.keys.refineConfigurationBeforeParsing, value)
    }
}

fun ScriptDefinition.refineConfiguration(body: RefineConfigurationBuilder.() -> Unit) {
    include(RefineConfigurationBuilder().apply(body))
}

// -------------------------------------------------------------------------------
// platform specific properties - another usage of the builder base class

class JvmSpecificProperties : ScriptingPropertiesBuilder() {
    companion object {
        // for accessing keys outside of DSL
        val keys = JvmSpecificProperties()
    }
}

val JvmSpecificProperties.javaHome by propertyKey<String>()

fun ScriptDefinition.jvm(body: JvmSpecificProperties.() -> Unit) {
    include(JvmSpecificProperties().apply(body))
}

// -------------------------------------------------------------------------------
// evaluation environment properties - another usage of the builder base class

abstract class ScriptEvaluationEnvironment : ScriptingPropertiesBuilder() {
    abstract fun properties()

    // the way to use keys outside of DSL
    object keys : ScriptEvaluationEnvironment() {
        override fun properties() {}
    }
}

val ScriptEvaluationEnvironment.implicitReceivers by propertyKey<List<Any>>()
val ScriptEvaluationEnvironment.contextVariables by propertyKey<List<Pair<String, Any?>>>()

fun buildScriptEvaluationEnvironment(body: ScriptEvaluationEnvironment.() -> Unit): PropertyBag {
    val builder = object : ScriptEvaluationEnvironment() {
        override fun properties() {
            body()
        }
    }
    return builder.build()
}

// -------------------------------------------------------------------------------
// Usage 1: script definition

object MyScriptDefinition : ScriptDefinition() {
    override fun scriptDefinition() {
        name("abc")
        fileNameExtension("my.kts")
        dependencies("base.jar")
        refineConfiguration {
            handler("handler")
            triggerBeforeParsing(true)
        }
        jvm {
            javaHome("/my/java/home")
        }
    }
}

// -------------------------------------------------------------------------------
// Usage 2: configuration refining

// user's handler
suspend fun refineConfiguration(
        scriptSource: String,
        scriptDefinition: ScriptDefinition,
        compileConfiguration: PropertyBag?,
        processedScriptData: PropertyBag
): PropertyBag? {
    // val foundAnnotations = processedScriptData[ProcessedScriptDataProperties.annotations]
    val newDependencies = listOf("foo.jar") // resolve(foundAnnotations)
    val newImports = listOf("foo.bar.*") // getDefaultImports(newDependencies)

    // building result manually
    val resultInMap = mapOf<PropertyKey<*>, Any>(
            ScriptDefinition.keys.dependencies to newDependencies,
            ScriptDefinition.keys.defaultImports to newImports
    )
    // building with dsl
    val resultViaDsl = buildScriptCompileConfiguration {
        dependencies(newDependencies)
        defaultImports(newImports)
    }
    return resultInMap
}

// -------------------------------------------------------------------------------
// Usage 3: scripting evaluation environment

fun eval(/* script: CompiledScript  */) {
    val environment = buildScriptEvaluationEnvironment {
        implicitReceivers("Abc")
        contextVariables("var1" to 10, "var2" to "foo")
    }
    // host.eval(script, environment)
}
