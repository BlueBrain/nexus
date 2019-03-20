[Documentation](#documentation) |
[Development](#development) |
[License](#license)

# [BlueBrainNexus.io](https://bluebrainnexus.io/)

A landing page to get an overview of the technology behind Nexus.

## Development

Anyone can edit this page without needing node or js configured, all you need is docker.
You want to change some text or add an image? just clone me and make your changes using the steps below!

### With Docker

Make sure you have already installed both [Docker Engine](https://docs.docker.com/install/) and [Docker Compose](https://docs.docker.com/compose/install/).

- Install: `make install` (installs necessary packages)
- Dev Mode: `make start` (starts serving the page in dev mode at [localhost:8000](http://localhost:8000))

You can also run other helpful commands

- Test: `make test`
- Lint: `make lint`

## Distribution

This section shows how to build output files and verify that everything's okay to be delivered by github pages.

Run the build command, which will:

- compile the assets.
- Then copy the dist folder to src/main/paradox.

Make sure to make a PR having saved the changes to src/main/paradox. After a merge, this folder will be set up on github pages.

Build: `make build`

you can open the resulting files in your browser to make sure everything works correctly with the `Open` command

Open: `make open`

## License

- [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
