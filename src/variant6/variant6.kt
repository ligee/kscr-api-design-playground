
package variant6

import kotlin.reflect.KProperty

// -------------------------------------------------------------------------------
// properties collection

open class PropertiesCollection(private val properties: Map<Key<*>, Any> = emptyMap()) {

    data class Key<T>(val name: String, val defaultValue: T? = null)

    class PropertyKeyDelegate<T>(private val defaultValue: T? = null) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Key<T> =
                Key(property.name, defaultValue)
    }

    companion object {
        fun <T> key(defaultValue: T? = null) = PropertyKeyDelegate(defaultValue)
    }

    operator fun <T> get(key: PropertiesCollection.Key<T>): T? = properties[key]?.let { it as T } ?: key.defaultValue

    // -------------------------------------------------------------------------------
    // properties builder base class (DSL)

    open class Builder(baseProperties: Iterable<PropertiesCollection> = emptyList()) {

        val data: MutableMap<PropertiesCollection.Key<*>, Any> = LinkedHashMap<PropertiesCollection.Key<*>, Any>().apply {
            baseProperties.forEach { putAll(it.properties) }
        }

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

        // a class for extending properties, see jvm below
        interface BuilderExtension<T: Builder> {
            fun get(): T
        }

        // include another builder extension
        operator fun <T: Builder> BuilderExtension<T>.invoke(body: T.() -> Unit) {
            val builder = this.get().apply(body)
            builder.data.putAll(this@Builder.data)
        }

        // direct manipulation - public - for usage in inline dsl methods and for extending dsl
        operator fun <T, V : Any> set(key: PropertiesCollection.Key<T>, value: V) {
            data[key] = value
        }
    }
}

// -------------------------------------------------------------------------------
// script definition - primary usage of the builder base class

interface ScriptDefinitionKeys

open class ScriptDefinition(baseDefinitions: Iterable<ScriptDefinition>, body: Builder.() -> Unit)
    : PropertiesCollection(Builder(baseDefinitions).apply(body).data) {

    constructor(body: Builder.() -> Unit = {}) : this(emptyList(), body)
    constructor(vararg baseDefinitions: ScriptDefinition, body: Builder.() -> Unit = {}) : this(baseDefinitions.asIterable(), body)

    class Builder internal constructor (baseDefinitions: Iterable<ScriptDefinition>) : ScriptDefinitionKeys, PropertiesCollection.Builder(baseDefinitions)

    // inherited from script definition for using as a keys anchor
    companion object : ScriptDefinitionKeys
}

// advanced types for some properties

interface ScriptData {
    val scriptSource: String
    val scriptDefinition: ScriptDefinition
    val configuration: ScriptDefinition? // for simplicity, in fact - another container
    val processedScriptData: String? // for simplicity, in fact yet another container
}

typealias RefineScriptConfigurationHandler = (ScriptData) -> ScriptDefinition?

class RefineOnAnnotationsProperty(vararg val annotations: String, val handler: RefineScriptConfigurationHandler)

// script definition properties

val ScriptDefinitionKeys.name by PropertiesCollection.key<String>()
val ScriptDefinitionKeys.fileNameExtension by PropertiesCollection.key<String>()
val ScriptDefinitionKeys.dependencies by PropertiesCollection.key<List<String>>()
val ScriptDefinitionKeys.defaultImports by PropertiesCollection.key<List<String>>()
val ScriptDefinitionKeys.refineConfigurationBeforeParsing by PropertiesCollection.key<RefineScriptConfigurationHandler>()
val ScriptDefinitionKeys.refineConfigurationOnAnnotations by PropertiesCollection.key<RefineOnAnnotationsProperty>()

// -------------------------------------------------------------------------------
// builder for complex property - a builder for filling existing properties more conveniently

class RefineConfiguration : PropertiesCollection.Builder() {

    fun beforeParsing(handler: RefineScriptConfigurationHandler) {
        set(ScriptDefinition.refineConfigurationBeforeParsing, handler)
    }

    fun onAnnotations(vararg annotations: String, handler: RefineScriptConfigurationHandler) {
        set(ScriptDefinition.refineConfigurationOnAnnotations, RefineOnAnnotationsProperty(*annotations, handler = handler))
    }
}

val ScriptDefinitionKeys.refineConfiguration get() = RefineConfiguration()

// -------------------------------------------------------------------------------
// platform specific properties - another builder extension

interface JvmSpecificPropertiesKeys

class JvmSpecificPropertiesBuilder : JvmSpecificPropertiesKeys, PropertiesCollection.Builder() {

    companion object : PropertiesCollection.Builder.BuilderExtension<JvmSpecificPropertiesBuilder>, JvmSpecificPropertiesKeys {
        override fun get() = JvmSpecificPropertiesBuilder()
    }
}

val JvmSpecificPropertiesKeys.javaHome by PropertiesCollection.key<String>()

val ScriptDefinitionKeys.jvm get() = JvmSpecificPropertiesBuilder

// -------------------------------------------------------------------------------
// evaluation environment properties - another usage of the builder base class

interface ScriptEvaluationEnvironmentKeys

class ScriptEvaluationEnvironment(body: Builder.() -> Unit = {}) : PropertiesCollection(Builder().apply(body).data) {

    class Builder internal constructor () : ScriptEvaluationEnvironmentKeys, PropertiesCollection.Builder()

    companion object : ScriptEvaluationEnvironmentKeys
}

val ScriptEvaluationEnvironmentKeys.implicitReceivers by PropertiesCollection.key<List<Any>>()
val ScriptEvaluationEnvironmentKeys.contextVariables by PropertiesCollection.key<List<Pair<String, Any?>>>()

// -------------------------------------------------------------------------------
// Usage 1: script definition

object MyScriptDefinition1 : ScriptDefinition({
    name("abc")
    fileNameExtension("my.kts")
    dependencies("base.jar")
    refineConfiguration {
        beforeParsing { it.configuration } // return unchanged
        onAnnotations("DependsOn", "Repository") {
            // user's handler:
            // val foundAnnotations = it.processedScriptData[ProcessedScriptDataProperties.annotations]
            val newDependencies = listOf("foo.jar") // resolve(foundAnnotations)
            val newImports = listOf("foo.bar.*") // getDefaultImports(newDependencies)

            ScriptDefinition(it.scriptDefinition) {
                dependencies(newDependencies)
                defaultImports(newImports)
            }
        }
    }
    jvm {
        javaHome("/my/java/home")
    }
})

// create definition for using outside of annotation

val myScriptDefinition2 = ScriptDefinition {
    name("def")
    jvm {
        javaHome("/my/java/home")
    }
}

// -------------------------------------------------------------------------------
// Usage 2: scripting evaluation environment

fun eval(/* script: CompiledScript  */) {
    val environment = ScriptEvaluationEnvironment {
        implicitReceivers("Abc")
        contextVariables("var1" to 10, "var2" to "foo")
    }
    // host.eval(script, environment)
}

// -------------------------------------------------------------------------------
// Usage 3: get properties from a collection

fun useProps(scriptDefinition: ScriptDefinition): String {
    val name = scriptDefinition[ScriptDefinition.name] ?: "script"
    val deps = scriptDefinition[ScriptDefinition.dependencies]?.joinToString()
    val javaHome = scriptDefinition[ScriptDefinition.jvm.javaHome]
    return "$name deps: $deps"
}
