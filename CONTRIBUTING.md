# Contributing to Kafka SQL Explorer

Thank you for your interest in Kafka SQL Explorer! We welcome contributions from the community.

## How to Contribute

### Reporting Bugs
- Use the GitHub Issue Tracker to report bugs.
- Provide a clear and descriptive title.
- Include steps to reproduce the issue, expected behavior, and actual behavior.
- Attach screenshots or logs if applicable.

### Suggesting Enhancements
- Open an issue to describe your proposed feature or improvement.
- Explain why this enhancement would be useful to other users.

### Pull Requests
1. Fork the repository.
2. Create a new branch for your feature or bugfix (`git checkout -b feature/my-new-feature`).
3. Make your changes.
4. Ensure your code follows the project's coding standards.
5. Write or update tests as necessary.
6. Commit your changes (`git commit -am 'Add some feature'`).
7. Push to the branch (`git push origin feature/my-new-feature`).
8. Open a Pull Request.

## Development Setup

### Prerequisites
- JDK 21+
- Docker and Docker Compose
- Maven 3.8+

### Running the Project Locally

**1. Using the Development Docker Compose (Hot Reloading)**
This is the recommended approach for full-stack development. It starts Kafka, a Vite dev server for the frontend, and a Spring Boot dev server for the backend.
```bash
docker-compose -f docker-compose-dev.yml up --build
```
- Frontend UI: `http://localhost:5173`
- Backend API: `http://localhost:8080`

**2. Standard Maven Run**
If you prefer running just the backend manually:
1. Start the Kafka cluster only:
   ```bash
   docker-compose up -d kafka
   ```
2. Run the application:
   ```bash
   ./mvnw spring-boot:run
   ```
3. Access the UI at `http://localhost:8080`.

**3. Building a Production Docker Image**
The project uses a multi-stage Dockerfile that builds both frontend and backend natively inside Docker:
```bash
docker build -t kafka-sql-explorer:latest .
```

### Running Tests
```bash
mvn test
```

## Coding Standards
- Follow standard Java coding conventions.
- Use meaningful variable and method names.
- Document complex logic with comments.
- Ensure all new features are covered by tests.

## License
By contributing to Kafka SQL Explorer, you agree that your contributions will be licensed under its GNU Affero General Public License, Version 3.0.
