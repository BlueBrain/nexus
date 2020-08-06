.PHONY: build test clean

install:
	@echo "Installing project's dependencies... 🚀"
	@docker-compose run --rm nexus-product-page install

start:
	@echo "Launching dev mode 🍄"
	@docker-compose run --rm --service-port nexus-product-page start

build:
	$(MAKE) clean
	@echo "Building project... 👷"
	@docker-compose run --rm nexus-product-page run build
	# $(MAKE) open

open:
	@echo "Opening output html in your browser... 🏖"
	@open ../paradox/index.html

test:
	@echo "Running tests... 🧪"
	@docker-compose run --rm nexus-product-page test

lint:
	@echo "Linting... ✨"
	@docker-compose run --rm nexus-product-page run lint

clean:
	@echo "Cleaning old output... 🧼"
	@docker-compose run --rm nexus-product-page run clean