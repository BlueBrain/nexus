package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.async.anyProject
import ch.epfl.bluebrain.nexus.kg.cache.ResolverCache
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution, Resolver, StaticResolution}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.{ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util.{
  ActorSystemFixture,
  EitherValues,
  IOEitherValues,
  IOOptionValues,
  Randomness,
  Resources => TestResources
}
import io.circe.Json
import org.mockito.ArgumentMatchers.any
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class SchemasSpec
    extends ActorSystemFixture("SchemasSpec", true)
    with IOEitherValues
    with IOOptionValues
    with AnyWordSpecLike
    with IdiomaticMockito
    with Matchers
    with OptionValues
    with EitherValues
    with Randomness
    with TestResources
    with TestHelper
    with Inspectors {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(7.seconds, 15.milliseconds)

  implicit private val appConfig             = Settings(system).appConfig
  implicit private val aggregateCfg          = appConfig.aggregate
  implicit private val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  implicit private val repo = Repo[IO].ioValue
  private val projectCache  = mock[ProjectCache[IO]]
  private val resolverCache = mock[ResolverCache[IO]]
  private val acls          = mock[Acls[IO]]
  resolverCache.get(any[ProjectRef]) shouldReturn IO.pure(List.empty[Resolver])
  acls.list(anyProject, ancestors = true, self = false)(Caller.anonymous) shouldReturn IO(AccessControlLists.empty)

  implicit private val resolution   =
    new ProjectResolution[IO](
      repo,
      resolverCache,
      projectCache,
      StaticResolution(AppConfig.iriResolution),
      acls,
      Caller.anonymous
    )
  implicit private val materializer = new Materializer(resolution, projectCache)
  private val schemas: Schemas[IO]  = Schemas[IO](repo)

  trait Base {
    implicit val subject: Subject = Anonymous
    val projectRef                = ProjectRef(genUUID)
    val base                      = Iri.absolute(s"http://example.com/base/").rightValue
    val id                        = Iri.absolute(s"http://example.com/$genUUID").rightValue
    lazy val resId                = Id(projectRef, id)
    val voc                       = Iri.absolute(s"http://example.com/voc/").rightValue
    // format: off
    implicit lazy val project = ResourceF(resId.value, projectRef.id, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Project("proj", genUUID, "org", None, Map.empty, base, voc))
    // format: on
    val schema                    = resolverSchema deepMerge Json.obj("@id" -> Json.fromString(id.asString))

  }

  "A Schemas bundle" when {

    "performing create operations" should {

      "create a new schema" in new Base {
        val resource = schemas.create(schema).value.accepted
        resource shouldEqual KgResourceF.simpleF(
          Id(projectRef, resource.id.value),
          schema,
          schema = shaclRef,
          types = Set(nxv.Schema.value)
        )
      }

      "create a new schema with the id passed on the call" in new Base {
        val resource = schemas.create(resId, schema).value.accepted
        resource shouldEqual KgResourceF.simpleF(
          Id(projectRef, resource.id.value),
          schema,
          schema = shaclRef,
          types = Set(nxv.Schema.value)
        )
      }

      "prevent to create a new schema with the id passed on the call not matching the @id on the payload" in new Base {
        val otherId = Id(projectRef, genIri)
        schemas.create(otherId, schema).value.rejected[IncorrectId] shouldEqual IncorrectId(otherId.ref)
      }
    }

    "performing update operations" should {

      "update a schema" in new Base {
        schemas.create(resId, schema).value.accepted shouldBe a[Resource]
        val update = schema deepMerge Json.obj(
          "@type" -> Json.arr(Json.fromString("nxv:Schema"), Json.fromString("nxv:Resolver"))
        )

        schemas.update(resId, 1L, update).value.accepted shouldEqual
          KgResourceF.simpleF(resId, update, 2L, schema = shaclRef, types = Set(nxv.Schema.value, nxv.Resolver.value))
      }

      "update a schema with circular dependency" in new Base {
        projectCache.get(projectRef.id) shouldReturn IO(Some(project))
        resolverCache.get(projectRef) shouldReturn IO.pure(List(InProjectResolver.default(projectRef)))

        val viewSchemaId     = resId.copy(value = genIri)
        val viewSchemaWithId = viewSchema deepMerge Json.obj("@id" -> Json.fromString(viewSchemaId.value.asString))

        schemas.create(viewSchemaId, viewSchemaWithId).value.accepted shouldBe a[Resource]

        val schemaWithImports = schema deepMerge Json.obj("imports" -> Json.fromString(viewSchemaId.value.asString))
        schemas.create(resId, schemaWithImports).value.accepted shouldBe a[Resource]

        val viewSchemaWithImports = viewSchemaWithId deepMerge Json.obj("imports" -> Json.fromString(id.asString))
        schemas.update(viewSchemaId, 1L, viewSchemaWithImports).value.accepted shouldBe a[Resource]

        val viewSchemaWithOwnImports = viewSchemaWithId deepMerge Json.obj(
          "imports" -> Json.fromString(viewSchemaId.value.asString)
        )
        schemas.update(viewSchemaId, 2L, viewSchemaWithOwnImports).value.accepted shouldBe a[Resource]
      }

      "prevent to update a schema that does not exists" in new Base {
        schemas.update(resId, 1L, schema).value.rejected[NotFound] shouldEqual NotFound(resId.ref)
      }
    }

    "performing deprecate operations" should {

      "deprecate a schema" in new Base {
        schemas.create(resId, schema).value.accepted shouldBe a[Resource]
        schemas.deprecate(resId, 1L).value.accepted shouldEqual
          KgResourceF.simpleF(resId, schema, 2L, schema = shaclRef, types = Set(nxv.Schema.value), deprecated = true)
      }

      "prevent deprecating a schema that's already deprecated" in new Base {
        schemas.create(resId, schema).value.accepted shouldBe a[Resource]
        schemas.deprecate(resId, 1L).value.accepted shouldBe a[Resource]
        schemas.deprecate(resId, 2L).value.rejected[ResourceIsDeprecated]
      }
    }
  }
}
