package ch.epfl.bluebrain.nexus.kg.routes

import java.time.{Clock, Instant, ZoneId}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.{ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.{Compacted, Expanded}
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf._
import ch.epfl.bluebrain.nexus.delta.config.Settings
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceEncoderSpec
    extends TestKit(ActorSystem("ResourceEncoderSpec"))
    with AnyWordSpecLike
    with Matchers
    with TestHelper {
  implicit private val appConfig = Settings(system).appConfig

  implicit private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault)

  private val base       = Iri.absolute("http://example.com/").rightValue
  private val voc        = Iri.absolute("http://example.com/voc/").rightValue
  private val iri        = base + "id"
  private val subject    = Anonymous
  private val projectRef = ProjectRef(genUUID)
  private val projectId  = Id(projectRef, iri)
  private val resId      = Id(projectRef, base + "foobar")

  implicit private val project = ResourceF(
    projectId.value,
    projectRef.id,
    1L,
    deprecated = false,
    Set.empty,
    Instant.EPOCH,
    subject,
    Instant.EPOCH,
    subject,
    Project("proj", genUUID, "org", None, Map.empty, base, voc)
  )

  "ResourceEncoder" should {
    val json     = Json.obj("@id" -> Json.fromString("foobar"), "foo" -> Json.fromString("bar"))
    val context  = Json.obj("@base" -> Json.fromString(base.asString), "@vocab" -> Json.fromString(voc.asString))
    val resource = KgResourceF.simpleF(resId, json)

    "encode resource metadata" in {
      val expected =
        """
          |{
          |  "@id" : "http://example.com/foobar",
          |  "_constrainedBy" : "https://bluebrain.github.io/nexus/schemas/unconstrained.json",
          |  "_createdAt" : "1970-01-01T01:00:00Z",
          |  "_createdBy" : "http://127.0.0.1:8080/v1/anonymous",
          |  "_deprecated" : false,
          |  "_project" : "http://127.0.0.1:8080/v1/projects/org/proj",
          |  "_rev" : 1,
          |  "_self" : "http://127.0.0.1:8080/v1/resources/org/proj/_/foobar",
          |  "_incoming" : "http://127.0.0.1:8080/v1/resources/org/proj/_/foobar/incoming",
          |  "_outgoing" : "http://127.0.0.1:8080/v1/resources/org/proj/_/foobar/outgoing",
          |  "_updatedAt" : "1970-01-01T01:00:00Z",
          |  "_updatedBy" : "http://127.0.0.1:8080/v1/anonymous",
          |  "@context" : "https://bluebrain.github.io/nexus/contexts/resource.json"
          |}
        """.stripMargin
      ResourceEncoder.json(resource).rightValue shouldEqual parse(expected).rightValue
    }

    "encode resource value in compacted form" in {
      implicit val output: JsonLDOutputFormat = Compacted
      val triples                             = Set[Triple]((base + "foobar", voc + "foo", "bar"))
      val resourceV                           = resource.map(_ => Value(json, context, Graph(IriNode(base + "foobar"), triples)))
      val expected                            =
        """
          |{
          |  "@context" : "https://bluebrain.github.io/nexus/contexts/resource.json",
          |  "@id" : "foobar",
          |  "foo" : "bar"
          |}
        """.stripMargin
      ResourceEncoder.json(resourceV).rightValue shouldEqual parse(expected).rightValue
    }

    "encode resource value in expanded form" in {
      implicit val output: JsonLDOutputFormat = Expanded
      val triples                             = Set[Triple]((base + "foobar", voc + "foo", "bar"))
      val resourceV                           = resource.map(_ => Value(json, context, Graph(IriNode(base + "foobar"), triples)))
      val expected                            =
        """
          |{
          |  "@id" : "http://example.com/foobar",
          |  "http://example.com/voc/foo" : "bar"
          |}
        """.stripMargin
      ResourceEncoder.json(resourceV).rightValue shouldEqual parse(expected).rightValue
    }
  }
}
