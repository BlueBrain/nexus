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

Warning! If you do not have root access, you won't be able to use docker. Use `npm` instead.

> The instructions assume GNU Make, or you can use BSD make port `gmake` instead.

- Install: `make install` (installs necessary packages)
- Dev Mode: `make start` (starts serving the page in dev mode at [localhost:8000](http://localhost:8000))

You can also run other helpful commands

- Test: `make test`
- Lint: `make lint`

## Distribution

This section shows how to build output files and verify that everything's okay to be delivered by github pages.

Run the build command, which will:

- clean previous assets found in `src/main/paradox/public`.
- compile the assets and put them in `src/main/paradox/public`.
- Move the `index.html` to the root of `src/main/paradox/`
- open your browser to the resulting things

Make sure to make a PR having saved the changes to `src/main/paradox`. After a merge, this folder will be served with github pages.

Build: `make build`

This will cause your browser to open to view the resulting website, or you can call open manually with the `Open` command

Open: `make open`

## License

- [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
