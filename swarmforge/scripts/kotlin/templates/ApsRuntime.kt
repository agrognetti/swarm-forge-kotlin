package acceptance

import java.io.File

/**
 * The acceptance runtime: the shared execution engine that every generated
 * acceptance entry point calls into.
 *
 * Generated entry points contain no step logic. Each one names a scenario and
 * an example row and hands control to this file, which loads the feature IR,
 * expands executions, prepends background steps and routes each step to a
 * project handler in [ApsStepHandlers].
 *
 * The IR is loaded at run time, not compiled in. That is what lets
 * gherkin-mutator run the same generated entry points against a mutated IR
 * without regenerating anything: it sets one system property per mutation.
 *
 * Read this before changing it. gherkin-mutator's verdicts only mean something
 * if a failing assertion actually fails the test, so every path that cannot
 * prove a step passed must throw.
 */

/** Per-execution state. A fresh instance is created for every scenario execution. */
class ApsWorld {
    private val values = LinkedHashMap<String, Any?>()

    operator fun set(key: String, value: Any?) {
        values[key] = value
    }

    operator fun get(key: String): Any? = values[key]

    fun contains(key: String): Boolean = values.containsKey(key)

    /** Reads a value the scenario should already have set. Absence is a failure, not null. */
    @Suppress("UNCHECKED_CAST")
    fun <T> demand(key: String): T =
        (values[key] ?: throw ApsStepFailure(
            "The scenario world holds no '$key'. A later step needs it, so an earlier " +
                "step should have set it. Keys present: " +
                if (values.isEmpty()) "(none)." else values.keys.joinToString(", ") + "."
        )) as T

    override fun toString(): String = values.toString()
}

/** Thrown for every failure a step can produce. An AssertionError so any test framework fails. */
class ApsStepFailure(message: String, cause: Throwable? = null) : AssertionError(message, cause)

data class ApsStep(val keyword: String, val text: String, val parameters: List<String>) {
    override fun toString(): String = "$keyword $text"
}

/**
 * The example row for the current scenario execution.
 *
 * Every value in the IR is a string, so converting to project types is a step
 * handler's job. These accessors do the conversion and, more importantly, fail
 * with the column name and the offending value when it cannot be done.
 */
class ApsExamples internal constructor(
    private val values: Map<String, String>,
    private val executionName: String,
) {
    val columns: Set<String> get() = values.keys

    fun has(name: String): Boolean = values.containsKey(name)

    fun text(name: String): String = values[name] ?: throw ApsStepFailure(
        "$executionName: the step asks for <$name>, which is not a column in this example row. " +
            "Columns present: " +
            if (values.isEmpty()) "(none - this scenario has no Examples table)."
            else values.keys.joinToString(", ") + "."
    )

    fun int(name: String): Int = convert(name, "an integer") { it.toIntOrNull() }

    fun long(name: String): Long = convert(name, "a long") { it.toLongOrNull() }

    fun double(name: String): Double = convert(name, "a number") { it.toDoubleOrNull() }

    fun boolean(name: String): Boolean = convert(name, "a boolean") {
        when (it.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }
    }

    /** Comma-separated cells, the only list form the IR's string-only values allow. */
    fun list(name: String): List<String> =
        text(name).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun <T> convert(name: String, expected: String, parse: (String) -> T?): T {
        val raw = text(name)
        return parse(raw.trim()) ?: throw ApsStepFailure(
            "$executionName: <$name> is '$raw', which is not $expected."
        )
    }

    override fun toString(): String =
        if (values.isEmpty()) "(no examples)" else values.entries.joinToString(", ") { "${it.key}=${it.value}" }
}

/** A project-specific step adapter. Patterns capture the placeholder NAME, not the value. */
interface ApsStepHandler {
    val pattern: Regex

    fun handle(step: ApsStep, match: MatchResult, world: ApsWorld, examples: ApsExamples)
}

object ApsRuntime {

    /** gherkin-mutator points this at a mutated IR, one mutation per test JVM. */
    const val IR_PROPERTY: String = "aps.feature.json"
    const val IR_ENVIRONMENT: String = "APS_FEATURE_JSON"

    private val placeholder = Regex("<([A-Za-z0-9_]+)>")
    private val lock = Any()

    private var cachedPath: String? = null
    private var cachedStamp: Long = -1
    private var cachedIr: Map<String, Any?>? = null

    /**
     * The IR path for this run: the mutator's override if present, else the path
     * baked in when the entry points were generated.
     */
    fun irPath(generatedDefault: String): String =
        System.getProperty(IR_PROPERTY)
            ?: System.getenv(IR_ENVIRONMENT)
            ?: generatedDefault

    /**
     * Runs one scenario execution.
     *
     * [scenarioName] is not decoration. The mutator is forbidden to change
     * scenario names, so a mismatch here means the generated entry points no
     * longer describe the IR being run, and reporting a pass would be a lie.
     */
    fun execute(irPath: String, scenarioIndex: Int, exampleIndex: Int, scenarioName: String) {
        val resolved = resolve(irPath)
        val ir = feature(resolved)
        val scenarios = ir["scenarios"].apsArray("'scenarios' in $resolved")

        if (scenarioIndex !in scenarios.indices) {
            throw ApsIrException(
                "$resolved holds ${scenarios.size} scenario(s) but the generated entry point asks " +
                    "for index $scenarioIndex ('$scenarioName'). The generated tests are stale: " +
                    "re-run aps-kotlin generate."
            )
        }

        val scenario = scenarios[scenarioIndex].apsObject("scenario $scenarioIndex in $resolved")
        val actualName = scenario["name"].apsText("the name of scenario $scenarioIndex in $resolved")
        if (actualName != scenarioName) {
            throw ApsIrException(
                "Scenario $scenarioIndex is named '$actualName' in $resolved, but the generated " +
                    "entry point was written for '$scenarioName'. The generated tests are stale: " +
                    "re-run aps-kotlin generate."
            )
        }

        val rows = scenario["examples"].apsArrayOrEmpty("'examples' of scenario $scenarioIndex")
        val row: Map<String, String> = when {
            rows.isEmpty() -> {
                if (exampleIndex != 0) {
                    throw ApsIrException(
                        "Scenario '$scenarioName' has no examples, so it runs once, but the " +
                            "generated entry point asks for example index $exampleIndex."
                    )
                }
                emptyMap()
            }
            exampleIndex !in rows.indices -> throw ApsIrException(
                "Scenario '$scenarioName' has ${rows.size} example row(s) but the generated entry " +
                    "point asks for index $exampleIndex. The generated tests are stale: re-run " +
                    "aps-kotlin generate."
            )
            else -> stringRow(rows[exampleIndex], "example row ${exampleIndex + 1} of '$scenarioName'")
        }

        val executionName = "$scenarioName/example_${exampleIndex + 1}"
        val examples = ApsExamples(row, executionName)
        val steps = ir["background"].apsArrayOrEmpty("'background' in $resolved").map { step(it) } +
            scenario["steps"].apsArray("'steps' of scenario $scenarioIndex").map { step(it) }

        if (steps.isEmpty()) {
            throw ApsIrException(
                "$executionName has no steps. A scenario that asserts nothing must not pass."
            )
        }

        val world = ApsStepHandlers.newWorld()
        for (current in steps) run(current, world, examples, executionName)
    }

    private fun run(step: ApsStep, world: ApsWorld, examples: ApsExamples, executionName: String) {
        for (name in step.parameters) {
            if (!examples.has(name)) {
                throw ApsStepFailure(
                    "$executionName: step '$step' uses <$name>, which is not a column in this " +
                        "example row. Columns present: " +
                        if (examples.columns.isEmpty()) "(none)." else examples.columns.joinToString(", ") + "."
                )
            }
        }

        val matched = ApsStepHandlers.handlers.mapNotNull { handler ->
            handler.pattern.matchEntire(step.text)?.let { handler to it }
        }

        when (matched.size) {
            0 -> throw ApsStepFailure(
                "$executionName: no step handler matches '${step.text}'.\n" +
                    "  Add one to ApsStepHandlers. An unhandled step fails on purpose: a " +
                    "specification nobody implemented must not report success."
            )
            1 -> {
                val (handler, match) = matched.single()
                try {
                    handler.handle(step, match, world, examples)
                } catch (failure: AssertionError) {
                    if (failure is ApsStepFailure) throw failure
                    throw ApsStepFailure("$executionName: step '$step' failed.\n  ${failure.message}", failure)
                } catch (error: Exception) {
                    throw ApsStepFailure(
                        "$executionName: step '$step' threw ${error.javaClass.simpleName}: ${error.message}",
                        error
                    )
                }
            }
            else -> throw ApsStepFailure(
                "$executionName: ${matched.size} step handlers match '${step.text}' " +
                    "(${matched.joinToString(", ") { it.first.javaClass.simpleName }}).\n" +
                    "  Ambiguous step text is an error, not a coin toss. Narrow the patterns."
            )
        }
    }

    private fun step(raw: Any?): ApsStep {
        val obj = raw.apsObject("a step")
        val text = obj["text"].apsText("a step's 'text'")
        // The parser spec makes 'text' authoritative and 'parameters' derived, so
        // the placeholders are read back out of the text rather than trusted.
        return ApsStep(
            keyword = obj["keyword"].apsText("a step's 'keyword'"),
            text = text,
            parameters = placeholder.findAll(text).map { it.groupValues[1] }.toList()
        )
    }

    private fun stringRow(raw: Any?, what: String): Map<String, String> {
        val obj = raw.apsObject(what)
        val out = LinkedHashMap<String, String>()
        for ((key, value) in obj) {
            out[key] = value as? String ?: throw ApsIrException(
                "$what: column '$key' holds ${value.apsTypeName()}. Every example value in the " +
                    "IR must be a string, even numbers and booleans."
            )
        }
        return out
    }

    /**
     * Gradle runs unit tests with the module directory as the working directory,
     * while the IR path recorded at generation time is relative to the repository
     * root. Walking up a few levels keeps both a plain `gradlew test` and a
     * mutator run working without absolute paths in generated files.
     */
    private fun resolve(irPath: String): String {
        val direct = File(irPath)
        if (direct.isAbsolute || direct.isFile) return direct.path
        var dir: File? = File("").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidate = File(dir, irPath)
            if (candidate.isFile) return candidate.path
            dir = dir.parentFile
            depth++
        }
        throw ApsIrException(
            "Cannot find the feature IR '$irPath'. Looked from ${File("").absolutePath} upwards.\n" +
                "  Produce it with: bb gherkin-parser <feature-file> $irPath\n" +
                "  Or point this run at another copy with -D$IR_PROPERTY=<path>."
        )
    }

    private fun feature(path: String): Map<String, Any?> = synchronized(lock) {
        val file = File(path)
        val stamp = file.lastModified() xor file.length()
        val hit = cachedIr
        if (hit != null && cachedPath == path && cachedStamp == stamp) return hit
        val parsed = ApsJson.parse(file.readText()).apsObject("the feature IR at $path")
        cachedPath = path
        cachedStamp = stamp
        cachedIr = parsed
        return parsed
    }
}
