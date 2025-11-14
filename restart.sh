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

    # Find Java process running quarkus
    PID=$(pgrep -f "quarkus.*concentratee_cache" || true)

    if [ -n "$PID" ]; then
        echo "   Found process: $PID"
        kill $PID || true

        # Wait for process to stop (max 30 seconds)
        for i in {1..30}; do
            if ! kill -0 $PID 2>/dev/null; then
                echo "   ✅ Service stopped successfully"
                return 0
            fi
            echo -n "."
            sleep 1
        done

        # Force kill if still running
        if kill -0 $PID 2>/dev/null; then
            echo ""
            echo "   ⚠️  Service didn't stop gracefully, forcing..."
            kill -9 $PID || true
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
    nohup java -jar target/quarkus-app/quarkus-run.jar > logs/application.log 2>&1 &

    # Save PID
    NEW_PID=$!
    echo $NEW_PID > concentratee_cache.pid
    echo "   ✅ Service started with PID: $NEW_PID"
}

# Function to verify service is running
verify_service() {
    echo "🔍 Verifying service..."
    sleep 3

    # Check if process is running
    if [ -f concentratee_cache.pid ]; then
        PID=$(cat concentratee_cache.pid)
        if kill -0 $PID 2>/dev/null; then
            echo "   ✅ Service is running (PID: $PID)"

            # Try to reach the health endpoint
            echo "   🏥 Checking health endpoint..."
            for i in {1..10}; do
                if curl -s http://localhost:8080/health > /dev/null 2>&1; then
                    echo "   ✅ Health check passed!"
                    echo ""
                    echo "🎉 Service restarted successfully!"
                    echo "📊 View logs: tail -f logs/application.log"
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
            exit 1
        fi
    else
        echo "   ⚠️  PID file not found"
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
