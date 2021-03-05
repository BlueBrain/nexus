package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResolutionFetchRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.ResourceDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.{ResourceResolution, Resources}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class ResourceReferenceExchangeDummySpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata -> jsonContentOf("contexts/metadata.json"),
      contexts.shacl    -> jsonContentOf("contexts/shacl.json")
    )

  private val org             = Label.unsafe("myorg")
  private val am              = ApiMappings(Map("nxv" -> nxv.base))
  private val project         = ProjectGen.project("myorg", "myproject", base = nxv.base, mappings = am)
  private val schemaSource    = jsonContentOf("resources/schema.json")
  private val schema          = SchemaGen.schema(schemaorg.Person, project.ref, schemaSource.removeKeys(keywords.id))
  private val incorrectSchema = SchemaGen.schema(schemaorg.unitText, project.ref, schemaSource.removeKeys(keywords.id))

  private val (orgs, projs) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
    case (ref, _) if ref.iri == schema.id =>
      IO.pure(SchemaGen.resourceFor(schema))
    case (ref, pRef)                      =>
      IO.raiseError(ResolverResolutionRejection.ResourceNotFound(ref.iri, pRef))
  }

  private val resolverContextResolution: ResolverContextResolution =
    new ResolverContextResolution(
      res,
      (r, p, _) =>
        resources
          .fetch[ResolutionFetchRejection](r, p)
          .bimap(
            _ => ResourceResolutionReport(),
            _.value
          )
    )

  private val resolution: ResourceResolution[Schema] = ResourceResolutionGen.singleInProject(project.ref, fetchSchema)

  private lazy val resources: Resources = ResourcesDummy(orgs, projs, resolution, resolverContextResolution).accepted

  "A ResourceReferenceExchange" should {
    val id      = iri"http://localhost/${genString()}"
    val source  =
      json"""{
              "@context": {
                "@vocab": ${nxv.base}
              },
              "@type": "Custom",
              "name": "Alex",
              "number": 24,
              "bool": false
            }"""
    val tag     = TagLabel.unsafe("tag")
    val resRev1 = resources.create(id, project.ref, schema.id, source).accepted
    val resRev2 = resources.tag(id, project.ref, None, tag, 1L, 1L).accepted

    val exchange = new ResourceReferenceExchangeDummy(resources)

    "return a resource by id" in {
      val value = exchange.apply(project.ref, Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a resource by tag" in {
      val value = exchange.apply(project.ref, Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a resource by rev" in {
      val value = exchange.apply(project.ref, Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a resource by schema and id" in {
      val value = exchange.apply(project.ref, Latest(schema.id), Latest(id)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev2
    }

    "return a resource by schema and tag" in {
      val value = exchange.apply(project.ref, Latest(schema.id), Tag(id, tag)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return a resource by schema and rev" in {
      val value = exchange.apply(project.ref, Latest(schema.id), Revision(id, 1L)).accepted.value
      value.toSource shouldEqual source
      value.toResource shouldEqual resRev1
    }

    "return None for incorrect schema" in {
      forAll(List(Latest(id), Tag(id, tag), Revision(id, 1L))) { ref =>
        exchange.apply(project.ref, Latest(incorrectSchema.id), ref).accepted shouldEqual None
      }
    }

    "return None for incorrect id" in {
      exchange.apply(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.apply(project.ref, Latest(schema.id), Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      exchange.apply(project.ref, Latest(schema.id), Tag(id, TagLabel.unsafe("unknown"))).accepted shouldEqual None
    }

    "return the correct project and id" in {
      val event = ResourceDeprecated(id, project.ref, Set.empty, 1L, Instant.now(), subject)
      exchange.apply(event) shouldEqual Some((project.ref, id))
    }
  }
}
