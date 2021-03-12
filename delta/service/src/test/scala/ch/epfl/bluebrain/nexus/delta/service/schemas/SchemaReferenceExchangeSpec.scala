package ch.epfl.bluebrain.nexus.delta.service.schemas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema => schemaorg}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent.SchemaDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ProjectSetup, SchemasDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.{SchemaImports, Schemas}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

class SchemaReferenceExchangeSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader
  implicit private val scheduler: Scheduler     = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json").accepted,
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json").accepted
    )

  private val org          = Label.unsafe("myorg")
  private val am           = ApiMappings(Map("nxv" -> nxv.base))
  private val project      = ProjectGen.project("myorg", "myproject", base = nxv.base, mappings = am)
  private val schemaSource = jsonContentOf("resources/schema.json")
  private val schema       = SchemaGen.schema(schemaorg.Person, project.ref, schemaSource.removeKeys(keywords.id))

  private val (orgs, projs) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  private val schemaImports: SchemaImports = new SchemaImports(
    (_, _, _) => IO.raiseError(ResourceResolutionReport()),
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  private val resolverContextResolution: ResolverContextResolution =
    new ResolverContextResolution(res, (_, _, _) => IO.raiseError(ResourceResolutionReport()))

  private val schemas: Schemas = SchemasDummy(orgs, projs, schemaImports, resolverContextResolution).accepted

  "A SchemaReferenceExchange" should {
    val tag     = TagLabel.unsafe("tag")
    val resRev1 = schemas.create(schema.id, project.ref, schema.source).accepted
    val resRev2 = schemas.tag(schema.id, project.ref, tag, 1L, 1L).accepted

    val exchange = new SchemaReferenceExchange(schemas)

    "return a schema by id" in {
      val value = exchange.apply(project.ref, Latest(schema.id)).accepted.value
      value.toSource shouldEqual schema.source
      value.toResource shouldEqual resRev2
    }

    "return a schema by tag" in {
      val value = exchange.apply(project.ref, Tag(schema.id, tag)).accepted.value
      value.toSource shouldEqual schema.source
      value.toResource shouldEqual resRev1
    }

    "return a schema by rev" in {
      val value = exchange.apply(project.ref, Revision(schema.id, 1L)).accepted.value
      value.toSource shouldEqual schema.source
      value.toResource shouldEqual resRev1
    }

    "return a resource by schema and id" in {
      val value = exchange.apply(project.ref, Latest(Vocabulary.schemas.shacl), Latest(schema.id)).accepted.value
      value.toSource shouldEqual schema.source
      value.toResource shouldEqual resRev2
    }

    "return a resource by schema and tag" in {
      val value = exchange.apply(project.ref, Latest(Vocabulary.schemas.shacl), Tag(schema.id, tag)).accepted.value
      value.toSource shouldEqual schema.source
      value.toResource shouldEqual resRev1
    }

    "return a resource by schema and rev" in {
      val value = exchange.apply(project.ref, Latest(Vocabulary.schemas.shacl), Revision(schema.id, 1L)).accepted.value
      value.toSource shouldEqual schema.source
      value.toResource shouldEqual resRev1
    }

    "return None for incorrect schema" in {
      forAll(List(Latest(schema.id), Tag(schema.id, tag), Revision(schema.id, 1L))) { ref =>
        exchange.apply(project.ref, Latest(iri"http://locahost/${genString()}"), ref).accepted shouldEqual None
      }
    }

    "return None for incorrect id" in {
      exchange.apply(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.apply(project.ref, Revision(schema.id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      exchange.apply(project.ref, Tag(schema.id, TagLabel.unsafe("unknown"))).accepted shouldEqual None
    }

    "return the correct project and id" in {
      val event = SchemaDeprecated(schema.id, project.ref, 1L, Instant.now(), subject)
      exchange.apply(event) shouldEqual Some((project.ref, schema.id))
    }
  }
}
