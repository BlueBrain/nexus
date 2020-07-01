package ch.epfl.bluebrain.nexus.kg.resources

import java.nio.file.Paths
import java.time.{Clock, Instant, ZoneId}
import java.util.regex.Pattern.quote

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.commons.test
import ch.epfl.bluebrain.nexus.commons.test.io.{IOEitherValues, IOOptionValues}
import ch.epfl.bluebrain.nexus.commons.test.{ActorSystemFixture, CirceEq, EitherValues}
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, ResolverCache, StorageCache}
import ch.epfl.bluebrain.nexus.kg.config.KgConfig.iriResolution
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution, Resolver, StaticResolution}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.Verify
import ch.epfl.bluebrain.nexus.kg.storage.Storage._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json
import javax.crypto.SecretKey
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class StoragesSpec
    extends ActorSystemFixture("StoragesSpec", true)
    with AnyWordSpecLike
    with IOEitherValues
    with IOOptionValues
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Matchers
    with OptionValues
    with EitherValues
    with test.Resources
    with TestHelper
    with Inspectors
    with BeforeAndAfter
    with CirceEq {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit private val appConfig             = Settings(system).serviceConfig
  implicit private val aggregateCfg          = appConfig.kg.aggregate
  implicit private val secretKey: SecretKey  = appConfig.kg.storage.derivedKey
  implicit private val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  implicit private val repo          = Repo[IO].ioValue
  implicit private val resolverCache = mock[ResolverCache[IO]]
  implicit private val storageCache  = mock[StorageCache[IO]]
  private val projectCache           = mock[ProjectCache[IO]]

  private val resolution             =
    new ProjectResolution(
      repo,
      resolverCache,
      projectCache,
      StaticResolution[IO](iriResolution),
      mock[Acls[IO]],
      Caller.anonymous
    )
  implicit private val materializer  = new Materializer[IO](resolution, projectCache)
  private val storages: Storages[IO] = Storages[IO]
  private val readPerms              = Permission.unsafe("resources/read")
  private val writePerms             = Permission.unsafe("files/write")

  private val passVerify = new VerifyStorage[IO] {
    override def apply: IO[Either[String, Unit]] = IO.pure(Right(()))
  }

  before {
    Mockito.reset(resolverCache, storageCache)
  }

  trait Base {
    implicit val subject: Subject       = Anonymous
    val projectRef                      = ProjectRef(genUUID)
    val base                            = Iri.absolute(s"http://example.com/base/").rightValue
    val id                              = Iri.absolute(s"http://example.com/$genUUID").rightValue
    val resId                           = Id(projectRef, id)
    val voc                             = Iri.absolute(s"http://example.com/voc/").rightValue
    // format: off
    implicit val project = Project(resId.value, "proj", "org", None, base, voc, Map.empty, projectRef.id, genUUID, 1L, deprecated = false, Instant.EPOCH, subject.id, Instant.EPOCH, subject.id)
    // format: on
    def updateId(json: Json)            =
      json deepMerge Json.obj("@id" -> Json.fromString(id.show))

    val diskStorage                     = updateId(jsonContentOf("/storage/disk.json"))
    val diskStorageSource               = updateId(jsonContentOf("/storage/disk-source.json"))
    val remoteStorageSource             = updateId(jsonContentOf("/storage/remote-source.json"))
    // format: off
    val remoteDiskStorage = updateId(jsonContentOf("/storage/remoteDisk.json", Map(quote("{folder}") -> "folder", quote("{cred}") -> "cred", quote("{read}") -> "resources/read", quote("{write}") -> "files/write")))
    val diskStorageModel = DiskStorage(projectRef, id, 1L, deprecated = false, default = false, "SHA-256", Paths.get("/tmp"), readPerms, writePerms, 10737418240L)
    val remoteDiskStorageModel = RemoteDiskStorage(projectRef, id, 1L, deprecated = false, default = false, "SHA-256", "http://example.com/some", Some("cred"), "folder", readPerms, writePerms, 10737418240L)
    val remoteDiskStorageModelEncrypted = RemoteDiskStorage(projectRef, id, 1L, deprecated = false, default = false, "SHA-256", "http://example.com/some", Some("cred".encrypt), "folder", readPerms, writePerms, 10737418240L)
    // format: on
    resolverCache.get(projectRef) shouldReturn IO(List.empty[Resolver])

    implicit val verify = new Verify[IO] {
      override def apply(storage: Storage): VerifyStorage[IO] =
        if (
          storage == diskStorageModel || storage == s3StorageModel || storage == remoteDiskStorageModel || storage == diskStorageModel
            .copy(default = true, maxFileSize = 200L)
        ) passVerify
        else throw new RuntimeException
    }

    val typesDisk               = Set(nxv.Storage.value, nxv.DiskStorage.value)
    val s3Storage               = updateId(jsonContentOf("/storage/s3.json"))
    // format: off
    val s3StorageModel = S3Storage(projectRef, id, 1L, deprecated = false, default = true, "SHA-256", "bucket", S3Settings(Some(S3Credentials("access", "secret")), Some("endpoint"), Some("region")), Permission.unsafe("my/read"), Permission.unsafe("my/write"), 10737418240L)
    val s3StorageModelEncrypted = S3Storage(projectRef, id, 1L, deprecated = false, default = true, "SHA-256", "bucket", S3Settings(Some(S3Credentials("ByjwlDNy8D1Gm1o0EFCXwA==", "SjMIILT+A5BTUH4LP8sJBg==")), Some("endpoint"), Some("region")), Permission.unsafe("my/read"), Permission.unsafe("my/write"), 10737418240L)
    // format: on
    val typesS3                 = Set(nxv.Storage.value, nxv.S3Storage.value)
    val typesRemote             = Set(nxv.Storage.value, nxv.RemoteDiskStorage.value)

    def resourceV(json: Json, rev: Long = 1L, types: Set[AbsoluteIri]): ResourceV = {
      val ctx   = Json.obj("@context" -> (storageCtx.contextValue deepMerge resourceCtx.contextValue))
      val graph = (json deepMerge Json.obj("@id" -> Json.fromString(id.asString)))
        .replaceContext(ctx)
        .toGraph(resId.value)
        .rightValue

      val resourceV =
        ResourceF.simpleV(resId, Value(json, ctx.contextValue, graph), rev, schema = storageRef, types = types)
      resourceV.copy(
        value = resourceV.value.copy(graph = graph ++ resourceV.metadata())
      )
    }
  }

  "Storages bundle" when {

    "performing create operations" should {

      "prevent to create a storage that does not validate against the storage schema" in new Base {
        val invalid = List.range(1, 2).map(i => jsonContentOf(s"/storage/storage-wrong-$i.json"))
        forAll(invalid) { j =>
          val json = updateId(j)
          storages.create(json).value.rejected[InvalidResource]
        }
      }

      "create a DiskStorage" in new Base {
        storageCache.put(eqTo(diskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        val result   = storages.create(diskStorage).value.accepted
        val expected =
          ResourceF.simpleF(resId, diskStorage, schema = storageRef, types = typesDisk)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "create a RemoteDiskStorage" in new Base {
        storageCache.put(eqTo(remoteDiskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        val result   = storages.create(resId, remoteDiskStorage).value.accepted
        val expected = ResourceF.simpleF(resId, remoteDiskStorage, schema = storageRef, types = typesRemote)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "create a S3Storage" in new Base {
        storageCache.put(eqTo(s3StorageModel))(any[Instant]) shouldReturn IO.pure(())
        val result   = storages.create(resId, s3Storage).value.accepted
        val expected = ResourceF.simpleF(resId, s3Storage, schema = storageRef, types = typesS3)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "prevent creating a storage with the id passed on the call not matching the @id on the payload" in new Base {
        val json = diskStorage deepMerge Json.obj("@id" -> Json.fromString(genIri.asString))
        storages.create(resId, json).value.rejected[IncorrectId] shouldEqual IncorrectId(resId.ref)
      }

    }

    "performing update operations" should {

      "update a storage" in new Base {
        val storageUpdated = diskStorage deepMerge Json.obj(
          "default"     -> Json.fromBoolean(true),
          "maxFileSize" -> Json.fromLong(200L)
        )
        storageCache.put(eqTo(diskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.create(resId, diskStorage).value.accepted shouldBe a[Resource]
        storageCache.put(eqTo(diskStorageModel.copy(default = true, maxFileSize = 200L)))(any[Instant]) shouldReturn IO
          .pure(())
        val result         = storages.update(resId, 1L, storageUpdated).value.accepted
        val expected       = ResourceF.simpleF(resId, storageUpdated, 2L, schema = storageRef, types = typesDisk)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "prevent to update a resolver that does not exists" in new Base {
        storages.update(resId, 1L, diskStorage).value.rejected[NotFound] shouldEqual NotFound(resId.ref)
      }
    }

    "performing deprecate operations" should {

      "deprecate a storage" in new Base {
        storageCache.put(eqTo(diskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.create(resId, diskStorage).value.accepted shouldBe a[Resource]
        val result   = storages.deprecate(resId, 1L).value.accepted
        val expected =
          ResourceF.simpleF(resId, diskStorage, 2L, schema = storageRef, types = typesDisk, deprecated = true)
        result.copy(value = Json.obj()) shouldEqual expected.copy(value = Json.obj())
      }

      "prevent deprecating a resolver already deprecated" in new Base {
        storageCache.put(eqTo(diskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.create(resId, diskStorage).value.accepted shouldBe a[Resource]
        storages.deprecate(resId, 1L).value.accepted shouldBe a[Resource]
        storages.deprecate(resId, 2L).value.rejected[ResourceIsDeprecated] shouldBe a[ResourceIsDeprecated]
      }
    }

    "performing read operations" should {
      val diskAddedJson = Json.obj(
        "_algorithm"      -> Json.fromString("SHA-256"),
        "writePermission" -> Json.fromString("files/write"),
        "readPermission"  -> Json.fromString("resources/read"),
        "maxFileSize"     -> Json.fromLong(appConfig.kg.storage.disk.maxFileSize)
      )

      val s3AddedJson = Json.obj(
        "_algorithm"  -> Json.fromString("SHA-256"),
        "maxFileSize" -> Json.fromLong(appConfig.kg.storage.amazon.maxFileSize)
      )

      "return a storage" in new Base {
        storageCache.put(eqTo(diskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.create(resId, diskStorage).value.accepted shouldBe a[Resource]
        val result   = storages.fetch(resId).value.accepted
        val expected = resourceV(diskStorage deepMerge diskAddedJson, 1L, typesDisk)
        result.value.ctx shouldEqual expected.value.ctx
        result.value.graph shouldEqual expected.value.graph
        result shouldEqual expected.copy(value = result.value)
        storages.fetchSource(resId).value.accepted should equalIgnoreArrayOrder(diskStorageSource)
      }

      "return the requested storage on a specific revision" in new Base {
        storageCache.put(eqTo(diskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.create(resId, diskStorage).value.accepted shouldBe a[Resource]
        storageCache.put(eqTo(s3StorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.update(resId, 1L, s3Storage).value.accepted shouldBe a[Resource]
        storageCache.put(eqTo(remoteDiskStorageModel))(any[Instant]) shouldReturn IO.pure(())
        storages.update(resId, 2L, remoteDiskStorage).value.accepted shouldBe a[Resource]

        storages.fetch(resId, 3L).value.accepted shouldEqual storages.fetch(resId).value.accepted
        storages.fetchSource(resId, 3L).value.accepted should equalIgnoreArrayOrder(remoteStorageSource)

        val resultRemote   = storages.fetch(resId, 3L).value.accepted
        val expectedRemote =
          resourceV(remoteDiskStorage.removeKeys("credentials") deepMerge diskAddedJson, 3L, typesRemote)
        resultRemote.value.ctx shouldEqual expectedRemote.value.ctx
        expectedRemote.value.graph shouldEqual expectedRemote.value.graph
        expectedRemote shouldEqual expectedRemote.copy(value = expectedRemote.value)

        val resultS3   = storages.fetch(resId, 2L).value.accepted
        val expectedS3 = resourceV(s3Storage.removeKeys("accessKey", "secretKey") deepMerge s3AddedJson, 2L, typesS3)
        resultS3.value.ctx shouldEqual expectedS3.value.ctx
        resultS3.value.graph shouldEqual expectedS3.value.graph
        resultS3 shouldEqual expectedS3.copy(value = resultS3.value)

        val result   = storages.fetch(resId, 1L).value.accepted
        val expected = resourceV(diskStorage deepMerge diskAddedJson, 1L, typesDisk)
        result.value.ctx shouldEqual expected.value.ctx
        result.value.graph shouldEqual expected.value.graph
        result shouldEqual expected.copy(value = result.value)
      }

      "return NotFound when the provided storage does not exists" in new Base {
        storages.fetch(resId).value.rejected[NotFound] shouldEqual NotFound(resId.ref, schemaOpt = Some(storageRef))
        storages.fetchSource(resId).value.rejected[NotFound] shouldEqual NotFound(
          resId.ref,
          schemaOpt = Some(storageRef)
        )
      }
    }
  }
}
