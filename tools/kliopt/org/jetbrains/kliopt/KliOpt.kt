/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.jetbrains.kliopt

import kotlin.reflect.KProperty

@DslMarker
annotation class ArgParserMarker

@ArgParserMarker
interface ArgParserElement {

}

/*class Subcommand(commandName: String) : ArgParser(commandName) {

}*/

class ArgParserDSLElement(val programName: String) : ArgParserElement {
    private val cliInstance = ArgParser(programName)

    var useDefaultHelpShortName: Boolean
        get() = cliInstance.useDefaultHelpShortName
        set(value) { cliInstance.useDefaultHelpShortName = value }

    var prefixStyle: ArgParser.OPTION_PREFIX_STYLE
        get() = cliInstance.prefixStyle
        set(value) {cliInstance.prefixStyle = value }

    operator fun get(key: String): ArgParser.ArgumentValue<*> = cliInstance[key]
    /*fun subcommand() : Subcommand {

    }*/
    fun <T : Any> option(type: ArgType<T>, fullName: String, init: OptionDescriptor<T>.() -> Unit = {}): OptionDescriptor<T> {
        val descriptor = OptionDescriptor(type, fullName)
        descriptor.init()
        cliInstance.addOption(descriptor)
        return descriptor
    }
    fun  <T : Any> argument(type: ArgType<T>, fullName: String, init: ArgDescriptor<T>.() -> Unit = {}) : ArgDescriptor<T> {
        val descriptor = ArgDescriptor(type, fullName)
        descriptor.init()
        cliInstance.addArgument(descriptor)
        return descriptor
    }
}

fun argParser(programName: String, init: ArgParserDSLElement.() -> Unit): ArgParserDSLElement {
    val parser = ArgParserDSLElement(programName)
    parser.init()
    return parser
}

// Possible types of arguments.
sealed class ArgType<T : Any>(val hasParameter: kotlin.Boolean) {
    abstract val description: kotlin.String
    abstract val convertion: (value: kotlin.String, name: kotlin.String)->T

    class Boolean : ArgType<kotlin.Boolean>(false) {
        override val description: kotlin.String
            get() = ""

        override val convertion: (value: kotlin.String, name: kotlin.String) -> kotlin.Boolean
            get() = { _, _ -> true }
    }

    class String : ArgType<kotlin.String>(true) {
        override val description: kotlin.String
            get() = "{ String }"

        override val convertion: (value: kotlin.String, name: kotlin.String) -> kotlin.String
            get() = { value, _ -> value }
    }

    class Int : ArgType<kotlin.Int>(true) {
        override val description: kotlin.String
            get() = "{ Int }"

        override val convertion: (value: kotlin.String, name: kotlin.String) -> kotlin.Int
            get() = { value, name -> value.toIntOrNull()
                    ?: error("Option $name is expected to be integer number. $value is provided.") }
    }

    class Double : ArgType<kotlin.Double>(true) {
        override val description: kotlin.String
            get() = "{ Double }"

        override val convertion: (value: kotlin.String, name: kotlin.String) -> kotlin.Double
            get() = { value, name -> value.toDoubleOrNull()
                    ?: error("Option $name is expected to be double number. $value is provided.") }
    }

    class Choice(val values: List<kotlin.String>) : ArgType<kotlin.String>(true) {
        override val description: kotlin.String
            get() = "{ Value should be one of $values }"

        override val convertion: (value: kotlin.String, name: kotlin.String) -> kotlin.String
            get() = { value, name -> if (value in values) value else error("Option $name is expected to be one of $values. $value is provided.") }
    }
}

data class Action(val callback: (parser: ArgParser) -> Unit, val parser: ArgParser)

// Common descriptor both for options and positional arguments.
abstract class Descriptor<T : Any>(val type: ArgType<T>,
                          val fullName: String,
                          val description: String? = null,
                          val defaultValue: String? = null,
                          val isRequired: Boolean = false,
                          val deprecatedWarning: String? = null) {
    abstract val textDescription: String
    abstract val helpMessage: String
}

class OptionDescriptor<T: Any>(
        type: ArgType<T>,
        fullName: String,
        val shortName: String ? = null,
        description: String? = null,
        defaultValue: String? = null,
        isRequired: Boolean = false,
        val isMultiple: Boolean = false,
        val delimiter: String? = null,
        deprecatedWarning: String? = null) : Descriptor<T> (type, fullName, description, defaultValue, isRequired, deprecatedWarning) {
    override val textDescription: String
        get() = "option --$fullName"

    override val helpMessage: String
        get() {
            val result = StringBuilder()
            result.append("    --${fullName}")
            shortName?.let { result.append(", -$it") }
            defaultValue?.let { result.append(" [$it]") }
            description?.let {result.append(" -> ${it}")}
            if (isRequired) result.append(" (always required)")
            result.append(" ${type.description}")
            deprecatedWarning?.let { result.append(" Warning: $it") }
            result.append("\n")
            return result.toString()
        }
}

class ArgDescriptor<T : Any>(
        type: ArgType<T>,
        fullName: String,
        description: String? = null,
        defaultValue: String? = null,
        isRequired: Boolean = true,
        deprecatedWarning: String? = null) : Descriptor<T> (type, fullName, description, defaultValue, isRequired, deprecatedWarning) {
    override val textDescription: String
        get() = "argument $fullName"

    override val helpMessage: String
        get() {
            val result = StringBuilder()
            result.append("    ${fullName}")
            defaultValue?.let { result.append(" [$it]") }
            description?.let {result.append(" -> ${it}")}
            if (!isRequired) result.append(" (optional)")
            result.append(" ${type.description}")
            deprecatedWarning?.let { result.append(" Warning: $it") }
            result.append("\n")
            return result.toString()
        }
}

// Arguments parser.
class ArgParser(val programName: String, var useDefaultHelpShortName: Boolean = true,
                var prefixStyle: OPTION_PREFIX_STYLE = OPTION_PREFIX_STYLE.LINUX) {

    // Map of options: key - fullname of option, value - pair of descriptor and parsed values.
    protected val options = mutableMapOf<String, ParsingValue<*, *>>()
    // Map of argumnets: key - fullname of argument, value - pair of descriptor and parsed values.
    protected val arguments = mutableMapOf<String, ParsingValue<*, *>>()

    //private lateinit var parsedValues: MutableMap<String, ParsingElement>
    //private lateinit var valuesOrigin: MutableMap<String, ValueOrigin?>
    //private lateinit var optDescriptors: Map<String, OptionDescriptor>
    private lateinit var shortNames: Map<String, ParsingValue<*, *>>

    // Use Linux-style of options description.
    protected val optionFullFormPrefix = if (prefixStyle == OPTION_PREFIX_STYLE.LINUX) "--" else "-"
    protected val optionShortFromPrefix = "-"

    enum class ValueOrigin { SET_BY_USER, SET_DEFAULT_VALUE, UNSET }
    enum class OPTION_PREFIX_STYLE { LINUX, JVM }

    operator fun get(key: String): ArgumentValue<*> =
        options[key]?.argumentValue ?: arguments[key]?.argumentValue ?: printError("There is no option or argument with name $key")

    fun <T : Any>option(type: ArgType<T>,
               fullName: String,
               shortName: String ? = null,
               description: String? = null,
               defaultValue: String? = null,
               isRequired: Boolean = false,
               isMultiple: Boolean = false,
               delimiter: String? = null,
               deprecatedWarning: String? = null): ArgumentValue<*> {
        val descriptor = OptionDescriptor(type, fullName, shortName, description, defaultValue,
                isRequired, isMultiple, delimiter, deprecatedWarning)
        val cliElement = if (isMultiple || delimiter != null) ArgumentMultipleValues(type.convertion)
            else ArgumentSingleValue(type.convertion)
        options[fullName] = ParsingValue(descriptor, cliElement)
        return cliElement
    }

    fun <T : Any>argument(type: ArgType<T>,
                 fullName: String,
                 description: String? = null,
                 defaultValue: String? = null,
                 isRequired: Boolean = true,
                 deprecatedWarning: String? = null): ArgumentValue<T> {
        val descriptor = ArgDescriptor(type, fullName, description, defaultValue, isRequired, deprecatedWarning)
        val cliElement = ArgumentSingleValue(type.convertion)
        arguments[fullName] = ParsingValue(descriptor, cliElement)
        return cliElement
    }

    internal fun <T : Any>addOption(descriptor: OptionDescriptor<T>) {
        val cliElement = if (descriptor.isMultiple || descriptor.delimiter != null) ArgumentMultipleValues(descriptor.type.convertion)
        else ArgumentSingleValue(descriptor.type.convertion)
        options[descriptor.fullName] = ParsingValue(descriptor, cliElement)
    }

    internal fun <T : Any>addArgument(descriptor: ArgDescriptor<T>) {
        val cliElement = ParsingValue(descriptor,ArgumentSingleValue(descriptor.type.convertion))
        arguments[descriptor.fullName] = cliElement
    }

    class ParsingValue<T: Any, U: Any>(val descriptor: Descriptor<T>, val argumentValue: ArgumentValue<U>) {
        fun addValue(stringValue: String) {
            if (descriptor is OptionDescriptor<*> && !descriptor.isMultiple &&
                    !argumentValue.isEmpty() && descriptor.delimiter != null) {
                error("Try to provide more than one value for ${descriptor.fullName}.")
            }
            if (descriptor is OptionDescriptor<*> && descriptor.delimiter != null) {
                stringValue.split(descriptor.delimiter).forEach {
                    argumentValue.addValue(it, descriptor.fullName)
                }
            } else {
                argumentValue.addValue(stringValue, descriptor.fullName)
            }
        }
    }

    abstract class ArgumentValue<T : Any>(val conversion: (value: String, name: String)->T) {
        protected lateinit var values: T

        abstract fun addValue(stringValue: String, argumentName: String)

        abstract fun isEmpty(): Boolean
        protected fun valuesAreInitialized() = ::values.isInitialized
        operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = if (!isEmpty()) values else null
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            values = value
        }
    }

    class ArgumentSingleValue<T : Any>(conversion: (value: String, name: String)->T): ArgumentValue<T>(conversion) {
        override fun addValue(stringValue: String, argumentName: String) {
            if (!valuesAreInitialized()) {
                values = conversion(stringValue, argumentName)
            }
            error("Try to provide more than one value $values and $stringValue for $argumentName.")
        }

        override fun isEmpty(): Boolean = !valuesAreInitialized()
    }

    class ArgumentMultipleValues<T : Any>(conversion: (value: String, name: String)->T):
            ArgumentValue<MutableList<T>>({ value, name -> mutableListOf(conversion(value, name)) }) {
        init {
            values = mutableListOf()
        }
        override fun addValue(stringValue: String, argumentName: String) {
            values.addAll(conversion(stringValue, argumentName))
        }

        override fun isEmpty() = values.isEmpty()
    }

    // Output error. Also adds help usage information for easy understanding of problem.
    fun printError(message: String): Nothing {
        error("$message\n${makeUsage()}")
    }

    // Get origin of option value.
    //fun getOrigin(name: String) = valuesOrigin[name] ?: printError("No option/argument $name in list of avaliable options")

    private fun saveAsArg(arg: String): Boolean {
        // Find uninitialized arguments.
        val nullArgs = arguments.keys.filter { arguments[it]!!.argumentValue.isEmpty() }
        val name = nullArgs.firstOrNull()
        name?. let {
            val argumentValue = arguments[name]!!
            argumentValue.descriptor.deprecatedWarning?.let { println ("Warning: $it") }
            argumentValue.addValue(arg)
            return true
        }
        return false
    }

    private fun <T : Any, U: Any>saveAsOption(parsingValue: ParsingValue<T, U>, value: String) {
        // Show deprecated warning only first time of using option.
        parsingValue.descriptor.deprecatedWarning?.let {
            if (parsingValue.argumentValue.isEmpty())
                println ("Warning: $it")
        }
        parsingValue.addValue(value)
    }

    // Try to recognize command line element as full form as known option.
    protected fun recognizeOptionFullForm(candidate: String) =
        if (candidate.startsWith(optionFullFormPrefix))
            options[candidate.substring(optionFullFormPrefix.length)]
        else null

    // Try to recognize command line element as short form as known option.
    protected fun recognizeOptionShortForm(candidate: String) =
            if (candidate.startsWith(optionFullFormPrefix))
                shortNames[candidate.substring(optionShortFromPrefix.length)]
            else null

    // Parse arguments.
    // Returns true if all arguments were parsed, otherwise return false and print help message.
    fun parse(args: Array<String>): Boolean {
        val helpDescriptor = if (useDefaultHelpShortName) OptionDescriptor(ArgType.Boolean(), "help", "h", "Usage info")
            else OptionDescriptor(ArgType.Boolean(), "help", description = "Usage info")
        options["help"] = ParsingValue(helpDescriptor, ArgumentSingleValue(helpDescriptor.type.convertion))

        var index = 0
        shortNames = options.filter { (it.value.descriptor as? OptionDescriptor<*>)?.shortName != null }.
                map { (it.value.descriptor as OptionDescriptor<*>).shortName!! to it.value }.toMap()
        //val argDescriptors = arguments.map { it.fullName to it }.toMap()
        //val descriptorsKeys = optDescriptors.keys.union(argDescriptors.keys).toList()
        //val processedValues = descriptorsKeys.map { it to mutableListOf<String>() }.toMap().toMutableMap()
        //parsedValues = descriptorsKeys.map { it to null }.toMap().toMutableMap()
        //valuesOrigin = descriptorsKeys.map { it to ValueOrigin.UNSET }.toMap().toMutableMap()
        while (index < args.size) {
            val arg = args[index]
            // Check for actions.
            /*actions.forEach { (name, action) ->
                if (arg == name) {
                    // Use parser for this action.
                    val parseResult = action.parser.parse(args.slice(index + 1..args.size - 1).toTypedArray())
                    if (parseResult)
                        action.callback(action.parser)
                    return false
                }
            }*/
            if (arg.startsWith('-')) {
                // Candidate in being option.
                // Option is found.
                val argValue = recognizeOptionShortForm(arg) ?: recognizeOptionFullForm(arg)
                argValue?.descriptor?. let {
                    if (argValue.descriptor.type.hasParameter) {
                        if (index < args.size - 1) {
                            saveAsOption(argValue, args[index + 1])
                            index++
                        } else {
                            // An error, option with value without value.
                            printError("No value for ${argValue.descriptor.textDescription}")
                        }
                    } else {
                        // Boolean flags.
                        if (argValue.descriptor.fullName == "help") {
                            println(makeUsage())
                            return false
                        }
                        saveAsOption(argValue, "true")
                    }
                } ?: run {
                    // Try save as argument.
                    if (!saveAsArg(arg)) {
                        printError("Unknown option $arg")
                    }
                }
            } else {
                // Argument is found.
                if (!saveAsArg(arg)) {
                    printError("Too many arguments! Couldn't proccess argument $arg!")
                }
            }
            index++
        }

        // Postprocess results of parsing.
        options.values.union(arguments.values).forEach { value ->
            val descriptor = value.descriptor
            // Not inited, append default value if needed.
            if (value.argumentValue.isEmpty()) {
                descriptor.defaultValue?. let {
                    value.addValue(descriptor.defaultValue)
                    //valuesOrigin[key] = ValueOrigin.SET_DEFAULT_VALUE
                } ?: run {
                    if (descriptor.isRequired) {
                        printError("Please, provide value for ${descriptor.textDescription}. It should be always set")
                    }
                }
            } else {
                //valuesOrigin[key] = ValueOrigin.SET_BY_USER
            }
        }
        return true
    }

    private fun makeUsage(): String {
        val result = StringBuilder()
        result.append("Usage: $programName options_list\n")
        if (!arguments.isEmpty()) {
            result.append("Arguments: \n")
            arguments.forEach {
                result.append(it.value.descriptor.helpMessage)
            }
        }
        result.append("Options: \n")
        options.forEach {
            result.append(it.value.descriptor.helpMessage)
        }
        return result.toString()
    }
}