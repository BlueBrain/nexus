module.exports = {
  products: [
    {
      name: "Nexus Forge",
      slug: "nexus-forge",
      features: [
        {
          title: "Storing",
          description:
            "Storing allows us to persist and manage Resources in the configured Store. Resources contain additional information to allow users recovering from errors.",
        },
        {
          title: "Querying",
          description:
            "It is possible to retrieve resources from the store by (1) its id, (2) specifying filters with the properties and a specific value and (3) using a simplified version of SPARQL query.",
        },
        {
          title: "Versioning",
          description:
            "The user can create versions of Resources, if the Store supports this feature.",
        },
        {
          title: "Resolving",
          description:
            "Resolvers are helpers to find commonly used resources that one may want to link to.",
        },
        {
          title: "Modelling",
          description:
            "To create Resources, the user can make use of Modeling functions. The user can explore predefined Types and the properties that describe them via Templates.",
        },
        {
          title: "Mapping",
          description:
            "Mappings are predefined configuration files that encode the logic on how to transform a specific data source into Resources that follow a template of a targeted Type.",
        },
      ],
      overviewText:
        "Blue Brain Nexus Forge is a domain-agnostic, generic and extensible Python framework enabling non-expert users to create and manage knowledge graphs by making it easy to:",
      overviewItems: [
        "Discover and reuse available knowledge resources such as ontologies and schemas to shape, constraint, link and add semantics to datasets.",
        "Build knowledge graphs from datasets generated from heterogeneous sources and formats. Defining, executing and sharing data mappers to transform data from a source format to a target one conformant to schemas and ontologies.",
        "Interface with various stores offering knowledge graph storage, management and scaling capabilities, for example Nexus Delta store or an In-memory store.",
        "Validate and register data and metadata.",
        "Search and download data and metadata from a knowledge graph.",
      ],
      featureText: "Forge offers a rich set of features to manage your data.",
      tagLine: "Building and Using Knowledge Graphs Made Easy",
      description:
        "Blue Brain Nexus Forge is a domain-agnostic, generic and extensible Python framework enabling non-expert users to create and manage knowledge graphs.",
    },
    {
      name: "Nexus Fusion",
      slug: "nexus-fusion",
      overviewText:
        "Nexus Fusion is the web application that runs on top of our Nexus Delta API. It supports all the functionalities of the backend services, from managing permissions to indexing resources. It is built with collaboration in mind.",
      featureText:
        "Nexus Fusion offers rich features for working with data. We are constantly enriching those.",
      tagLine: "Enabling Collaborative Data and Knowledge Discovery",
      description:
        "An extensible, open-source web interface that thrives on your data. With workspaces, plugins, and an admin interface available out-of-the-box, you can start working with your ingested data immediately.",
      features: [
        {
          title: "Studios",
          description:
            "Query, organize, visualize, and download the data and metadata stored in your Nexus instance, or federate across other instances.",
        },
        {
          title: "Extensible",
          description:
            "Extend Fusion with your own apps and plugins thanks to our modular architecture. Easily adapt the interface to your domain and needs",
        },
        {
          title: "Search",
          description:
            "Leveraging the powerful indexing of Nexus Delta, Fusion offers advanced search functionalities. Use our defaults or customize your own.",
        },
        {
          title: "Graph Exploration",
          description:
            "Explore the vicinity of the selected resource or write your own graph queries (SPARQL).",
        },
        {
          title: "Administration",
          description:
            "Manage your Nexus Delta instance from the visual interface instead of the command line.",
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
            "Store, manage, and describe all your data, using schemas and leveraging automatic validation.",
        },
        {
          title: "Scalable & Secure",
          description:
            "Use Docker, Kubernetes for deployments, and interface with your organization’s authentication provider.",
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
            "Use Server Sent Event (SSE) to write your own extensions. You can also add your own features and contribute to the community.",
        },
        {
          title: "Federation",
          description:
            "Our federation capabilities allow you to make data available across several deployments of Nexus.",
        },
      ],
      overviewText:
        "Delta can be used as a store for Nexus Forge and works seamlessly with Nexus Fusion. \n We offer several clients that consume the API of Delta. Nexus.js allows you to build data-driven web applications, Nexus Python SDK to integrate your data pipelines with the Knowledge Graph and the Nexus CLI allows you to manage your deployment from the command line.",
      featureText: "Delta offers a rich set of features to manage your data.",
      tagLine: "Managing the Data and Knowledge Graph Lifecycle",
      description:
        "A secure and scalable service that allows you to organize your data into a Knowledge Graph. Its API enables you to store your data, describe them with metadata, enforce format using schemas combined with automatic validation, capture provenance, and access revisions.",
      additionalInfo:
        "All data and metadata stored into your Knowledge Graph is versioned. All metadata is further indexed into views that offer several access modalities such as Graph and Document.",
    },
  ],
}
