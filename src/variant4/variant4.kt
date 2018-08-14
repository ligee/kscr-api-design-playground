
package variant4

import kotlin.reflect.KProperty

// -------------------------------------------------------------------------------
// properties collection

interface PropertiesCollection {

    val properties: Map<Key<*>, Any> get() = emptyMap()

    data class Key<T>(val name: String, val defaultValue: T? = null)

    class PropertyKeyDelegate<T>(private val defaultValue: T? = null) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Key<T> =
                Key(property.name, defaultValue)
    }

    companion object {
        fun <T> key(defaultValue: T? = null) = PropertyKeyDelegate(defaultValue)
    }

    fun properties(body: Builder.() -> Unit): Map<Key<*>, Any> = Builder().apply(body).data

    // -------------------------------------------------------------------------------
    // properties builder base class (DSL)

    open class Builder {

        val data: MutableMap<PropertiesCollection.Key<*>, Any> = LinkedHashMap()

        // generic builder for all properties
        operator fun <T: Any> PropertiesCollection.Key<T>.invoke(v: T) {
            set(this, v)
        }

        // builder for lists
        operator fun <T> PropertiesCollection.Key<List<T>>.invoke(vararg vals: T) {
            set(this, vals.asList())
        }

        // builder from types, requiring the Builder to be a class rather than an interface
        inline operator fun <reified K> PropertiesCollection.Key<String>.invoke() {
            set(this, K::class.qualifiedName!!)
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
}

fun <T> PropertiesCollection.getOrNull(key: PropertiesCollection.Key<T>): T? =
        properties.get(key)?.let { it as T } ?: key.defaultValue

// -------------------------------------------------------------------------------
// script definition - primary usage of the builder base class

interface ScriptDefinition : PropertiesCollection {

    // inherited from script definition for using as a keys anchor
    companion object : ScriptDefinition {

        class Builder internal constructor () : ScriptDefinition, PropertiesCollection.Builder()
        {
            override val properties = data
        }

        // builder for script definition
        fun create(body: Builder.() -> Unit): ScriptDefinition = Builder().apply(body)
    }
}

// script definition properties

val ScriptDefinition.name by PropertiesCollection.key<String>()
val ScriptDefinition.fileNameExtension by PropertiesCollection.key<String>()
val ScriptDefinition.dependencies by PropertiesCollection.key<List<String>>()
val ScriptDefinition.defaultImports by PropertiesCollection.key<List<String>>()
val ScriptDefinition.refineConfigurationHandler by PropertiesCollection.key<String>()
val ScriptDefinition.refineConfigurationBeforeParsing by PropertiesCollection.key<Boolean>()

// alternative way to declare definition for annotation

abstract class ScriptDefinitionForAnnotation : ScriptDefinition, PropertiesCollection.Builder()
{
    abstract fun properties() // need to implement a (single) call to it on a use site or here in properties accessor

    override val properties = data
}

// -------------------------------------------------------------------------------
// builder for complex property - builder extension

class RefineConfiguration : PropertiesCollection.Builder() {

    fun handler(fn: String) {
        set(ScriptDefinition.refineConfigurationHandler, fn)
    }

    fun triggerBeforeParsing(value: Boolean = true) {
        set(ScriptDefinition.refineConfigurationBeforeParsing, value)
    }
}

val ScriptDefinition.refineConfiguration get() = RefineConfiguration()

// -------------------------------------------------------------------------------
// platform specific properties - another builder extension

class JvmSpecificProperties : PropertiesCollection.Builder() {

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

object MyScriptDefinition : ScriptDefinition {
    override val properties = properties {
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

// alternative:

object MyScriptDefinition2 : ScriptDefinitionForAnnotation() {
    override fun properties() {
        name("aaa")
    }
}

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
