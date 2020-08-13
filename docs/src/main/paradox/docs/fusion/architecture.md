# Architecture

## Technology Overview

Nexus Fusion is a Server-side rendered single-oage webapp powered by [React.js](https://reactjs.org/). It is written in [Typescript](https://www.typescriptlang.org/), and therefore must be transpiled into native browser JavaScript, during a build step, before being served.

The build step produces a server artifact to be ran on a Node.js server, and client-side assets and javascript.

We produce a docker image that is able to serve the compiled assets directly, which is available on [Dockerhub](https://hub.docker.com/repository/docker/bluebrain/nexus-web).

Although the application is served by a Node.js server, the client communicates directly to @ref:[Nexus Delta](../delta/index.md) using @ref:[Nexus.js](../utilities/utilities.md#nexus-js)

## Goals

Nexus Fusion aims to be an extensible web application that can serve generic needs of data visualization, governance and administration, while simultaneously serving domain specific needs of users. In order to facilitate this, we're adapting a plugin model where developers can adapt Nexus Fusion for their user's situation. This began in previous releases by introducing Plugins, and we'll continue develping this direction with Subapps.

Nexus Fusion aims to be a frame, upon which many extension are hung.

## SubApps

Nexus Fusion is divided into sections called SubApps. SubApps are separate workspaces that attempt to contain the concerns of disparate users and activities, and provide different access rights for each.

At the moment, we're packaging two SubApps called @ref:[Admin](admin.md) and @ref:[Studios](studio.md). These exist as part of the source code of Nexus Fusion, and live in the `src/subapps` folder.

You can learn how to develop your own SubApps to extend Fusion @ref:[here](add-your-own-app.md).

Expect the SubApp feature and its functionality will expand and change in the next releases of Nexus Fusion.

## Plugins

Plugins are ways to render resources. You can find more about them @ref:[here](plugins.md). It's important to note that the plugin repository is hosted separately from Nexus Fusion. Nexus Fusion will request a Plugin Manifest from this repository at run time, and fetch plugins to render during run time based on a config. Both the plugins, the configuration, and the manifest should be hosted somewhere Nexus Fusion can request it.
