
package variant5

import kotlin.reflect.KProperty

// -------------------------------------------------------------------------------
// properties collection

open class PropertiesCollection(val properties: Map<Key<*>, Any> = emptyMap()) {

    sealed class Key<T>(val name: String, val defaultValue: T? = null) {

        open class AnyKey<T>(name: String, defaultValue: T? = null) : Key<T>(name, defaultValue) {

            // generic builder for all properties
            operator fun <T: Any> Builder.invoke(v: T) {
                set(this@AnyKey, v)
            }

            class Delegate<T>(private val defaultValue: T? = null): DelegateBase<T> {
                override operator fun getValue(thisRef: Any?, property: KProperty<*>): AnyKey<T> = AnyKey(property.name, defaultValue)
            }
        }

        class StringKey(name: String, defaultValue: String? = null) : AnyKey<String>(name, defaultValue) {
            // builder from types, requiring the Builder to be a class rather than an interface
            inline operator fun <reified K> Builder.invoke() {
                set(this@StringKey, K::class.qualifiedName!!)
            }

            class Delegate(private val defaultValue: String? = null) : DelegateBase<String> {
                override operator fun getValue(thisRef: Any?, property: KProperty<*>): StringKey = StringKey(property.name, defaultValue)
            }
        }

        interface DelegateBase<T> {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): Key<T>
        }
    }

    companion object {
        inline fun <reified T> key(defaultValue: T? = null) = when (T::class) {
            String::class -> Key.StringKey.Delegate(defaultValue as String)
            else -> Key.AnyKey.Delegate<T>(defaultValue)
        }
    }

    // -------------------------------------------------------------------------------
    // properties builder base class (DSL)

    interface Builder {

        val data: MutableMap<PropertiesCollection.Key<*>, Any>

        // builder for lists
        operator fun <T> PropertiesCollection.Key<List<T>>.invoke(vararg vals: T) {
            set(this, vals.asList())
        }

        // include other properties
        fun include(other: PropertiesCollection?) {
            other?.properties?.let { data.putAll(it) }
        }

        // include another builder
        operator fun <T: Builder> T.invoke(body: T.() -> Unit) {
            this.body()
            this@Builder.data.putAll(this.data)
        }

        // direct manipulation - public - for usage in inline dsl methods and for extending dsl

        operator fun <T, V : Any> set(key: PropertiesCollection.Key<T>, value: V) {
            data[key] = value
        }

        operator fun <T, V> get(key: PropertiesCollection.Key<T>): V? = data[key]?.let { it as V }
    }

    class BuilderImpl(override val data: MutableMap<Key<*>, Any> = linkedMapOf()) : Builder
}

fun <T> PropertiesCollection.getOrNull(key: PropertiesCollection.Key<T>): T? =
        properties.get(key)?.let { it as T } ?: key.defaultValue

// -------------------------------------------------------------------------------
// script definition - primary usage of the builder base class

open class ScriptDefinition(body: Builder.() -> Unit = {}) : PropertiesCollection(Builder().apply(body).data) {

    class Builder internal constructor () : ScriptDefinition(), PropertiesCollection.Builder by PropertiesCollection.BuilderImpl()

    // inherited from script definition for using as a keys anchor
    companion object {
        // builder for script definition
        fun create(body: Builder.() -> Unit): ScriptDefinition = Builder().apply(body)
    }
}

// script definition properties

val ScriptDefinition.Companion.name by PropertiesCollection.key<String>()
val ScriptDefinition.Companion.fileNameExtension by PropertiesCollection.key<String>()
val ScriptDefinition.Companion.dependencies by PropertiesCollection.key<List<String>>()
val ScriptDefinition.Companion.defaultImports by PropertiesCollection.key<List<String>>()
val ScriptDefinition.Companion.refineConfigurationHandler by PropertiesCollection.key<String>()
val ScriptDefinition.Companion.refineConfigurationBeforeParsing by PropertiesCollection.key<String>()

// -------------------------------------------------------------------------------
// builder for complex property - builder extension

class RefineConfiguration : PropertiesCollection.Builder by PropertiesCollection.BuilderImpl() {

    fun beforeParsing(fn: String) {
        set(ScriptDefinition.refineConfigurationBeforeParsing, fn)
    }
}

val ScriptDefinition.refineConfiguration get() = RefineConfiguration()

// -------------------------------------------------------------------------------
// platform specific properties - another builder extension

class JvmSpecificProperties : PropertiesCollection.Builder by PropertiesCollection.BuilderImpl() {

    val javaHome by PropertiesCollection.key<String>()
}

val ScriptDefinition.jvm get() = JvmSpecificProperties()

// -------------------------------------------------------------------------------
// evaluation environment properties - another usage of the builder base class

interface ScriptEvaluationEnvironment : PropertiesCollection {

    // seems need to repeat such a companion object for every property collection
    companion object : ScriptEvaluationEnvironment {

        class Builder internal constructor () : ScriptEvaluationEnvironment, PropertiesCollection.Builder() {
            override val properties = data
        }

        // builder for script definition
        fun create(body: Builder.() -> Unit): ScriptEvaluationEnvironment = Builder().apply(body)
    }
}

val ScriptEvaluationEnvironment.implicitReceivers by PropertiesCollection.key<List<Any>>()
val ScriptEvaluationEnvironment.contextVariables by PropertiesCollection.key<List<Pair<String, Any?>>>()

// -------------------------------------------------------------------------------
// Usage 1: script definition

object MyScriptDefinition : ScriptDefinition({
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
})

// create definition for using outside of annotation

val myScriptDefinition3 = ScriptDefinition.create {
    name("def")
}

// -------------------------------------------------------------------------------
// Usage 2: configuration refining

// user's handler
suspend fun refineConfiguration(
        scriptSource: String,
        scriptDefinition: ScriptDefinition,
        compileConfiguration: PropertiesCollection?,
        processedScriptData: PropertiesCollection
): PropertiesCollection? {
    // val foundAnnotations = processedScriptData[ProcessedScriptDataProperties.annotations]
    val newDependencies = listOf("foo.jar") // resolve(foundAnnotations)
    val newImports = listOf("foo.bar.*") // getDefaultImports(newDependencies)

    return ScriptDefinition.create {
        dependencies(newDependencies)
        defaultImports(newImports)
    }
}

// -------------------------------------------------------------------------------
// Usage 3: scripting evaluation environment

fun eval(/* script: CompiledScript  */) {
    val environment = ScriptEvaluationEnvironment.create {
        implicitReceivers("Abc")
        contextVariables("var1" to 10, "var2" to "foo")
    }
    // host.eval(script, environment)
}

// -------------------------------------------------------------------------------
// Usage 4: get properties from a collection

fun useProps(scriptDefinition: ScriptDefinition): String {
    val name = scriptDefinition.getOrNull(ScriptDefinition.name) ?: "script"
    val deps = scriptDefinition.getOrNull(ScriptDefinition.dependencies)?.joinToString()
    return "$name deps: $deps"
}
