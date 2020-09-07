@@@ index

- @ref:[Architecture](architecture.md)
- @ref:[API Reference](api/current/index.md)
- @ref:[Benchmarks](benchmarks.md)

@@@

# Nexus Delta

Blue Brain Nexus Delta is a low latency, scalable and secure system that realizes a range of functions to support
data management and knowledge graph lifecycles.

It is a central piece of the Nexus ecosystem of software components as it offers a set of foundational capabilities to
the other components (@ref:[Nexus Fusion] and @ref:[Nexus Forge]) around data and
metadata storage, management, validation and consumption in a secure setting.

Nexus Delta is developed [in the open] with a permissive licence ([Apache License, version 2.0]) using open standards
and interoperable semantic web technologies like [OpenID Connect], [OAuth 2.0], [RDF], [JSON-LD], [SHACL],
[Server-Sent Events].

It is quite versatile as it is able to handle very small to very large amounts of data on-premise or in the cloud and
can be used in a large spectrum of industries being completely domain agnostic.

Please refer to the @ref:[architecture] and @ref:[api reference] sections for more information about this component.


[Nexus Fusion]: ../fusion/index.md
[Nexus Forge]: ../forge.md
[architecture]: ./architecture.md
[api reference]: ./api/current/index.md
[in the open]: https://github.com/BlueBrain/nexus
[Apache License, version 2.0]: https://www.apache.org/licenses/LICENSE-2.0
[OpenID Connect]: https://openid.net/connect/
[OAuth 2.0]: https://tools.ietf.org/html/rfc6749
[RDF]: https://www.w3.org/RDF/
[JSON-LD]: https://www.w3.org/TR/json-ld11/
[SHACL]: https://www.w3.org/TR/shacl/
[Server-Sent Events]: https://www.w3.org/TR/eventsource/