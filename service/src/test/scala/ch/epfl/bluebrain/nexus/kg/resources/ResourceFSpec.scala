package ch.epfl.bluebrain.nexus.kg.resources

import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant, ZoneId, ZoneOffset}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.{IriNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.{Iri, Node}
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util.EitherValues
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

//noinspection NameBooleanParameters
class ResourceFSpec
    extends TestKit(ActorSystem("ResourceFSpec"))
    with AnyWordSpecLike
    with Matchers
    with EitherValues
    with TestHelper {

  implicit private def toNode(instant: Instant): Node =
    Literal(instant.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT), xsd.dateTime)
  implicit private val appConfig                      = Settings(system).appConfig

  "A ResourceF" should {
    implicit val clock: Clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())

    val subject: Subject = User("dmontero", "someRealm")
    val userIri          = Iri.absolute(s"${appConfig.http.prefixIri.asUri}/realms/someRealm/users/dmontero").rightValue
    val anonIri          = Iri.absolute(s"${appConfig.http.prefixIri.asUri}/anonymous").rightValue

    val projectRef           = ProjectRef(genUUID)
    val id                   = Iri.absolute(s"http://example.com/${projectRef.id}").rightValue
    val resId                = Id(projectRef, id)
    val json                 = Json.obj("key" -> Json.fromString("value"))
    val schema               = Ref(shaclSchemaUri)
    val apiMappings          = Map[String, AbsoluteIri](
      "nxv"           -> nxv.base,
      "ex"            -> url"http://example.com/",
      "resource"      -> unconstrainedSchemaUri,
      "elasticsearch" -> nxv.defaultElasticSearchIndex.value,
      "graph"         -> nxv.defaultSparqlIndex.value
    )
    // format: off
    implicit val projectMeta = ResourceF(id, genUUID, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, Anonymous, Project("core", genUUID, "bbp", None, apiMappings, nxv.projects.value, genIri))
    // format: on

    "compute the metadata graph for a resource" in {
      val resource = KgResourceF
        .simpleF(resId, json, 2L, schema = schema, types = Set(nxv.Schema.value))
        .copy(createdBy = subject, updatedBy = Anonymous)
      resource.metadata() should contain allElementsOf Set[Triple](
        (IriNode(id), nxv.rev, 2L),
        (IriNode(id), nxv.deprecated, false),
        (IriNode(id), nxv.updatedAt, clock.instant()),
        (IriNode(id), nxv.createdAt, clock.instant()),
        (IriNode(id), nxv.createdBy, userIri.asString),
        (IriNode(id), nxv.updatedBy, anonIri.asString),
        (IriNode(id), nxv.self, s"http://127.0.0.1:8080/v1/schemas/bbp/core/ex:${projectRef.id}"),
        (IriNode(id), nxv.project, url"http://127.0.0.1:8080/v1/projects/bbp/core"),
        (IriNode(id), nxv.constrainedBy, IriNode(schema.iri))
      )
    }

    "compute the metadata graph for a resource when self, createdAt and updatedAt are iri" in {
      val resource = KgResourceF
        .simpleF(resId, json, 2L, schema = schema, types = Set(nxv.Schema.value))
        .copy(createdBy = subject, updatedBy = Anonymous)
      resource.metadata(MetadataOptions(true, false)) should contain allElementsOf Set[Triple](
        (IriNode(id), nxv.rev, 2L),
        (IriNode(id), nxv.deprecated, false),
        (IriNode(id), nxv.updatedAt, clock.instant()),
        (IriNode(id), nxv.createdAt, clock.instant()),
        (IriNode(id), nxv.createdBy, IriNode(userIri)),
        (IriNode(id), nxv.updatedBy, IriNode(anonIri)),
        (IriNode(id), nxv.self, url"http://127.0.0.1:8080/v1/schemas/bbp/core/ex:${projectRef.id}"),
        (IriNode(id), nxv.project, url"http://127.0.0.1:8080/v1/projects/bbp/core"),
        (IriNode(id), nxv.constrainedBy, IriNode(schema.iri))
      )
    }

    "remove the metadata from a resource" in {
      val jsonMeta = json deepMerge Json.obj("@id" -> Json.fromString(id.asString)) deepMerge Json.obj(
        nxv.rev.value.asString -> Json.fromLong(10L)
      )
      simpleV(
        resId,
        jsonMeta,
        2L,
        schema = schema,
        types = Set(nxv.Schema.value)
      ).value.graph.removeMetadata.triples shouldEqual Set
        .empty[Triple]
    }
  }

}
