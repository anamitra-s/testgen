# Makefile for TestGen local development (§9.9)

.PHONY: help dev-up dev-down test package eval-smoke run-local clean

help:
	@echo "Available targets:"
	@echo "  dev-up      - Start Postgres and run Flyway migrations via Docker Compose (§9.9)"
	@echo "  dev-down    - Tear down local Postgres and volumes"
	@echo "  test        - Run unit and integration tests across all modules (§9.9)"
	@echo "  package     - Build fat executable jar for testgen-orchestrator"
	@echo "  eval-smoke  - Run 10-case smoke eval suite against live API (§8.5, §9.9)"
	@echo "  run-local   - Run full pipeline locally against sample fixture (§9.9)"
	@echo "  clean       - Clean Maven build targets"

dev-up:
	docker compose up -d postgres
	docker compose run --rm flyway

dev-down:
	docker compose down -v

test:
	./mvnw test

package:
	./mvnw clean package -DskipTests

eval-smoke: package
	java -jar testgen-orchestrator/target/testgen-orchestrator.jar eval \
		--suite=smoke \
		--repeats=1 \
		--concurrency=4 \
		--gate=advisory \
		--report=eval-report.json

run-local: package
	java -jar testgen-orchestrator/target/testgen-orchestrator.jar run \
		--recording-dir=evals/cases/dashboard-date-filter-001 \
		--target-area=dashboard \
		--description="Dashboard date filter persists across reload" \
		--requester=local-dev \
		--idempotency-key=local-dev-001

clean:
	./mvnw clean
