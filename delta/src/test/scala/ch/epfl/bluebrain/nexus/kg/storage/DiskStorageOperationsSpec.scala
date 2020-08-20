package ch.epfl.bluebrain.nexus.kg.storage

import java.nio.file.Paths

import akka.http.scaladsl.model.{ContentTypes, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File.FileDescription
import ch.epfl.bluebrain.nexus.kg.{KgError, TestHelper}
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util.{ActorSystemFixture, IOEitherValues, Resources}
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, OptionValues}

import scala.concurrent.duration._

class DiskStorageOperationsSpec
    extends ActorSystemFixture("DiskStorageOperationsSpec")
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfter
    with IdiomaticMockito
    with IOEitherValues
    with Resources
    with TestHelper
    with OptionValues {

  implicit private val appConfig = Settings(system).appConfig

  implicit private val sc: StorageConfig = appConfig.storage.copy(
    DiskStorageConfig(Paths.get("/tmp"), "SHA-256", read, write, false, 1024L),
    RemoteDiskStorageConfig("http://example.com", "v1", None, "SHA-256", read, write, true, 1024L),
    S3StorageConfig("MD5", read, write, true, 1024L),
    "password",
    "salt",
    RetryStrategyConfig("linear", 300.millis, 5.minutes, 100, 1.second)
  )

  private val project  = ProjectRef(genUUID)
  private val storage  = Storage.DiskStorage.default(project)
  private val resId    = Id(storage.ref, genIri)
  private val fileDesc = FileDescription("my file.txt", ContentTypes.`text/plain(UTF-8)`)

  "DiskStorageOperations" should {

    "verify when the storage exists" in {
      val verify = new DiskStorageOperations.VerifyDiskStorage[IO](storage)
      verify.apply.accepted
    }

    "save and fetch files" in {
      val save   = new DiskStorageOperations.SaveDiskFile[IO](storage)
      val fetch  = new DiskStorageOperations.FetchDiskFile[IO]()
      val source = genSource

      val attr    = save.apply(resId, fileDesc, source).ioValue
      attr.bytes shouldEqual 16L
      attr.filename shouldEqual fileDesc.filename
      attr.mediaType shouldEqual fileDesc.mediaType.value
      attr.location shouldEqual Uri(s"file:///tmp/${mangle(project, attr.uuid, "my%20file.txt")}")
      attr.path shouldEqual attr.location.path.tail.tail.tail
      val fetched = fetch.apply(attr).ioValue

      consume(source) shouldEqual consume(fetched)
    }

    "not link files" in {
      val link = new DiskStorageOperations.LinkDiskFile[IO]()
      link.apply(resId, fileDesc, Uri.Path("/foo")).failed[KgError] shouldEqual KgError.UnsupportedOperation
    }
  }

}
