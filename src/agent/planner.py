"""
Refactoring Planner Agent Module.

This module implements the PlannerAgent using LangChain and LangGraph
to analyze codebases and create refactoring plans using discovered IDE tools.
"""

from typing import Any, TypedDict, Annotated
from pydantic import BaseModel, Field
from langchain_core.messages import BaseMessage
from langgraph.graph import StateGraph, END
from langgraph.graph.state import CompiledStateGraph


class RefactoringStep(BaseModel):
    """Represents a single step in a refactoring plan."""
    
    step_number: int = Field(..., description="Step number in the sequence")
    tool_name: str = Field(..., description="Name of the tool to use")
    action: str = Field(..., description="Action to perform")
    target: str = Field(..., description="Target element or location")
    parameters: dict[str, Any] = Field(
        default_factory=dict,
        description="Additional parameters for the action"
    )
    expected_result: str = Field(
        default="",
        description="Expected result after completing this step"
    )


class RefactoringPlan(BaseModel):
    """Represents a complete refactoring plan."""
    
    name: str = Field(..., description="Name of the refactoring operation")
    description: str = Field(
        default="",
        description="Description of what the refactoring accomplishes"
    )
    steps: list[RefactoringStep] = Field(
        default_factory=list,
        description="Ordered list of steps to execute"
    )
    confidence: float = Field(
        default=0.0,
        ge=0.0,
        le=1.0,
        description="Confidence score for the plan"
    )
    prerequisites: list[str] = Field(
        default_factory=list,
        description="Prerequisites that must be met before execution"
    )
    risks: list[str] = Field(
        default_factory=list,
        description="Potential risks or issues with this plan"
    )


class PlannerState(TypedDict):
    """State for the planner agent workflow."""
    
    messages: Annotated[list[BaseMessage], "Messages in the conversation"]
    goal: Annotated[str, "Refactoring goal to achieve"]
    context: Annotated[dict[str, Any], "Context information"]
    discovered_tools: Annotated[list[dict[str, Any]], "Available tools"]
    plan: Annotated[dict[str, Any] | None, "Generated plan"]
    current_step: Annotated[int, "Current planning step"]
    validation_errors: Annotated[list[str], "Validation errors"]


class PlannerAgent:
    """
    LangGraph-based agent for planning refactoring operations.
    
    Uses LangGraph to orchestrate the planning process, taking a refactoring
    goal and creating a detailed plan using discovered IDE tools.
    
    The agent uses a state machine pattern with the following nodes:
    - analyze_goal: Parse and understand the refactoring goal
    - find_tools: Find relevant tools for the goal
    - create_plan: Generate step-by-step plan
    - validate: Validate plan feasibility
    - optimize: Optimize plan for efficiency
    
    Example:
        >>> from src.agent import DiscoveryAgent
        >>> 
        >>> discovery = DiscoveryAgent(ui_parser, action_handler)
        >>> tools = await discovery.discover_tools()
        >>> planner = PlannerAgent(tools)
        >>> plan = await planner.plan_refactoring("Extract method")
    """
    
    def __init__(
        self,
        discovered_tools: list[dict[str, Any]],
        config: dict[str, Any] | None = None,
        llm: Any = None,
    ) -> None:
        """
        Initialize the PlannerAgent with LangGraph.
        
        Args:
            discovered_tools: List of tools from DiscoveryAgent.
            config: Optional configuration dictionary.
            llm: Optional LangChain LLM for intelligent planning.
        """
        self.discovered_tools = discovered_tools
        self.config = config or {}
        self.llm = llm
        
        self.graph = self._build_graph()
    
    def _build_graph(self) -> CompiledStateGraph:
        """
        Build the LangGraph state machine for planning.
        
        Returns:
            Compiled StateGraph for the planning workflow.
        """
        workflow = StateGraph(PlannerState)
        
        # Add nodes
        workflow.add_node("analyze_goal", self._analyze_goal_node)
        workflow.add_node("find_tools", self._find_tools_node)
        workflow.add_node("create_plan", self._create_plan_node)
        workflow.add_node("validate", self._validate_node)
        workflow.add_node("optimize", self._optimize_node)
        
        # Set entry point
        workflow.set_entry_point("analyze_goal")
        
        # Add edges
        workflow.add_edge("analyze_goal", "find_tools")
        workflow.add_edge("find_tools", "create_plan")
        workflow.add_conditional_edges(
            "validate",
            self._should_revise_plan,
            {
                "revise": "create_plan",
                "optimize": "optimize",
            }
        )
        workflow.add_edge("create_plan", "validate")
        workflow.add_edge("optimize", END)
        
        return workflow.compile()
    
    async def _analyze_goal_node(
        self,
        state: PlannerState,
    ) -> dict[str, Any]:
        """
        Analyze the refactoring goal.
        
        Args:
            state: Current planner state.
        
        Returns:
            Updated state values.
        """
        raise NotImplementedError()
    
    async def _find_tools_node(
        self,
        state: PlannerState,
    ) -> dict[str, Any]:
        """
        Find relevant tools for the goal.
        
        Args:
            state: Current planner state.
        
        Returns:
            Updated state with relevant tools.
        """
        raise NotImplementedError()
    
    async def _create_plan_node(
        self,
        state: PlannerState,
    ) -> dict[str, Any]:
        """
        Create the refactoring plan.
        
        Args:
            state: Current planner state.
        
        Returns:
            Updated state with generated plan.
        """
        raise NotImplementedError()
    
    async def _validate_node(
        self,
        state: PlannerState,
    ) -> dict[str, Any]:
        """
        Validate the generated plan.
        
        Args:
            state: Current planner state.
        
        Returns:
            Updated state with validation results.
        """
        raise NotImplementedError()
    
    async def _optimize_node(
        self,
        state: PlannerState,
    ) -> dict[str, Any]:
        """
        Optimize the plan for efficiency.
        
        Args:
            state: Current planner state.
        
        Returns:
            Optimized plan.
        """
        raise NotImplementedError()
    
    def _should_revise_plan(
        self,
        state: PlannerState,
    ) -> str:
        """
        Determine whether to revise the plan.
        
        Args:
            state: Current planner state.
        
        Returns:
            Next node to transition to.
        """
        if state.get("validation_errors"):
            return "revise"
        return "optimize"
    
    async def plan_refactoring(
        self,
        goal: str,
        context: dict[str, Any] | None = None,
    ) -> RefactoringPlan:
        """
        Create a refactoring plan for the given goal.
        
        Args:
            goal: Description of the refactoring goal.
            context: Optional context information.
        
        Returns:
            RefactoringPlan with ordered steps to achieve the goal.
        """
        initial_state: PlannerState = {
            "messages": [],
            "goal": goal,
            "context": context or {},
            "discovered_tools": self.discovered_tools,
            "plan": None,
            "current_step": 0,
            "validation_errors": [],
        }
        
        final_state = await self.graph.ainvoke(initial_state)
        
        if final_state.get("plan"):
            return RefactoringPlan(**final_state["plan"])
        
        return RefactoringPlan(
            name="Empty Plan",
            description=f"Could not create plan for: {goal}",
        )
    
    def _is_tool_available(
        self,
        tool_name: str,
    ) -> bool:
        """
        Check if a tool is available in discovered tools.
        
        Args:
            tool_name: Name of the tool to check.
        
        Returns:
            True if tool is available, False otherwise.
        """
        for tool in self.discovered_tools:
            if tool.get("name") == tool_name:
                return True
        return False