# Tool Discovery Agent - Docker Image
# Python 3.11 with JetBrains Driver dependencies

FROM python:3.11-slim

LABEL maintainer="Tool Discovery Agent"
LABEL description="Agent for discovering IntelliJ IDEA tools through GUI perception"

# Install system dependencies for JetBrains Driver and UI automation
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jre-headless \
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libfreetype6 \
    libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

# Set Java home
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Create non-root user for security
RUN useradd --create-home --shell /bin/bash agent
USER agent
WORKDIR /home/agent/app

# Copy dependency files first for better caching
COPY --chown=agent:agent pyproject.toml ./

# Install Python dependencies
RUN pip install --no-cache-dir -e ".[dev]"

# Copy application code
COPY --chown=agent:agent src/ ./src/
COPY --chown=agent:agent configs/ ./configs/
COPY --chown=agent:agent tests/ ./tests/

# Create directories for output
RUN mkdir -p output/screenshots output/logs

# Expose API port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the API server
CMD ["python", "-m", "uvicorn", "src.api:app", "--host", "0.0.0.0", "--port", "8080"]