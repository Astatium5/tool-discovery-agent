package graph

data class GraphDecision(
    val action: String,
    val params: Map<String, String> = emptyMap(),
)

data class GraphDecisionResult(
    val reasoning: String,
    val decision: GraphDecision,
    val tokenCount: Int = 0,
)

interface GraphDecisionEngine {
    fun decide(
        task: String,
        page: PageState,
        history: List<GraphAgent.ActionRecord>,
        graph: KnowledgeGraph,
        currentPageId: String?,
    ): GraphDecisionResult
}
