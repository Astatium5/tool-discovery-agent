package test

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

data class GraphAgentRenameFixtureScenario(
    val id: String,
    val fileStem: String,
    val contents: String,
    val originalName: String = "originalName",
    val renamedName: String = "renamedName",
    val requiredBefore: List<String>,
    val requiredAfter: List<String>,
    val forbiddenAfter: List<String>,
) {
    val task: String
        get() = "rename the local variable $originalName to $renamedName in the current file"

    fun assertExpectedBefore(document: String) {
        requiredBefore.forEach { expected ->
            check(document.contains(expected)) {
                "Fixture $id did not open or contents were unexpected. Missing: $expected"
            }
        }
    }

    fun assertExpectedAfter(document: String, context: String) {
        requiredAfter.forEach { expected ->
            assertTrue(
                document.contains(expected),
                "$context should contain '$expected' for fixture $id, but document was:\n$document",
            )
        }
        forbiddenAfter.forEach { forbidden ->
            assertFalse(
                document.contains(forbidden),
                "$context should not contain '$forbidden' for fixture $id, but document was:\n$document",
            )
        }
    }

    companion object {
        val canonical =
            GraphAgentRenameFixtureScenario(
                id = "canonical",
                fileStem = "GraphAgentRenameFixture",
                contents =
                    """
                    package fixtures

                    class GraphAgentRenameFixture {
                        fun renderGreeting(): String {
                            val originalName = "Ada"
                            return "Hello, ${'$'}originalName"
                        }
                    }
                    """.trimIndent(),
                requiredBefore = listOf("val originalName = \"Ada\"", "return \"Hello, \$originalName\""),
                requiredAfter = listOf("val renamedName = \"Ada\"", "return \"Hello, \$renamedName\""),
                forbiddenAfter = listOf("val originalName = \"Ada\"", "return \"Hello, \$originalName\""),
            )

        val multipleLocalVariables =
            GraphAgentRenameFixtureScenario(
                id = "multiple-local-variables",
                fileStem = "GraphAgentRenameMultiLocalFixture",
                contents =
                    """
                    package fixtures

                    class GraphAgentRenameMultiLocalFixture {
                        fun renderGreeting(): String {
                            val title = "Dr."
                            val originalName = "Ada"
                            val punctuation = "!"
                            return "${'$'}title ${'$'}originalName${'$'}punctuation"
                        }
                    }
                    """.trimIndent(),
                requiredBefore =
                    listOf(
                        "val title = \"Dr.\"",
                        "val originalName = \"Ada\"",
                        "val punctuation = \"!\"",
                        "return \"\$title \$originalName\$punctuation\"",
                    ),
                requiredAfter =
                    listOf(
                        "val renamedName = \"Ada\"",
                        "return \"\$title \$renamedName\$punctuation\"",
                    ),
                forbiddenAfter =
                    listOf(
                        "val originalName = \"Ada\"",
                        "return \"\$title \$originalName\$punctuation\"",
                    ),
            )

        val similarNames =
            GraphAgentRenameFixtureScenario(
                id = "similar-names",
                fileStem = "GraphAgentRenameSimilarNamesFixture",
                contents =
                    """
                    package fixtures

                    class GraphAgentRenameSimilarNamesFixture {
                        fun renderGreeting(): String {
                            val originalName = "Ada"
                            val originalNameSuffix = " Lovelace"
                            return originalName + originalNameSuffix
                        }
                    }
                    """.trimIndent(),
                requiredBefore =
                    listOf(
                        "val originalName = \"Ada\"",
                        "val originalNameSuffix = \" Lovelace\"",
                        "return originalName + originalNameSuffix",
                    ),
                requiredAfter =
                    listOf(
                        "val renamedName = \"Ada\"",
                        "val originalNameSuffix = \" Lovelace\"",
                        "return renamedName + originalNameSuffix",
                    ),
                forbiddenAfter =
                    listOf(
                        "val originalName = \"Ada\"",
                        "return originalName + originalNameSuffix",
                    ),
            )

        val usageSiteVerification =
            GraphAgentRenameFixtureScenario(
                id = "usage-site-verification",
                fileStem = "GraphAgentRenameUsageSiteFixture",
                contents =
                    """
                    package fixtures

                    class GraphAgentRenameUsageSiteFixture {
                        fun renderGreeting(): String {
                            val originalName = "Ada"
                            val otherName = "Grace"
                            val auditLabel = "originalName should stay literal"
                            return auditLabel + ":" + originalName + ":" + otherName
                        }
                    }
                    """.trimIndent(),
                requiredBefore =
                    listOf(
                        "val originalName = \"Ada\"",
                        "val auditLabel = \"originalName should stay literal\"",
                        "return auditLabel + \":\" + originalName + \":\" + otherName",
                    ),
                requiredAfter =
                    listOf(
                        "val renamedName = \"Ada\"",
                        "val auditLabel = \"originalName should stay literal\"",
                        "return auditLabel + \":\" + renamedName + \":\" + otherName",
                    ),
                forbiddenAfter =
                    listOf(
                        "val originalName = \"Ada\"",
                        "return auditLabel + \":\" + originalName + \":\" + otherName",
                    ),
            )

        val noisyEditorState =
            GraphAgentRenameFixtureScenario(
                id = "noisy-editor-state",
                fileStem = "GraphAgentRenameNoisyFixture",
                contents =
                    """
                    package fixtures

                    class GraphAgentRenameNoisyFixture {
                        private val banner = "noise"

                        fun renderGreeting(enabled: Boolean): String {
                            val prefix = if (enabled) "[on]" else "[off]"
                            val originalName = "Ada"
                            val suffix = listOf("stable", banner.lowercase()).joinToString("-")
                            return "${'$'}prefix:${'$'}originalName:${'$'}suffix"
                        }

                        fun unusedNoise(): Int {
                            val retries = 3
                            return (1..retries).sum()
                        }
                    }
                    """.trimIndent(),
                requiredBefore =
                    listOf(
                        "private val banner = \"noise\"",
                        "val originalName = \"Ada\"",
                        "return \"\$prefix:\$originalName:\$suffix\"",
                    ),
                requiredAfter =
                    listOf(
                        "val renamedName = \"Ada\"",
                        "return \"\$prefix:\$renamedName:\$suffix\"",
                    ),
                forbiddenAfter =
                    listOf(
                        "val originalName = \"Ada\"",
                        "return \"\$prefix:\$originalName:\$suffix\"",
                    ),
            )

        val capturedByLocalFunction =
            GraphAgentRenameFixtureScenario(
                id = "captured-by-local-function",
                fileStem = "GraphAgentRenameCapturedLocalFunctionFixture",
                contents =
                    """
                    package fixtures

                    class GraphAgentRenameCapturedLocalFunctionFixture {
                        fun renderGreeting(): String {
                            val originalName = "Ada"

                            fun decorated(): String {
                                return originalName.uppercase()
                            }

                            return decorated() + ":" + originalName
                        }
                    }
                    """.trimIndent(),
                requiredBefore =
                    listOf(
                        "val originalName = \"Ada\"",
                        "return originalName.uppercase()",
                        "return decorated() + \":\" + originalName",
                    ),
                requiredAfter =
                    listOf(
                        "val renamedName = \"Ada\"",
                        "return renamedName.uppercase()",
                        "return decorated() + \":\" + renamedName",
                    ),
                forbiddenAfter =
                    listOf(
                        "val originalName = \"Ada\"",
                        "return originalName.uppercase()",
                        "return decorated() + \":\" + originalName",
                    ),
            )

        val phaseB: List<GraphAgentRenameFixtureScenario> =
            listOf(
                multipleLocalVariables,
                similarNames,
                usageSiteVerification,
                noisyEditorState,
            )

        val phaseD: List<GraphAgentRenameFixtureScenario> = phaseB + capturedByLocalFunction
    }
}
