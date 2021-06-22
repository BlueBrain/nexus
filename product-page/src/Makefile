.PHONY: build test clean

install:
	@echo "Installing project's dependencies... 🚀"
	@docker-compose run --rm nexus-product-page install

start:
	@echo "Launching dev mode 🍄"
	@docker-compose run --rm --service-port nexus-product-page start

build:
	@echo "Building project... 👷"
	@docker-compose run --rm nexus-product-page run build

test:
	@echo "Running tests... 🧪"
	@docker-compose run --rm nexus-product-page test

lint:
	@echo "Linting... ✨"
	@docker-compose run --rm nexus-product-page run lint
