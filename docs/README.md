# Concentratee Cache System Documentation

High-performance in-memory cache for the Concentratee application, built with Java 21 and Quarkus.

## Overview

The cache system provides O(1) lookups for students, profiles, rules, and sessions with real-time synchronization from PostgreSQL using LISTEN/NOTIFY.

## Documentation Structure

- [Cache Architecture](architecture.md) - Overall system design and data flow
- [Data Structures](data-structures.md) - Hash maps and indexes
- [Database Integration](database-integration.md) - PostgreSQL tables and triggers
- [Cleanup Strategy](cleanup-strategy.md) - Stale data management
- [API Endpoints](api-endpoints.md) - REST API reference
- [Tracking System](tracking-system.md) - Appearance tracking, counting, and persistence

## Quick Start

```bash
./mvnw quarkus:dev
```

Access health check: http://localhost:8082/health

## Key Features

- **O(1) Lookups**: Hash-based indexes for all queries
- **Real-time Updates**: PostgreSQL LISTEN/NOTIFY integration
- **Automatic Cleanup**: Scheduled removal of stale data every 6 hours
- **Memory Efficient**: Smart filtering prevents stale data from affecting results
- **Thread Safe**: ConcurrentHashMap for all data structures
