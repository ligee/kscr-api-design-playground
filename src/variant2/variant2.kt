
package variant2

import kotlin.reflect.KClass
import kotlin.reflect.KProperty

// -------------------------------------------------------------------------------
// properties collection

interface PropertiesCollection {

    data class Key<T>(val name: String, val defaultValue: T? = null)

    val properties: Map<Key<*>, Any>?

    class PropertyKeyDelegate<T>(private val defaultValue: T? = null) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Key<T> =
                Key(property.name, defaultValue)
    }

    // additional delegate for dsl extenders
    class ScriptingPropertiesBuilderDelegate<T : ScriptingPropertiesBuilderExtension>(val kclass: KClass<T>) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): KClass<T> = kclass
    }

    companion object {
        fun <T> key(defaultValue: T? = null) = PropertyKeyDelegate(defaultValue)
    }

    fun properties(body: PropertiesBuilder.() -> Unit): Map<Key<*>, Any> {
        val builder = object : PropertiesBuilder {
            override val data = LinkedHashMap<Key<*>, Any>()
        }
        builder.body()
        return builder.data
    }

    // -------------------------------------------------------------------------------
    // properties builder base class (DSL)

    interface PropertiesBuilder {

        // generic builder for all properties
        operator fun <T: Any> PropertiesCollection.Key<T>.invoke(v: T) {
            set(this, v)
        }

        // builder for lists
        operator fun <T> PropertiesCollection.Key<List<T>>.invoke(vararg vals: T) {
            set(this, vals.asList())
        }

        // include other properties
        fun include(other: PropertiesCollection) {
            other.properties?.let { data.putAll(it) }
        }

        // for using with extensions
        operator fun <T : ScriptingPropertiesBuilderExtension> KClass<T>.invoke(body: T.() -> Unit) {
            this.constructors.first().call(this@PropertiesBuilder).also {
                it.body()
            }
        }

        // direct manipulation - public - for usage in inline dsl methods and for extending dsl

        operator fun <T, V : Any> set(key: PropertiesCollection.Key<T>, value: V) {
            data[key] = value
        }

        operator fun <T, V> get(key: PropertiesCollection.Key<T>): V? = data[key]?.let { it as V }

        // implementation details

        val data: MutableMap<PropertiesCollection.Key<*>, Any>
    }

    // extension
    open class ScriptingPropertiesBuilderExtension(val parentBuilder: PropertiesBuilder)
}


// -------------------------------------------------------------------------------
// script definition - primary usage of the builder base class

abstract class ScriptDefinition: PropertiesCollection {

    companion object {
        // the way to use keys outside of DSL
        val keys = object : ScriptDefinition() { override val properties = null }

        class Builder : ScriptDefinition(), PropertiesCollection.PropertiesBuilder {
            override val data = LinkedHashMap<PropertiesCollection.Key<*>, Any>()
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

abstract class ScriptDefinitionForAnnotation : ScriptDefinition(), PropertiesCollection.PropertiesBuilder {

    abstract fun scriptDefinition()

    override val data = LinkedHashMap<PropertiesCollection.Key<*>, Any>()
    override val properties = data
}

// -------------------------------------------------------------------------------
// builder for complex property - builder extension

class RefineConfigurationBuilder(parent: PropertiesCollection.PropertiesBuilder) : PropertiesCollection.ScriptingPropertiesBuilderExtension(parent) {
    inline operator fun invoke(body: RefineConfigurationBuilder.() -> Unit) {
        body()
    }

    fun handler(fn: String) {
        parentBuilder.set(ScriptDefinition.keys.refineConfigurationHandler, fn)
    }

    fun triggerBeforeParsing(value: Boolean = true) {
        parentBuilder.set(ScriptDefinition.keys.refineConfigurationBeforeParsing, value)
    }
}

val ScriptDefinition.refineConfiguration by PropertiesCollection.ScriptingPropertiesBuilderDelegate(RefineConfigurationBuilder::class)

// -------------------------------------------------------------------------------
// platform specific properties - another builder extension

class JvmSpecificProperties(parent: PropertiesCollection.PropertiesBuilder) : PropertiesCollection.ScriptingPropertiesBuilderExtension(parent) {
    val javaHome by PropertiesCollection.key<String>()
}

val ScriptDefinition.jvm by PropertiesCollection.ScriptingPropertiesBuilderDelegate(JvmSpecificProperties::class)

// -------------------------------------------------------------------------------
// evaluation environment properties - another usage of the builder base class

abstract class ScriptEvaluationEnvironment : PropertiesCollection {

    // seems need to repeat such a companion object for every property collection
    companion object {
        // the way to use keys outside of DSL
        val keys = object : ScriptEvaluationEnvironment() { override val properties = null }

        class Builder : ScriptEvaluationEnvironment(), PropertiesCollection.PropertiesBuilder {
            override val data = LinkedHashMap<PropertiesCollection.Key<*>, Any>()
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

object MyScriptDefinition : ScriptDefinition() {
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
    override fun scriptDefinition() {
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
