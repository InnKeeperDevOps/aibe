FROM gradle:8.14-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app

# Install Node.js (required by Claude CLI), git, and openssh-client for SSH-based cloning
RUN apt-get update && \
    apt-get install -y curl git openssh-client && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Claude CLI globally
RUN npm install -g @anthropic-ai/claude-code

COPY --from=build /app/build/libs/*.jar app.jar

# Create a non-root user for running Claude CLI subprocesses
RUN useradd -m -s /bin/bash claudeuser

# Create workspace directory for cloning repos
RUN mkdir -p /workspace && chown claudeuser:claudeuser /workspace

# Create data directory for the SQLite database (jdbc:sqlite:./data/sitemanager.db)
RUN mkdir -p /app/data

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
