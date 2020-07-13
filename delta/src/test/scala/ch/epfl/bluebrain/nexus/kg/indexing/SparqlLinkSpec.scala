package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.{Clock, Instant, ZoneId}

import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv

class SparqlLinkSpec extends AnyWordSpecLike with Matchers with OptionValues {

  "A SparqlLink" should {

    val clock: Clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())

    val id        = url"http://example.com/id"
    val property  = url"http://example.com/friend"
    val property2 = url"http://example.com/friend2"
    val paths     = List(property, property2)

    "build SparqlExternalLink from SPARQL response" in {
      val bindings = Map(
        "s"     -> Binding("uri", id.asString),
        "paths" -> Binding("literal", s"${property.asString} ${property2.asString}")
      )
      SparqlExternalLink(bindings).value shouldEqual SparqlExternalLink(id, paths)
    }

    "build SparqlResourceLink from SPARQL response" in {
      val self     = url"http://127.0.0.1:8080/v1/resources/myorg/myproject/_/id"
      val project  = url"http://127.0.0.1:8080/v1/projects/myorg/myproject/"
      val author   = url"http://127.0.0.1:8080/v1/realms/myrealm/users/me"
      val bindings = Map(
        "s"              -> Binding("uri", id.asString),
        "paths"          -> Binding("literal", s"${property.asString} ${property2.asString}"),
        "_rev"           -> Binding("literal", "1", datatype = Some(xsd.long.asString)),
        "_self"          -> Binding("uri", self.asString),
        "_project"       -> Binding("uri", project.asString),
        "types"          -> Binding("literal", s"${nxv.Resolver.value.asString} ${nxv.Schema.value.asString}"),
        "_constrainedBy" -> Binding("uri", unconstrainedSchemaUri.asString),
        "_createdBy"     -> Binding("uri", author.asString),
        "_updatedBy"     -> Binding("uri", author.asString),
        "_createdAy"     -> Binding("uri", author.asString),
        "_createdAt"     -> Binding("literal", clock.instant().toString, datatype = Some(xsd.dateTime.asString)),
        "_updatedAt"     -> Binding("literal", clock.instant().toString, datatype = Some(xsd.dateTime.asString)),
        "_deprecated"    -> Binding("literal", "false", datatype = Some(xsd.boolean.asString))
      )
      SparqlResourceLink(bindings).value shouldEqual
        SparqlResourceLink(
          id,
          project,
          self,
          1L,
          Set[AbsoluteIri](nxv.Schema.value, nxv.Resolver.value),
          false,
          clock.instant(),
          clock.instant(),
          author,
          author,
          unconstrainedRef,
          paths
        )
    }
  }

}
