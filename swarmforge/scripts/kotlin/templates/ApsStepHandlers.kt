package acceptance

/**
 * THIS FILE IS YOURS.
 *
 * aps-kotlin writes it once and never rewrites it. Files under
 * acceptance/generated carry a do-not-edit banner and are replaced on every
 * generate; this file is the opposite. It is the only place where the Gherkin
 * vocabulary of the specification meets real application behaviour.
 *
 * Until you add handlers, every step fails as unsupported. That is the correct
 * starting state: an acceptance suite with no adapter must not report success.
 */
object ApsStepHandlers {

    /**
     * A fresh world for every scenario execution. Build test doubles, fakes and
     * fixtures here, not in the handlers, so that no two executions share state.
     */
    fun newWorld(): ApsWorld = ApsWorld()

    /**
     * Handler patterns capture the placeholder NAME, not the value:
     *
     *     Given the opening balance is <opening_balance>
     *     ^the opening balance is <([A-Za-z0-9_]+)>$
     *
     * The handler receives the captured name and looks the value up in the
     * current example row. One handler then serves a whole family of steps while
     * the .feature file keeps meaningful column names - which matters, because
     * gherkin-mutator mutates those values and nothing else.
     *
     * Keep patterns narrow. Two handlers matching one step is an error, and the
     * runtime reports it as one rather than picking a winner.
     */
    val handlers: List<ApsStepHandler> = listOf(
        // Delete this once the first real handler exists.
        ExampleRecordingHandler(),
    )
}

/**
 * Template handler. It shows the shape and nothing else.
 *
 * A real handler drives the application and asserts on what it produced:
 *
 *     override val pattern = Regex("^the monthly payment is <([A-Za-z0-9_]+)>$")
 *
 *     override fun handle(step: ApsStep, match: MatchResult, world: ApsWorld, examples: ApsExamples) {
 *         val expected = examples.double(match.groupValues[1])
 *         val actual = world.demand<Loan>("loan").monthlyPayment()
 *         if (abs(actual - expected) > 0.005) {
 *             throw ApsStepFailure("monthly payment: expected $expected but the loan produced $actual")
 *         }
 *     }
 */
private class ExampleRecordingHandler : ApsStepHandler {

    override val pattern = Regex("^the example value <([A-Za-z0-9_]+)> is recorded$")

    override fun handle(step: ApsStep, match: MatchResult, world: ApsWorld, examples: ApsExamples) {
        val name = match.groupValues[1]
        world[name] = examples.text(name)
    }
}
