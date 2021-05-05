# Add your own App

## SubApps

You can add your own SubApp by cloning the @link:[Nexus Fusion repo](https://github.com/BlueBrain/nexus-web){ open=new }
and adding your React app to the src.

You must build your application from source, in order to use your SubApp. Starting from the version 1.4, there is no way to add a
SubApp using the provided Dockerhub distribution.

### Development

SubApps are essentially a configuration hosting a routing list of React components. These React components will have
access to the entire app `Redux` store, the `Nexus Client`, as well as `ConnectedRouter` Providers for use in React
hooks and consumers.

Your SubApp should be a function that returns an object equating to this type signature:

```typescript
{
  title: string; // name of the app, used in the Side Menu and on the Home page
  namespace: string; // subpath, used in the URL
  routes: RouteProps[]; // a list of routes that will come after the subpath
  subAppType: string; // 'internal' or 'external'
  // optional configuration:
  icon?: string; // an icon used in the Side Menu
  requireLogin?: boolean; // specifies whether the subapp is visible for logged-in users only
  description?: string; // will show up as the subapp's description on the Home page
  version?: string; // will show up next to the subapp's title, for example, 'alpha' or 'beta'
  url?: string; // url for external subapps, to be used in the Side Menu
};
```

Here's an example that might add a Calendar with events

```typescript

const title = 'Calendar';
const namespace = 'calendar';
const icon = require('../../shared/images/calendar.svg');
const subAppType = 'internal';
const requireLogin = false;
const description =
  'Plan your activities';

routes: [
      {
        path: '/',
        exact: true,
        component: CalendarView,
      },
      {
        path: '/:event/:date',
        exact: true,
        component: EventView,
      },

```

Then you can add your SubApp to the @link:[`src/subapps/index.ts`](https://github.com/BlueBrain/nexus-web/blob/main/src/subapps/index.ts){ open=new }
barrell file, which hosts the SubApp set consumed by Nexus Fusion.

```typescript
const SubApps: Map<string, SubApp> = new Map();

SubApps.set("Admin", Admin);
SubApps.set("StudioLegacy", StudioLegacy);
SubApps.set("Calendar", MyCalendarSubapp);

export default SubApps;
```

### External SubApps

You can list other applications that are not a part of Nexus Fusion in the Side Menu for easy access by providing a path to `manifest.json` file as ENV variable `SUB_APPS_MANIFEST_PATH`. Manifest should contain a SubApp's title and an url like the following example:

```typescript
{
  "subapps": [
    {
      "url": "https://views-of-lake-Geneva.ch",
      "title": "Views"
    },
    {
      "url": "https://data-viz-project.com",
      "title": "Viz"
    }
  ]
}
```

### How to disable SubApps

To disable a SubApp you are not planning to use, include its title in the `manifest.json` file:

```typescript
{
  "disabled": [
    {
      "title": "StudioLegacy",
    }
  ]
}
```
