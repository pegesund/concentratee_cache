# Setup Guide

This guide will help you set up the development environment for the Concentratee Cache service.

## Prerequisites

### 1. Java 21

The project requires Java 21 or later.

**Check your Java version:**
```bash
java -version
```

**Install Java 21 (Ubuntu/Debian):**
```bash
sudo apt update
sudo apt install openjdk-21-jdk
```

**Install Java 21 (macOS with Homebrew):**
```bash
brew install openjdk@21
```

**Install Java 21 (Windows):**
Download and install from [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/).

**Set JAVA_HOME (if needed):**
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### 2. PostgreSQL 16

The project requires PostgreSQL 16 or later with support for LISTEN/NOTIFY.

**Check your PostgreSQL version:**
```bash
psql --version
```

**Install PostgreSQL 16 (Ubuntu/Debian):**
```bash
sudo apt update
sudo apt install postgresql-16 postgresql-contrib-16
```

**Install PostgreSQL 16 (macOS with Homebrew):**
```bash
brew install postgresql@16
brew services start postgresql@16
```

**Install PostgreSQL 16 (Windows):**
Download and install from [PostgreSQL official site](https://www.postgresql.org/download/windows/).

### 3. Maven (Optional)

The project includes a Maven wrapper (`mvnw`), so Maven installation is optional.

**If you want to install Maven globally:**
```bash
# Ubuntu/Debian
sudo apt install maven

# macOS
brew install maven

# Check version
mvn -version
```

## Database Setup

### 1. Create Database User

```bash
sudo -u postgres psql
```

```sql
CREATE USER postgres WITH PASSWORD 'postgres';
ALTER USER postgres WITH SUPERUSER;
```

Or if the user already exists:
```sql
ALTER USER postgres WITH PASSWORD 'postgres';
```

### 2. Create Database

```sql
CREATE DATABASE concentratee_dev OWNER postgres;
\q
```

### 3. Initialize Database Schema

Run the SQL migration scripts in order:

```bash
cd database

# Run migrations
PGPASSWORD=postgres psql -h localhost -U postgres -d concentratee_dev -f 001_initial_schema.sql
PGPASSWORD=postgres psql -h localhost -U postgres -d concentratee_dev -f 002_add_triggers.sql

# Load sample data (optional)
PGPASSWORD=postgres psql -h localhost -U postgres -d concentratee_dev -f seed_data.sql
```

### 4. Verify Database Connection

```bash
PGPASSWORD=postgres psql -h localhost -U postgres -d concentratee_dev -c "\dt"
```

You should see tables: `students`, `profiles`, `sessions`, `rules`, `teachers`, `schools`.

## Project Setup

### 1. Clone the Repository

```bash
git clone https://github.com/pegesund/concentratee_cache.git
cd concentratee_cache
```

### 2. Configure Application (Optional)

The default configuration is in `src/main/resources/application.properties`:

```properties
# Database Configuration
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=postgres
quarkus.datasource.password=postgres
quarkus.datasource.reactive.url=postgresql://localhost:5432/concentratee_dev

# HTTP Configuration
quarkus.http.port=8082

# Logging
quarkus.log.level=INFO
quarkus.log.category."org.concentratee".level=DEBUG
```

**To override configuration**, create a `.env` file or set environment variables:

```bash
export QUARKUS_DATASOURCE_USERNAME=your_username
export QUARKUS_DATASOURCE_PASSWORD=your_password
export QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/your_database
export QUARKUS_HTTP_PORT=8082
```

### 3. Build the Project

```bash
./mvnw clean package
```

This will:
- Download all dependencies
- Compile the code
- Run tests
- Create a JAR file in `target/`

## Running the Application

### Development Mode (with Hot Reload)

```bash
./mvnw quarkus:dev
```

This starts the application with:
- Hot reload (code changes are automatically detected)
- Dev UI available at http://localhost:8082/q/dev
- API available at http://localhost:8082

**Press `d` in the terminal to open Dev UI in browser**

### Production Mode

```bash
# Build the production JAR
./mvnw clean package -DskipTests

# Run the JAR
java -jar target/quarkus-app/quarkus-run.jar
```

### Native Executable (Optional)

For faster startup and lower memory footprint:

```bash
# Build native executable (requires GraalVM)
./mvnw clean package -Dnative

# Run the native executable
./target/concentratee_cache-1.0.0-SNAPSHOT-runner
```

## Running Tests

### All Tests

```bash
./mvnw test
```

### Specific Test Class

```bash
./mvnw test -Dtest=CacheManagerTest
./mvnw test -Dtest=MainTest
```

### Test with Coverage

```bash
./mvnw test jacoco:report
```

Coverage report will be in `target/site/jacoco/index.html`.

## Verifying the Installation

### 1. Check Application Health

```bash
curl http://localhost:8082/health
```

Expected response:
```json
{
  "status": "ok",
  "database": "concentratee_dev",
  "timestamp": "2025-11-13T10:00:00"
}
```

### 2. Check Cache Stats

```bash
curl http://localhost:8082/cache/stats
```

Expected response:
```json
{
  "studentsById": 166,
  "profilesById": 10,
  "rulesById": 2,
  "sessionsById": 0,
  "sessionsByEmail": 0,
  "sessionsByProfile": 0
}
```

### 3. Test Active Profiles Endpoint

```bash
curl "http://localhost:8082/cache/profiles/active/test@example.com?expand=true"
```

## Common Issues

### Port Already in Use

If port 8082 is already in use:

```bash
# Find process using port
lsof -i :8082

# Kill the process
kill -9 <PID>

# Or change the port in application.properties
quarkus.http.port=8083
```

### Database Connection Issues

```bash
# Check PostgreSQL is running
sudo systemctl status postgresql

# Or on macOS
brew services list

# Restart PostgreSQL
sudo systemctl restart postgresql

# Or on macOS
brew services restart postgresql@16
```

### Permission Issues with Maven Wrapper

```bash
chmod +x mvnw
```

### Java Version Issues

```bash
# Check Java version
java -version

# Update alternatives (Ubuntu/Debian)
sudo update-alternatives --config java

# Set JAVA_HOME
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
```

### Database Triggers Not Created

If LISTEN/NOTIFY is not working:

```bash
# Reconnect to database and check triggers
PGPASSWORD=postgres psql -h localhost -U postgres -d concentratee_dev

# List triggers
SELECT tgname, tgrelid::regclass, tgenabled
FROM pg_trigger
WHERE tgname LIKE '%notify%';

# Re-run trigger creation script
\i database/002_add_triggers.sql
```

## IDE Setup

### IntelliJ IDEA

1. Open project: `File → Open` → Select project directory
2. IntelliJ will auto-detect Maven and import dependencies
3. Set Java SDK: `File → Project Structure → Project SDK` → Select Java 21
4. Enable annotation processing: `Settings → Build, Execution, Deployment → Compiler → Annotation Processors` → Enable
5. Install Quarkus plugin: `Settings → Plugins` → Search "Quarkus Tools"

### VS Code

1. Install extensions:
   - Extension Pack for Java
   - Quarkus
   - Maven for Java
2. Open project folder
3. VS Code will auto-detect Maven and import dependencies
4. Set Java version in `.vscode/settings.json`:
   ```json
   {
     "java.configuration.runtimes": [
       {
         "name": "JavaSE-21",
         "path": "/usr/lib/jvm/java-21-openjdk-amd64"
       }
     ]
   }
   ```

### Eclipse

1. Install Maven Integration for Eclipse (m2e)
2. Install Quarkus plugin from Eclipse Marketplace
3. Import project: `File → Import → Maven → Existing Maven Projects`
4. Select project directory
5. Set Java 21: `Project Properties → Java Compiler → Compiler compliance level` → 21

## Development Workflow

### 1. Start Development Server

```bash
./mvnw quarkus:dev
```

### 2. Make Code Changes

Edit files in `src/main/java` - changes are hot-reloaded automatically.

### 3. Run Tests

```bash
./mvnw test
```

### 4. Check Code Quality

```bash
# Compile and check for errors
./mvnw compile

# Run all tests
./mvnw test

# Package without tests (faster)
./mvnw package -DskipTests
```

### 5. Commit Changes

```bash
git add .
git commit -m "Your commit message"
git push
```

## Project Structure

```
concentratee_cache/
├── database/                 # SQL migrations and seed data
│   ├── 001_initial_schema.sql
│   ├── 002_add_triggers.sql
│   └── seed_data.sql
├── docs/                     # Documentation
│   ├── setup.md             # This file
│   ├── architecture.md
│   └── api-endpoints.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/concentratee/cache/
│   │   │       ├── Main.java           # REST API endpoints
│   │   │       └── CacheManager.java   # Cache logic
│   │   └── resources/
│   │       └── application.properties  # Configuration
│   └── test/
│       └── java/
│           └── org/concentratee/cache/
│               ├── CacheManagerTest.java  # Cache tests
│               └── MainTest.java          # API tests
├── pom.xml                   # Maven configuration
└── README.md                 # Project overview
```

## Next Steps

- Read [Architecture Documentation](./architecture.md) to understand the system design
- Read [API Endpoints](./api-endpoints.md) to learn about available endpoints
- Read [Database Integration](./database-integration.md) to understand LISTEN/NOTIFY
- Check [Data Structures](./data-structures.md) for cache implementation details

## Getting Help

- Check the [README.md](../README.md) for project overview
- Review documentation in the `docs/` folder
- Check Quarkus documentation: https://quarkus.io/guides/
- PostgreSQL LISTEN/NOTIFY: https://www.postgresql.org/docs/current/sql-notify.html
