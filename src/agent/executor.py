"""
Execution Agent Module.

This module implements the ExecutorAgent using LangChain and LangGraph
to execute refactoring plans by interacting with IDE GUI elements.
"""

from typing import Any, TypedDict, Annotated
from enum import Enum
from dataclasses import dataclass, field
from datetime import datetime
from pydantic import BaseModel
from langchain_core.messages import BaseMessage
from langgraph.graph import StateGraph, END
from langgraph.graph.state import CompiledStateGraph


class ExecutionStatus(str, Enum):
    """Status of an execution step or plan."""
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    SKIPPED = "skipped"
    ROLLED_BACK = "rolled_back"


class StepResult(BaseModel):
    """Result of executing a single step."""
    
    step_number: int
    status: ExecutionStatus
    message: str = ""
    timestamp: datetime = field(default_factory=datetime.now)
    screenshot_path: str | None = None
    error: str | None = None


class ExecutionState(TypedDict):
    """State for the executor agent workflow."""
    
    messages: Annotated[list[BaseMessage], "Messages in the conversation"]
    plan: Annotated[dict[str, Any] | None, "Plan to execute"]
    current_step: Annotated[int, "Current step index"]
    step_results: Annotated[list[dict[str, Any]], "Results of executed steps"]
    status: Annotated[str, "Overall execution status"]
    errors: Annotated[list[str], "Errors encountered"]


class ExecutorAgent:
    """
    LangGraph-based agent for executing refactoring plans.
    
    Uses LangGraph to orchestrate the execution process, taking a
    RefactoringPlan and executing each step through the ActionHandler.
    
    The agent uses a state machine pattern with the following nodes:
    - prepare: Set up execution environment
    - execute_step: Execute a single step
    - verify_step: Verify step completion
    - handle_error: Handle execution errors
    - rollback: Rollback on failure
    
    Example:
        >>> from src.agent import PlannerAgent
        >>> from src.interaction import ActionHandler
        >>> 
        >>> planner = PlannerAgent(discovered_tools)
        >>> plan = await planner.plan_refactoring("Extract method")
        >>> action_handler = ActionHandler()
        >>> executor = ExecutorAgent(action_handler)
        >>> result = await executor.execute(plan)
    """
    
    def __init__(
        self,
        action_handler: Any,
        config: dict[str, Any] | None = None,
        llm: Any = None,
    ) -> None:
        """
        Initialize the ExecutorAgent with LangGraph.
        
        Args:
            action_handler: Action handler instance for GUI interaction.
            config: Optional configuration dictionary.
            llm: Optional LangChain LLM for decision making.
        """
        self.action_handler = action_handler
        self.config = config or {}
        self.llm = llm
        
        self.graph = self._build_graph()
    
    def _build_graph(self) -> CompiledStateGraph:
        """
        Build the LangGraph state machine for execution.
        
        Returns:
            Compiled StateGraph for the execution workflow.
        """
        workflow = StateGraph(ExecutionState)
        
        # Add nodes
        workflow.add_node("prepare", self._prepare_node)
        workflow.add_node("execute_step", self._execute_step_node)
        workflow.add_node("verify_step", self._verify_step_node)
        workflow.add_node("handle_error", self._handle_error_node)
        workflow.add_node("rollback", self._rollback_node)
        
        # Set entry point
        workflow.set_entry_point("prepare")
        
        # Add edges
        workflow.add_edge("prepare", "execute_step")
        workflow.add_conditional_edges(
            "execute_step",
            self._check_step_result,
            {
                "success": "verify_step",
                "error": "handle_error",
                "complete": END,
            }
        )
        workflow.add_conditional_edges(
            "verify_step",
            self._check_verification,
            {
                "continue": "execute_step",
                "retry": "execute_step",
                "error": "handle_error",
            }
        )
        workflow.add_conditional_edges(
            "handle_error",
            self._should_rollback,
            {
                "rollback": "rollback",
                "continue": "execute_step",
                "abort": END,
            }
        )
        workflow.add_edge("rollback", END)
        
        return workflow.compile()
    
    async def _prepare_node(
        self,
        state: ExecutionState,
    ) -> dict[str, Any]:
        """
        Prepare for execution.
        
        Args:
            state: Current execution state.
        
        Returns:
            Updated state values.
        """
        return {
            "current_step": 0,
            "status": ExecutionStatus.RUNNING.value,
            "step_results": [],
            "errors": [],
        }
    
    async def _execute_step_node(
        self,
        state: ExecutionState,
    ) -> dict[str, Any]:
        """
        Execute the current step.
        
        Args:
            state: Current execution state.
        
        Returns:
            Updated state with step result.
        """
        raise NotImplementedError()
    
    async def _verify_step_node(
        self,
        state: ExecutionState,
    ) -> dict[str, Any]:
        """
        Verify step execution.
        
        Args:
            state: Current execution state.
        
        Returns:
            Updated state after verification.
        """
        raise NotImplementedError()
    
    async def _handle_error_node(
        self,
        state: ExecutionState,
    ) -> dict[str, Any]:
        """
        Handle execution errors.
        
        Args:
            state: Current execution state.
        
        Returns:
            Updated state with error handling.
        """
        raise NotImplementedError()
    
    async def _rollback_node(
        self,
        state: ExecutionState,
    ) -> dict[str, Any]:
        """
        Rollback executed steps.
        
        Args:
            state: Current execution state.
        
        Returns:
            Updated state after rollback.
        """
        raise NotImplementedError()
    
    def _check_step_result(
        self,
        state: ExecutionState,
    ) -> str:
        """
        Determine next action based on step result.
        
        Args:
            state: Current execution state.
        
        Returns:
            Next node to transition to.
        """
        raise NotImplementedError()
    
    def _check_verification(
        self,
        state: ExecutionState,
    ) -> str:
        """
        Determine next action after verification.
        
        Args:
            state: Current execution state.
        
        Returns:
            Next node to transition to.
        """
        raise NotImplementedError()
    
    def _should_rollback(
        self,
        state: ExecutionState,
    ) -> str:
        """
        Determine whether to rollback on error.
        
        Args:
            state: Current execution state.
        
        Returns:
            Next node to transition to.
        """
        raise NotImplementedError()
    
    async def execute(
        self,
        plan: Any,  # RefactoringPlan type
    ) -> list[StepResult]:
        """
        Execute a refactoring plan.
        
        Args:
            plan: RefactoringPlan to execute.
        
        Returns:
            List of StepResult objects for each step.
        """
        initial_state: ExecutionState = {
            "messages": [],
            "plan": plan.model_dump() if hasattr(plan, 'model_dump') else {},
            "current_step": 0,
            "step_results": [],
            "status": ExecutionStatus.PENDING.value,
            "errors": [],
        }
        
        final_state = await self.graph.ainvoke(initial_state)
        
        return [
            StepResult(**result) 
            for result in final_state.get("step_results", [])
        ]