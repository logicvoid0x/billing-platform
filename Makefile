# Local environment. `make up` takes a fresh machine to a healthy stack in one command.
COMPOSE := docker compose -f infra/docker-compose.yml

.PHONY: up up-multi up-stripe down clean topics topics-multi connect-plugin connectors health ps logs build

## Start everything (single broker), create topics, then check health.
up: connect-plugin
	$(COMPOSE) up -d --build --wait
	$(MAKE) topics
	$(MAKE) connectors
	$(MAKE) health

## Same with the 3-broker Kafka profile (replication 3, min.insync.replicas 2).
up-multi: connect-plugin
	$(COMPOSE) --profile multi-broker up -d --build --wait
	$(MAKE) topics-multi
	$(MAKE) connectors
	$(MAKE) health

## Add the Stripe CLI webhook forwarder; needs STRIPE_API_KEY (a test-mode key).
up-stripe:
	@test -n "$(STRIPE_API_KEY)" || { echo "set STRIPE_API_KEY to a Stripe test-mode key"; exit 1; }
	$(COMPOSE) --profile stripe up -d --wait stripe-cli

## Stop containers, keep volumes.
down:
	$(COMPOSE) --profile multi-broker --profile stripe down

## Stop containers and delete volumes (all local data).
clean:
	$(COMPOSE) --profile multi-broker --profile stripe down -v

topics:
	bash infra/kafka/topics.sh

topics-multi:
	bash infra/kafka/topics.sh --multi

## The usage.raw converter jar the Connect image copies (infra/connect/plugins, gitignored).
connect-plugin:
	bash ./gradlew -q :libs:usage-connect:stageConnectPlugin

## Register / update the Kafka Connect connectors (archive of usage.raw to Iceberg).
connectors:
	bash infra/connect/register-connectors.sh

health:
	bash infra/health-check.sh

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100

## Build the Gradle project (not part of the local environment).
build:
	bash ./gradlew build
