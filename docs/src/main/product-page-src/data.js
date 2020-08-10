module.exports = {
  products: [
    {
      name: "Nexus Forge",
      slug: "nexus-forge",
      features: [
        {
          title: "Data Management",
          description:
            "Store and manage all your Data and describe them with Metadata. Take control of your metadata using schemas and benefit from automatic validation. All your data and metadata is versioned so you cannot lose anything.",
        },
        {
          title: "Scalable & Secure",
          description:
            "Nexus grows with your data. Leveraging modern deployment technologies (Docker, Kubernetes), you can add additional hardware to speed up Nexus or cope with more data. Security is a primary concern and all data access through the API is secure. Nexus interfaces with your organization’s authentication provider.",
        },
        {
          title: "Flexible Storage",
          description:
            "Configure Nexus to use the storage technology you already use, we support both POSIX systems and cloud storage.",
        },
        {
          title: "Powerful indexing",
          description:
            "We index all your data automatically to enable search in the Knowledge Graph. You can customize the indexing to suit your needs.",
        },
        {
          title: "Extensibility",
          description:
            "Use Server Sent Event (SSE) to write your own extensions that leverage your Knowledge Graph’s events. Because Delta is open source, you can also add your own features and contribute to the community.",
        },
        {
          title: "Federation",
          description:
            "Because sharing of your data is often essential, our federation capabilities allow you to make data available across several deployment of Nexus.",
        },
      ],
      overviewText:
        "Delta can be used as a store for Nexus Forge and works seamlessly with Nexus Fusion.We offer several clients that consume the API of Delta. Nexus.js allows you to build data-driven web applications, Nexus Python SDK to integrate your data pipelines with the Knowledge Graph and the Nexus CLI allows you to manage your deployment from the command line.All data and metadata stored into your Knowledge Graph is versioned. All metadata is further indexed into views that offer several access modalities such as Graph and Document.",
      featureText: "Delta offers a rich set of features to manage your data.",
      tagLine: "Managing Data and Knowledge Graph Lifecycles.",
      description:
        "A secure and scalable service that allows you to organize your data into a Knowledge Graph. Its API enables you to store your data, describe them with metadata, enforce format using schemas combined with automatic validation, capture provenance, and access revisions.",
    },
    {
      name: "Nexus Fusion",
      slug: "nexus-fusion",
      overviewText:
        "Nexus Fusion is the web application that runs on top of our Nexus Delta API. It supports all the functionalities of the backend services, from managing permissions to indexing resources. It is built with collaboration in mind.Our Fusion web app comes with default apps for working with data and managing the Nexus instance. Not enough? You can plug your own app and leverage our stack.In addition, resources available in the instance can be easily visualized through plugins. Do you need more plugins? No problem, just write your own and upload it to the plugin library.Nexus Fusion leverages our javascript library, Nexus.js.",
      featureText:
        "Nexus Fusion offers rich features for working with data. We are constantly enriching those.",
      tagLine: "Enabling Collaborative Data and Knowledge Discovery.",
      description:
        "An extensible, open-source web interface that thrives on your data. With workspaces, plugins, and an admin interface available out-of-the-box, you can start working with your ingested data immediately.",
      features: [
        {
          title: "Studios",
          description:
            "Query and organize the data stored in your Nexus instance, or federate across other instances, in dedicated workspaces. Visualize the resource’s details, download the data and metadata, and explore the surrounding graph.",
        },
        {
          title: "Extensible (plugins)",
          description:
            "We created Nexus Fusion to be extensible. Besides our default plugins to visualize resources, you can develop and deploy your own. In addition, Fusion offers a framework to showcase your web apps. We provide workspaces and an admin interface out-of-the-box.",
        },
        {
          title: "Search",
          description:
            "Searching has never been easier. Nexus Fusion leverages the powerful indexing features from Delta to power the search functionalities. You can use our default index or customize your own. Search your resources in different projects, by categories, or with free text.",
        },
        {
          title: "Graph Exploration",
          description:
            "Nexus uses metadata to create the Knowledge Graph of your entities. You can use Fusion to navigate the surrounding network around your resources. You can also use our editor to write our own graph (SPARQL) queries.",
        },
        {
          title: "Administration",
          description:
            "Manage your Nexus instance. You can create and manage organizations, projects, and visualize permissions. You can also upload and browse data, and edit metadata directly, create and monitor views. Finally, write and test ElasticSearch and SPARQL queries in our web editor.",
        },
      ],
    },
    {
      name: "Nexus Delta",
      slug: "nexus-delta",
      features: [
        {
          title: "Data Management",
          description:
            "Store and manage all your Data and describe them with Metadata. Take control of your metadata using schemas and benefit from automatic validation. All your data and metadata is versioned so you cannot lose anything.",
        },
        {
          title: "Scalable & Secure",
          description:
            "Nexus grows with your data. Leveraging modern deployment technologies (Docker, Kubernetes), you can add additional hardware to speed up Nexus or cope with more data. Security is a primary concern and all data access through the API is secure. Nexus interfaces with your organization’s authentication provider.",
        },
        {
          title: "Flexible Storage",
          description:
            "Configure Nexus to use the storage technology you already use, we support both POSIX systems and cloud storage.",
        },
        {
          title: "Powerful indexing",
          description:
            "We index all your data automatically to enable search in the Knowledge Graph. You can customize the indexing to suit your needs.",
        },
        {
          title: "Extensibility",
          description:
            "Use Server Sent Event (SSE) to write your own extensions that leverage your Knowledge Graph’s events. Because Delta is open source, you can also add your own features and contribute to the community.",
        },
        {
          title: "Federation",
          description:
            "Because sharing of your data is often essential, our federation capabilities allow you to make data available across several deployment of Nexus.",
        },
      ],
      overviewText:
        "Delta can be used as a store for Nexus Forge and works seamlessly with Nexus Fusion.We offer several clients that consume the API of Delta. Nexus.js allows you to build data-driven web applications, Nexus Python SDK to integrate your data pipelines with the Knowledge Graph and the Nexus CLI allows you to manage your deployment from the command line.All data and metadata stored into your Knowledge Graph is versioned. All metadata is further indexed into views that offer several access modalities such as Graph and Document.",
      featureText: "Delta offers a rich set of features to manage your data.",
      tagLine: "Managing Data and Knowledge Graph Lifecycles.",
      description:
        "A secure and scalable service that allows you to organize your data into a Knowledge Graph. Its API enables you to store your data, describe them with metadata, enforce format using schemas combined with automatic validation, capture provenance, and access revisions.",
    },
  ],
}
