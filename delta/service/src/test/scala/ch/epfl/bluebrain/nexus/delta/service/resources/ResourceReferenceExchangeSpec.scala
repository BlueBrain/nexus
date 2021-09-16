package ch.epfl.bluebrain.nexus.delta.service.resources

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ProjectSetup, ResourcesDummy}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class ResourceReferenceExchangeSpec
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
  implicit private val api: JsonLdApi   = JsonLdJavaApi.strict

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata        -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
      contexts.shacl           -> jsonContentOf("contexts/shacl.json").topContextValueOrEmpty,
      contexts.schemasMetadata -> jsonContentOf("contexts/schemas-metadata.json").topContextValueOrEmpty
    )

  private val org          = Label.unsafe("myorg")
  private val project      = ProjectGen.project("myorg", "myproject", base = nxv.base)
  private val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val schema       = SchemaGen.schema(schemaorg.Person, project.ref, schemaSource.removeKeys(keywords.id))

  private val (orgs, projs) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
    case (ref, _) if ref.iri == schema.id => UIO.some(SchemaGen.resourceFor(schema))
    case _                                => UIO.none
  }

  private val resolverContextResolution: ResolverContextResolution =
    new ResolverContextResolution(
      res,
      (r, p, _) => resources.fetch(r, p).bimap(_ => ResourceResolutionReport(), _.value)
    )

  private val resolution: ResourceResolution[Schema] = ResourceResolutionGen.singleInProject(project.ref, fetchSchema)

  private lazy val resources: Resources =
    ResourcesDummy(
      orgs,
      projs,
      resolution,
      (_, _) => IO.unit,
      resolverContextResolution
    ).accepted

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

    val exchange = Resources.referenceExchange(resources)

    "return a resource by id" in {
      val value = exchange.fetch(project.ref, Latest(id)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev2
    }

    "return a resource by tag" in {
      val value = exchange.fetch(project.ref, Tag(id, tag)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return a resource by rev" in {
      val value = exchange.fetch(project.ref, Revision(id, 1L)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return None for incorrect id" in {
      exchange.fetch(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.fetch(project.ref, Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      exchange.fetch(project.ref, Tag(id, TagLabel.unsafe("unknown"))).accepted shouldEqual None
    }
  }
}
