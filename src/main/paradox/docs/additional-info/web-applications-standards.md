# Web applications development best practices

## Tools we standardize on

- **Language:** [Typescript](https://www.typescriptlang.org/)
Powerful type system and other features allow building higher quality applications, especially when they become complex. It is becoming the standard for entreprise-grade applications.

- **Framework**: [React](https://reactjs.org/) + [Redux](https://redux.js.org/)
The most widespread JS framework and state management solution.

- **Bundler:** [Parcel](https://parceljs.org/)
Manages everything needed to bundle regular web applications with zero configuration most of the time.

- **Unit tests:** [Jest](https://jestjs.io/)
Zero-configuration testing framework, best support for React.

- **Linting:** [ESLint Airbnb](https://www.npmjs.com/package/eslint-config-airbnb) with a few additions
One of the most widespread coding style to make it easy for multiple developers to collaborate on the same code base.

- **[NodeJS](https://nodejs.org/) version:** latest LTS
To allow anyone to install Blue Brain Nexus on their infrastructure, we stay compatible with NodeJS Long Term Support version that is the one usually available for production systems.

## Workflow

### Git and deployment

Our workflow is inspired by [Gitflow](https://nvie.com/posts/a-successful-git-branching-model/), simplified.

- Direct commit to the `master` branch is prohibited
- Features and fixes are developed in separate feature branches
- When ready for review, a Pull Request is opened for inclusion into the master branch.
- The Pull Request must be approved by at least 1 other developer.
- When merged into the master branch, it triggers a deployment to our `staging` environment.
- We tag the master branch with a [Semantic Versioning](https://semver.org/) version number when we consider it is high quality enough for release.

### Code documentation

Technical documentation is written along with the code in the form of [JSDoc](http://usejsdoc.org/) comments, and that we can extract with tools like [documentation.js](http://documentation.js.org/).

User documentation is written in the Blue Brain Nexus Platform documentation, including this very document. It is kept up-to-date with the latest features of the web applications and revised before any release.
