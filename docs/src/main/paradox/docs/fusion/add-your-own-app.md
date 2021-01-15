# Add your own App

## SubApps

You can add your own SubApp by cloning the @link:[Nexus Fusion repo](https://github.com/BlueBrain/nexus-web){ open=new } 
and adding your React app to the src.

You must build your application from source, in order to use your SubApp. As of version 1.4, there is no way to add a 
SubApp using the provided Dockerhub distribution.

### Development

SubApps are essentially a configuration hosting a routing list of React components. These React components will have 
access to the entire app `Redux` store, the `Nexus Client`, as well as `ConnectedRouter` Providers for use in React 
hooks and consumers.

Your SubApp should be a function that returns an object equating to this type signature:

```typescript
{
  title: string; // Name of the app, used in titles and the Nav Bar
  namespace: string; // subpath, used in the URL
  routes: RouteProps[]; // a list of routes that will come after the subpath
  icon?: string; // an optional Icon used in the Nav Bar
};
```

Here's an example that might add a Calendar with events

```typescript

const title = 'Calendar';
const namespace = 'studios';
const icon = require('../../shared/images/calendar.svg');

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
