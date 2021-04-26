package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{schema, SparqlLink}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF, ResourceRef, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, TestHelpers}

import java.time.Instant
import scala.xml.{NodeSeq, XML}

trait BlazegraphViewRoutesFixtures extends TestHelpers with EitherValuable {

  val org           = Label.unsafe("org")
  val orgDeprecated = Label.unsafe("org-deprecated")
  val base          = nxv.base
  val mappings      = ApiMappings("example" -> iri"http://example.com/", "view" -> schema.iri)

  val project                  = ProjectGen.project("org", "proj", base = base, mappings = mappings)
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
  val projectRef               = project.ref

  val queryResultsJson = jsonContentOf("sparql/results/query-result.json")
  val queryResults     = queryResultsJson.as[SparqlResults].rightValue
  val xmlResults       = NodeSeq.fromSeq(XML.loadString(contentOf("sparql/results/query-results.xml")))

  val ntriplesResults     = NTriples(contentOf("sparql/results/ntriples-result.nt"), BNode.random)
  val xmlConstructResults = NodeSeq.fromSeq(XML.loadString(contentOf("sparql/results/query-results-construct.xml")))
  val jsonLdResults       = jsonContentOf("sparql/results/json-ld-result.json")

  val linksResults: SearchResults[SparqlLink] = UnscoredSearchResults(
    2,
    List(
      UnscoredResultEntry(
        SparqlResourceLink(
          ResourceF(
            iri"http://example.com/id1",
            ResourceUris.resource(
              projectRef,
              projectRef,
              iri"http://example.com/id1",
              ResourceRef(iri"http://example.com/someSchema")
            )(project.apiMappings, project.base),
            1L,
            Set(iri"http://example.com/type1", iri"http://example.com/type2"),
            false,
            Instant.EPOCH,
            Identity.Anonymous,
            Instant.EPOCH,
            Identity.Anonymous,
            ResourceRef(iri"http://example.com/someSchema"),
            List(iri"http://example.com/property1", iri"http://example.com/property2")
          )
        )
      ),
      UnscoredResultEntry(
        SparqlExternalLink(
          iri"http://example.com/external",
          List(iri"http://example.com/property3", iri"http://example.com/property4"),
          Set(iri"http://example.com/type3", iri"http://example.com/type4")
        )
      )
    )
  )
}
