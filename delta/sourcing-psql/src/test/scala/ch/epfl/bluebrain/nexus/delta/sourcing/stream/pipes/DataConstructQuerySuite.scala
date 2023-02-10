package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.rdfs
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.DataConstructQuery.DataConstructQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{pipes, ReferenceRegistry}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant

class DataConstructQuerySuite extends BioSuite {

  private val base    = iri"http://localhost"
  private val instant = Instant.now()
  private val project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))
  private val state   = PullRequestActive(
    id = base / "id",
    project = project,
    rev = 1,
    createdAt = instant,
    createdBy = Anonymous,
    updatedAt = instant,
    updatedBy = Anonymous
  )
  private val graph   = PullRequestState.toGraphResource(state, base)

  private val registry = new ReferenceRegistry
  registry.register(DataConstructQuery)

  private val element =
    SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = Some(project),
      instant = instant,
      offset = Offset.at(1L),
      value = graph,
      rev = 1
    )

  def pipe(query: String): DataConstructQuery =
    registry
      .lookupA[pipes.DataConstructQuery.type](DataConstructQuery.ref)
      .rightValue
      .withJsonLdConfig(DataConstructQueryConfig(SparqlConstructQuery.unsafe(query)).toJsonLd)
      .rightValue

  test("Produce a correct graph") {
    val query         =
      s"""prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/>
         |prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>
         |
         |CONSTRUCT {
         |  ?resource rdfs:label ?upper_label .
         |}
         |WHERE {
         |  ?resource          a nxv:PullRequest ;
         |            rdfs:label          ?label .
         |  BIND(UCASE(?label) as ?upper_label) .
         |}""".stripMargin
    val expectedGraph = Graph.empty(base / "id").add(rdfs.label, "ACTIVE")
    val expected      = element.copy(value = element.value.copy(graph = expectedGraph))
    pipe(query).apply(element).assert(expected)
  }

}
