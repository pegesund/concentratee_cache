#!/bin/bash

# Concentratee Cache Service - Restart Script
# This script stops the running Quarkus application and starts it again

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "🔄 Restarting Concentratee Cache Service..."
echo "📁 Working directory: $SCRIPT_DIR"

# Function to find and kill the Quarkus process
stop_service() {
    echo "🛑 Stopping existing service..."

    # Find Java process running the concentratee_cache jar
    PIDS=$(pgrep -f "java.*concentratee_cache.*\.jar" || true)

    if [ -n "$PIDS" ]; then
        echo "   Found process(es): $PIDS"
        for PID in $PIDS; do
            kill $PID 2>/dev/null || true
        done

        # Wait for processes to stop (max 30 seconds)
        echo -n "   Waiting for shutdown"
        for i in {1..30}; do
            REMAINING=$(pgrep -f "java.*concentratee_cache.*\.jar" || true)
            if [ -z "$REMAINING" ]; then
                echo ""
                echo "   ✅ Service stopped successfully"
                return 0
            fi
            echo -n "."
            sleep 1
        done

        # Force kill if still running
        REMAINING=$(pgrep -f "java.*concentratee_cache.*\.jar" || true)
        if [ -n "$REMAINING" ]; then
            echo ""
            echo "   ⚠️  Service didn't stop gracefully, forcing..."
            for PID in $REMAINING; do
                kill -9 $PID 2>/dev/null || true
            done
            sleep 2
        fi
    else
        echo "   ℹ️  No running service found"
    fi
}

# Function to start the service
start_service() {
    echo "🚀 Starting service in production mode..."

    # Build the application
    echo "   📦 Building application..."
    ./mvnw clean package -DskipTests

    if [ $? -ne 0 ]; then
        echo "   ❌ Build failed!"
        exit 1
    fi

    # Start the application in background
    echo "   🏃 Starting Quarkus application..."
    cd "$SCRIPT_DIR"
    nohup java -jar target/quarkus-app/quarkus-run.jar >> logs/application.log 2>&1 &

    # Get the actual Java process PID (not the shell)
    sleep 2
    NEW_PID=$(pgrep -f "java.*quarkus-run.jar" | tail -1)

    if [ -n "$NEW_PID" ]; then
        echo $NEW_PID > concentratee_cache.pid
        echo "   ✅ Service started with PID: $NEW_PID"
    else
        echo "   ⚠️  Started but couldn't determine PID"
    fi
}

# Function to verify service is running
verify_service() {
    echo "🔍 Verifying service..."
    sleep 3

    # Check if Java process is running
    RUNNING_PID=$(pgrep -f "java.*quarkus-run.jar" | tail -1)

    if [ -n "$RUNNING_PID" ]; then
        echo "   ✅ Service is running (PID: $RUNNING_PID)"

        # Try to reach the health endpoint
        echo "   🏥 Checking health endpoint..."
        for i in {1..10}; do
            if curl -s http://localhost:8080/health > /dev/null 2>&1; then
                echo "   ✅ Health check passed!"
                echo ""
                echo "🎉 Service restarted successfully!"
                echo "📊 View logs: tail -f logs/application.log"
                echo "📊 Cache stats: curl http://localhost:8080/cache/stats"
                return 0
            fi
            echo -n "."
            sleep 2
        done
        echo ""
        echo "   ⚠️  Service is running but health check failed"
        echo "   Check logs: tail -f logs/application.log"
    else
        echo "   ❌ Service process not found!"
        echo "   Check logs: tail -f logs/application.log"
        exit 1
    fi
}

# Main execution
echo ""

# Create logs directory if it doesn't exist
mkdir -p logs

# Stop existing service
stop_service

echo ""

# Start new service
start_service

echo ""

# Verify service is running
verify_service

echo ""
