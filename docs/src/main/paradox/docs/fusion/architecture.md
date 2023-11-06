# Architecture

## Technology Overview

Nexus Fusion is a server-side rendered single-page webapp powered by @link:[React.js](https://react.dev){ open=new }.
It is written in @link:[Typescript](https://www.typescriptlang.org/){ open=new }, and therefore must be transpiled
into native browser JavaScript, during a build step, before being served.

The build step produces a server artifact to run on a Node.js server, with client-side assets and Javascript.

We produce a docker image that is able to serve the compiled assets directly, which is available on
@link:[Dockerhub](https://hub.docker.com/repository/docker/bluebrain/nexus-web){ open=new }.

Although the application is served by a Node.js server, the client communicates directly to @ref:[Nexus Delta](../delta/index.md) using @ref:[Nexus.js](../utilities/index.md#nexus-js).

## Pages

Nexus Fusion has undergone a significant restructuring to transition from a SubApp-based architecture to a page-based structure. This change will prepare the application for a full migration to file-system based routing technologie and help seperating the application main features.

Pages serve a specific top level entity or functionality concerns of diverse users and activities, and provide varying access privileges to each. The main features that can be accessed from the home page are: @ref:[Organizations](../fusion/organizations.md), @ref:[Projects](../fusion/projects.md),  @ref:[Studios](../fusion/studios.md) and @ref:[My data](../fusion/my-data.md). 

The page @ref:[Project](../fusion/project.md), is responsible for handling all aspects of managing a single project.

The search page has been removed, but every element that was previously available on the search page now has a link on the home page. These links redirect users to the appropriate global search type page.

The pages in Nexus Fusion are part of the source code and reside in the `src/pages` folder.

While all the previous feature is still available in the current version of Nexus Fusion, we expect its functionality to evolve and change in the upcoming release.

## Plugins

Plugins are ways to render resources. You can find more about them @ref:[here](plugins.md). It is important to note
that the plugin repository is hosted separately from Nexus Fusion. Nexus Fusion will request a Plugin Manifest from
this repository at run-time, and fetch plugins to render during run time based on a config. Both the plugins, the
configuration, and the manifest should be hosted somewhere Nexus Fusion can request it.

## Customization

You can customize the Header of Nexus Fusion by setting up the following environment variables:

- `LOGO_IMG`: Url for an image to be used as application logo in the Header, for example, `https://www.epfl.ch/logo-img.png`
- `LOGO_LINK`: Url for the logo, for example, `https://www.epfl.ch`
- `ORGANIZATION_IMG`: Url for the organization page foreground image, for example, `https://www.epfl.ch/default-org-img.png`
- `PROJECTS_IMG`: Url for the projects page foreground image, for example,`https://www.epfl.ch/default-projects-img.png`
- `STUDIOS_IMG`: Url for the studios page foreground image, for example, `https://www.epfl.ch/default-studios-img.png`
- `LANDING_VIDEO`: Url for video in the the landing page, for example, `https://www.epfl.ch/landing-page-video.mp4`
- `LANDING_POSTER_IMG`: Url for the video's poster image in landing page (replace the video when loading, for example,`https://www.epfl.ch/landing-page-poster-img.png`
- `MAIN_COLOR`: Url for the organization page, for example "#062d68"
- If you use Nexus Forge, it is possible to include a `Forge templates` button by providing the url as `FORGE_LINK`, for example, `https://some-url.hi`

The full list of environment variables can be found @link[here](https://github.com/BlueBrain/nexus-web/blob/main/README.md#env-variables-list).
