# Plugins

Your plugin must export a default function with the following signature:

```typescript
export default ({ ref: HTMLElement, nexusClient: NexusClient, resource: Resource<T> }) => {
  return () => {
    // optional callback when your plugin is unmounted from the page
  };
};
```

Nexus Plugin uses [SystemJS](https://github.com/systemjs/systemjs).

You have to transpile and bundle your code using SystemJS as output:

- with [rollup](https://rollupjs.org/guide/en/#outputformat): use `system` as output format
- with [webpack](https://webpack.js.org/configuration/output/#outputlibrarytarget): use `system` as `outputTarget`

# Configuring Nexus to run your plugins

Once you have your javascript bundled into a single file, you can place it in the `./plugins` folder at the root of your Nexus Web instance.

Plugins should follow this folder naming convention:

```
.
│   README.md
│
└───plugins
│   └───my-nexus-plugin
│       │   index.js
│   └───yet-another-nexus-plugin
│       │   index.js
│   ...
```

Once restarted, your Nexus Web instance will read the available files, which will be visible to add in the Dashboard Edit Form via an autocomplete searchbox.

@ref:[Read about configuring a Studio Dashboard](studio.md)
