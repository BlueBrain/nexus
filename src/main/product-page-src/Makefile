.PHONY: build test clean

install:
	@echo "Installing project's dependencies... 🚀"
	@docker-compose run --rm nexus-product-page install

start:
	@echo "Launching dev mode 🍄"
	@docker-compose run --rm --service-port nexus-product-page start

build:
	@echo "Building project... 👷"
	@docker-compose run --rm nexus-product-page

open:
	@echo "Opening output html in your browser... 🏖"
	@open ../paradox/test/index.html

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