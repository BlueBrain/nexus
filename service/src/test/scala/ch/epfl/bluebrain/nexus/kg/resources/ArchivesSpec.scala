package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}
import java.util.regex.Pattern.quote

import cats.data.OptionT
import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.archives.Archive.ResourceDescription
import ch.epfl.bluebrain.nexus.kg.archives.{Archive, ArchiveCache}
import ch.epfl.bluebrain.nexus.kg.cache.{Caches, ResolverCache, StorageCache, ViewCache}
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.service.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution, StaticResolution}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.{ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri}
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util.{
  ActorSystemFixture,
  CirceEq,
  EitherValues,
  IOEitherValues,
  IOOptionValues,
  Resources => TestResources
}
import io.circe.Json
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class ArchivesSpec
    extends ActorSystemFixture("ArchivesSpec", true)
    with IOEitherValues
    with IOOptionValues
    with AnyWordSpecLike
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Matchers
    with OptionValues
    with EitherValues
    with TestResources
    with TestHelper
    with Inspectors
    with BeforeAndAfter
    with CirceEq {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig             = Settings(system).appConfig
  implicit private val aggregateCfg          = appConfig.aggregate
  implicit private val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  implicit private val repo          = Repo[IO].ioValue
  implicit private val resolverCache = mock[ResolverCache[IO]]
  implicit private val projectCache  = mock[ProjectCache[IO]]
  private val resources              = mock[Resources[IO]]
  private val files                  = mock[Files[IO]]
  implicit private val archiveCache  = mock[ArchiveCache[IO]]

  private val resolution             =
    new ProjectResolution(
      repo,
      resolverCache,
      projectCache,
      StaticResolution[IO](iriResolution),
      mock[Acls[IO]],
      Caller.anonymous
    )
  private val cache                  = Caches(
    mock[OrganizationCache[IO]],
    projectCache,
    mock[ViewCache[IO]],
    resolverCache,
    mock[StorageCache[IO]],
    archiveCache
  )
  implicit private val materializer  = new Materializer[IO](resolution, projectCache)
  private val archives: Archives[IO] = Archives[IO](resources, files, cache)

  before {
    Mockito.reset(archiveCache)
  }

  trait Base {
    implicit val subject: Subject = Anonymous
    val projectRef                = ProjectRef(genUUID)
    val base                      = Iri.absolute(s"http://example.com/base/").rightValue
    val id                        = Iri.absolute(s"http://example.com/$genUUID").rightValue
    val resId                     = Id(projectRef, id)
    val voc                       = Iri.absolute(s"http://example.com/voc/").rightValue
    // format: off
    implicit val project = ResourceF(resId.value, projectRef.id, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Project("proj", genUUID, "org", None, Map.empty, base, voc))
    val project2 = ResourceF(resId.value, projectRef.id, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject,Project("myproject", genUUID, "myorg", None, Map.empty, base, voc))
    // format: on
    def updateId(json: Json)      =
      json deepMerge Json.obj("@id" -> Json.fromString(id.show))

    resolverCache.get(ProjectRef(project.uuid)) shouldReturn IO(
      List(InProjectResolver(ProjectRef(project.uuid), genIri, 1L, false, 1))
    )
    resolverCache.get(ProjectRef(project2.uuid)) shouldReturn IO(
      List(InProjectResolver(ProjectRef(project.uuid), genIri, 1L, false, 1))
    )
    projectCache.getBy(ProjectLabel(project.value.organizationLabel, project.value.label)) shouldReturn IO(
      Some(project)
    )
    projectCache.getBy(ProjectLabel(project2.value.organizationLabel, project2.value.label)) shouldReturn IO(
      Some(project2)
    )

    val archiveJson  = updateId(jsonContentOf("/archive/archive.json"))
    val archiveModel = Archive(
      resId,
      clock.instant,
      subject,
      Set[ResourceDescription](
        Archive.Resource(url"https://example.com/v1/gandalf", project, Some(1L), None, false, None),
        Archive.File(
          url"https://example.com/v1/epfl",
          project2,
          None,
          None,
          Some(Path.rootless("another/path").rightValue)
        )
      )
    )

    def resourceV(json: Json, rev: Long = 1L, types: Set[AbsoluteIri]): ResourceV = {
      val ctx   = archiveCtx.appendContextOf(resourceCtx)
      val graph = (json deepMerge Json.obj("@id" -> Json.fromString(id.asString)))
        .replaceContext(ctx)
        .toGraph(resId.value)
        .rightValue

      val resourceV =
        KgResourceF.simpleV(resId, Value(json, ctx.contextValue, graph), rev, schema = archiveRef, types = types)
      resourceV.copy(
        value = resourceV.value.copy(graph = Graph(resId.value, graph.triples ++ resourceV.metadata()))
      )
    }
  }

  "Archives bundle" when {

    "performing create operations" should {

      "prevent to create a archive that does not validate against the archive schema" in new Base {
        val invalid = updateId(jsonContentOf(s"/archive/archive-not-valid.json"))
        archives.create(invalid).value.rejected[InvalidResource]
      }

      "create an archive" in new Base {
        archiveCache.put(archiveModel) shouldReturn OptionT.some[IO](archiveModel)
        val expected =
          KgResourceF.simpleF(resId, archiveJson, schema = archiveRef, types = Set(nxv.Archive.value))
        val result   = archives.create(archiveJson).value.accepted
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "prevent creating an archive with the id passed on the call not matching the @id on the payload" in new Base {
        val json = archiveJson deepMerge Json.obj("@id" -> Json.fromString(genIri.asString))
        archives.create(resId, json).value.rejected[IncorrectId] shouldEqual IncorrectId(resId.ref)
      }

      "prevent creating an create with an id that already exists archive" in new Base {
        archiveCache.put(archiveModel) shouldReturn OptionT.none[IO, Archive]
        archives.create(resId, archiveJson).value.rejected[ResourceAlreadyExists]
      }
    }

    "performing read operations" should {

      "return an archive" in new Base {
        archiveCache.get(resId) shouldReturn OptionT.some[IO](archiveModel)
        val result       = archives.fetch(resId).value.accepted
        val expected     =
          resourceV(archiveJson, 1L, Set(nxv.Archive.value))
        result.value.ctx shouldEqual expected.value.ctx
        result shouldEqual expected.copy(value = result.value)
        val expectedJson = jsonContentOf(
          "/archive/archive-explicit.json",
          Map(quote("{id}") -> id.toString(), quote("{encodedId}") -> urlEncode(id.toString()))
        )
        val jsonResult   =
          result.value.graph.toJson(archiveCtx.appendContextOf(resourceCtx)).rightValue
        jsonResult.removeKeys("@context", "_updatedAt", "_createdAt") should equalIgnoreArrayOrder(expectedJson)
      }

      "return NotFound when the provided archive does not exists" in new Base {
        archiveCache.get(resId) shouldReturn OptionT.none[IO, Archive]
        archives.fetch(resId).value.rejected[NotFound] shouldEqual NotFound(resId.ref, schemaOpt = Some(archiveRef))
      }
    }
  }
}
