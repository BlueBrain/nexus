[![View our technology page](https://img.shields.io/badge/technology-Nexus-03ABE9.svg)](https://bluebrainnexus.io/)
[![Join the chat at https://gitter.im/BlueBrain/nexus](https://badges.gitter.im/BlueBrain/nexus.svg)](https://gitter.im/BlueBrain/nexus?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

# Blue Brain Nexus - A knowledge graph for data-driven science

The Blue Brain Nexus is a provenance based, semantic enabled data management platform enabling the definition of an
arbitrary domain of application for which there is a need to create and manage entities as well as their relations
(e.g. provenance). For example, the domain of application managed by the Nexus platform deployed at Blue Brain is to
digitally reconstruct and simulate the brain.

At the heart of the Blue Brain Nexus platform lies the Knowledge Graph, at Blue Brain, it will allow scientists to:

1. Register and manage neuroscience relevant entity types through schemas that can reuse or extend community defined
schemas (e.g. schema.org, bioschema.org, W3C-PROV) and ontologies (e.g. brain parcellation schemes, cell types,
taxonomy).

2. Submit data to the platform and describe their provenance using the W3C PROV model. Provenance is about how data or
things are generated (e.g. protocols, methods used...), when (e.g. timeline) and by whom (e.g. people, software...).
Provenance supports the data reliability and quality assessment as well as enables workflow reproducibility. Platform
users can submit data either through web forms or programmatic interfaces.

3. Search, discover, reuse and derive high-quality neuroscience data generated within and outside the platform for the
purpose of driving their own scientific endeavours.
Data can be examined by species, contributing laboratory, methodology, brain region, and data type, thereby allowing
functionality not currently available elsewhere. The data are predominantly organized into atlases (e.g. Allen CCF,
Waxholm) and linked to the KnowledgeSpace – a collaborative community-based encyclopedia linking brain research concepts
to the latest data, models and literature.

It is to be noted that many other scientific fields (Astronomy, Agriculture, Bioinformatics, Pharmaceutical industry,
...) are in need of such a technology. Consequently, Blue Brain Nexus core technology is being developed to be
**agnostic of the domain** it might be applied to.

## More information

The Blue Brain Nexus [documentation] offers more information about the software, its [architecture], an [api reference]
and the current [roadmap].

Please head over to the [getting started] section for a description of various options on running Nexus and
introductory material to [Linked Data] and the [Shapes Constraint Language].

For more details, you can talk with the development team directly on [Gitter].

[documentation]: https://bluebrainnexus.io/docs/
[components]: https://bluebrainnexus.io/docs/index.html#nexus-components
[getting started]: https://bluebrainnexus.io/docs/getting-started/
[api reference]: https://bluebrainnexus.io/docs/api/
[architecture]: https://bluebrainnexus.io/docs/architecture/
[roadmap]: https://bluebrainnexus.io/docs/roadmap/

[Linked Data]: https://www.w3.org/standards/semanticweb/data
[Shapes Constraint Language]: https://www.w3.org/TR/shacl/

[Gitter]: https://gitter.im/BlueBrain/nexus

## Getting involved
 There are several channels provided to address different issues:
- **Feature request**: If there is a feature you would like to see in Blue Brain Nexus, please first consult the [list of open feature requests](https://github.com/BlueBrain/nexus/issues?q=is%3Aopen+is%3Aissue+label%3Afeature). In case there isn't already one, please [open a feature request](https://github.com/BlueBrain/nexus/issues/new?labels=feature) describing your feature with as much detail as possible.
- **Bug report**: If you have found a bug while using some of the Nexus services, please create an issue [here](https://github.com/BlueBrain/nexus/issues/new?labels=bug).