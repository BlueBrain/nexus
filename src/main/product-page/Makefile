.PHONY: build test clean

install:
	@echo "Installing project's dependencies... 🚀"
	@docker-compose run --rm nexus-product-page install

build:
	@echo "Building project... 👷"
	@docker-compose run --rm nexus-product-page

test:
	@echo "Running tests... 🧪"
	@docker-compose run --rm nexus-product-page test

lint:
	@echo "Linting... ✨"
	@docker-compose run --rm nexus-product-page run lint

clean:
	@echo "Cleaning... 🧹"
	@docker-compose run --rm nexus-product-page run clean
	@sudo rm -fr node_modules/