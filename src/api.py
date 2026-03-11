"""
FastAPI server for the Tool Discovery Agent.

This module provides the HTTP/WebSocket server that the IntelliJ plugin
communicates with. Endpoints will be added as the project develops.
"""

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# Create FastAPI application
app = FastAPI(
    title="Tool Discovery Agent",
    description="GUI-Grounded Agent for IDE Tool Discovery",
    version="0.1.0",
)

# Configure CORS for local development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Allow all origins for development
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def root() -> dict[str, str]:
    """Root endpoint - basic health check."""
    return {"status": "ok", "message": "Tool Discovery Agent API"}


@app.get("/health")
async def health() -> dict[str, str]:
    """Health check endpoint."""
    return {"status": "healthy"}


def run_server(host: str = "0.0.0.0", port: int = 8080) -> None:
    """
    Run the FastAPI server.
    
    Args:
        host: Host to bind to.
        port: Port to listen on.
    """
    uvicorn.run(app, host=host, port=port)


if __name__ == "__main__":
    run_server()