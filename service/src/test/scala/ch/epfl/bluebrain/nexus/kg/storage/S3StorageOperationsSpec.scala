package ch.epfl.bluebrain.nexus.kg.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.UUID

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3
import akka.stream.scaladsl.{FileIO, Sink}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.test.io.IOValues
import ch.epfl.bluebrain.nexus.commons.test.{ActorSystemFixture, Randomness, Resources}
import ch.epfl.bluebrain.nexus.iam.client.types.Permission
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.KgError.DownstreamServiceError
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{Digest, FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef

import ch.epfl.bluebrain.nexus.kg.storage.Storage.{S3Credentials, S3Settings, S3Storage}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.amazonaws.auth.{AWSStaticCredentialsProvider, AnonymousAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import io.findify.s3mock.S3Mock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.jdk.CollectionConverters._
import scala.collection.mutable

class S3StorageOperationsSpec
    extends ActorSystemFixture("S3StorageOperationsSpec")
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with IOValues
    with Randomness
    with Resources {

  private val port    = freePort
  private val address = s"http://localhost:$port"
  private val region  = "fake-region"
  private val bucket  = "bucket"
  private val s3mock  = S3Mock(port)
  private val readS3  = Permission.unsafe("s3/read")
  private val writeS3 = Permission.unsafe("s3/write")

  private var client: AmazonS3 = _

  private val keys  = Set("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts")
  private val props = mutable.Map[String, String]()

  override protected def beforeAll(): Unit = {
    // saving the current system properties so that we can restore them later
    props ++= System.getProperties.asScala.toMap.view.filterKeys(keys.contains)
    keys.foreach(System.clearProperty)
    // S3 client needs to be created after we clear the system proxy settings
    client = AmazonS3ClientBuilder.standard
      .withPathStyleAccessEnabled(true)
      .withEndpointConfiguration(new EndpointConfiguration(address, region))
      .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials))
      .build
    s3mock.start
    client.createBucket(bucket)
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    s3mock.stop
    keys.foreach { k =>
      props.get(k) match {
        case Some(v) => System.setProperty(k, v)
        case None    => System.clearProperty(k)
      }
    }
    super.afterAll()
  }

  "S3StorageOperations" should {

    "save and fetch files" in {
      keys.foreach(System.clearProperty)

      val base       = url"https://nexus.example.com/"
      val projectId  = base + "org" + "proj"
      val projectRef = ProjectRef(UUID.randomUUID)
      val storage    =
        S3Storage(
          projectRef,
          projectId,
          1L,
          deprecated = false,
          default = true,
          "MD5",
          bucket,
          S3Settings(None, Some(address), Some(region)),
          readS3,
          writeS3,
          1024L
        )

      val verify = new S3StorageOperations.Verify[IO](storage)
      val save   = new S3StorageOperations.Save[IO](storage)
      val fetch  = new S3StorageOperations.Fetch[IO](storage)

      // bucket is empty
      verify.apply.ioValue shouldEqual Right(())

      val resid    = Id(projectRef, base + "files" + "id")
      val fileUuid = UUID.randomUUID
      val desc     = FileDescription(fileUuid, "my s3.json", Some(`text/plain(UTF-8)`))
      val filePath = "/storage/s3.json"
      val path     = Paths.get(getClass.getResource(filePath).toURI)
      val attr     = save(resid, desc, FileIO.fromPath(path)).ioValue
      val uriPath  = Uri.Path(mangle(projectRef, fileUuid, "my s3.json"))
      val location = Uri(s"http://s3.amazonaws.com/$bucket/$uriPath")
      val digest   = Digest("MD5", "5d3c675f85ffb2da9a8141ccd45bd6c6")
      // http://s3.amazonaws.com is hardcoded in S3Mock
      attr shouldEqual FileAttributes(fileUuid, location, uriPath, "my s3.json", `text/plain(UTF-8)`, 263L, digest)

      val download = fetch(attr).ioValue.runWith(Sink.head).futureValue.decodeString(UTF_8)
      download shouldEqual contentOf(filePath)

      // bucket has one object
      verify.apply.ioValue shouldEqual Right(())

      val randomUuid    = UUID.randomUUID
      val wrongPath     = Uri.Path(mangle(projectRef, randomUuid, genString()))
      val wrongLocation = Uri(s"http://s3.amazonaws.com/$bucket/$wrongPath")
      val nonexistent   = fetch(attr.copy(uuid = randomUuid, path = wrongPath, location = wrongLocation))
        .failed[KgError.RemoteFileNotFound]
      nonexistent shouldEqual KgError.RemoteFileNotFound(wrongLocation)
    }

    "link and fetch files" in {
      keys.foreach(System.clearProperty)
      client.putObject(bucket, "some/key/my s3.json", contentOf("/storage/s3.json"))

      val base       = url"https://nexus.example.com/"
      val projectId  = base + "org" + "proj"
      val projectRef = ProjectRef(UUID.randomUUID)
      val storage    =
        S3Storage(
          projectRef,
          projectId,
          1L,
          deprecated = false,
          default = true,
          "MD5",
          bucket,
          S3Settings(None, Some(address), Some(region)),
          readS3,
          writeS3,
          1024L
        )

      val verify = new S3StorageOperations.Verify[IO](storage)
      val link   = new S3StorageOperations.Link[IO](storage)
      val fetch  = new S3StorageOperations.Fetch[IO](storage)

      verify.apply.ioValue shouldEqual Right(())
      val count = client.listObjectsV2(bucket).getKeyCount

      val resid    = Id(projectRef, base + "files" + "id")
      val fileUuid = UUID.randomUUID
      val desc     = FileDescription(fileUuid, "my s3.json", Some(`text/plain(UTF-8)`))
      val key      = Uri.Path("some/key/my s3.json")
      val location = Uri(s"$address/$bucket/$key")
      val attr     = link(resid, desc, key).ioValue
      val digest   = Digest("MD5", "5d3c675f85ffb2da9a8141ccd45bd6c6")
      attr shouldEqual
        FileAttributes(fileUuid, location, key, "my s3.json", `text/plain(UTF-8)`, 263L, digest)

      val download =
        fetch(attr).ioValue.runWith(Sink.head).futureValue.decodeString(UTF_8)
      download shouldEqual contentOf("/storage/s3.json")

      verify.apply.ioValue shouldEqual Right(())
      client.listObjectsV2(bucket).getKeyCount shouldEqual count
    }

    "fail if the bucket doesn't exist" in {
      keys.foreach(System.clearProperty)

      val base       = url"https://nexus.example.com/"
      val projectId  = base + "org" + "proj"
      val projectRef = ProjectRef(UUID.randomUUID)
      val storage    =
        S3Storage(
          projectRef,
          projectId,
          1L,
          deprecated = false,
          default = true,
          "MD5",
          "foobar",
          S3Settings(None, Some(address), Some(region)),
          readS3,
          writeS3,
          1024L
        )

      val verify = new S3StorageOperations.Verify[IO](storage)
      val save   = new S3StorageOperations.Save[IO](storage)
      val fetch  = new S3StorageOperations.Fetch[IO](storage)

      verify.apply.ioValue shouldEqual Left("Error accessing S3 bucket 'foobar': The specified bucket does not exist")

      val resid    = Id(projectRef, base + "files" + "id")
      val fileUuid = UUID.randomUUID
      val desc     = FileDescription(fileUuid, "my s3.json", Some(`text/plain(UTF-8)`))
      val filePath = "/storage/s3.json"
      val path     = Paths.get(getClass.getResource(filePath).toURI)
      val upload   = save(resid, desc, FileIO.fromPath(path)).failed[DownstreamServiceError]
      upload.msg shouldEqual "Error uploading S3 object with filename 'my s3.json' in bucket 'foobar': The specified bucket does not exist"

      val key      = Uri.Path(mangle(projectRef, fileUuid, "my s3.json"))
      val location = Uri(s"http://s3.amazonaws.com/foobar/$key")
      val digest   = Digest("MD5", "5d3c675f85ffb2da9a8141ccd45bd6c6")
      val attr     = FileAttributes(fileUuid, location, key, desc.filename, `text/plain(UTF-8)`, 263L, digest)
      val download = fetch(attr).failed[DownstreamServiceError]
      download.msg shouldEqual s"Error fetching S3 object with key '$key' in bucket 'foobar': The specified bucket does not exist"
    }

    "verify storage with no region" in {
      keys.foreach(System.clearProperty)

      val base       = url"https://nexus.example.com/"
      val projectId  = base + "org" + "proj"
      val projectRef = ProjectRef(UUID.randomUUID)
      val storage    =
        S3Storage(
          projectRef,
          projectId,
          1L,
          deprecated = false,
          default = true,
          "MD5",
          bucket,
          S3Settings(None, Some(address), None),
          readS3,
          writeS3,
          1024L
        )

      val verify = new S3StorageOperations.Verify[IO](storage)
      verify.apply.ioValue shouldEqual Right(())
    }

    "save and fetch files in AWS" ignore {
      keys.foreach(System.clearProperty)

      val ak = "encryptedAccessKey"
      val sk = "encryptedSecretKey"

      val base       = url"https://nexus.example.com/"
      val projectId  = base + "org" + "proj"
      val projectRef = ProjectRef(UUID.randomUUID)
      val storage    =
        S3Storage(
          projectRef,
          projectId,
          1L,
          deprecated = false,
          default = true,
          "SHA-256",
          "nexus-storage",
          S3Settings(Some(S3Credentials(ak, sk)), Some("http://minio.dev.nexus.ocp.bbp.epfl.ch"), None),
          readS3,
          writeS3,
          1024L
        )

      val verify = new S3StorageOperations.Verify[IO](storage)
      val save   = new S3StorageOperations.Save[IO](storage)
      val fetch  = new S3StorageOperations.Fetch[IO](storage)

      // bucket is empty
      verify.apply.ioValue shouldEqual Right(())

      val resid    = Id(projectRef, base + "files" + "id")
      val fileUuid = UUID.randomUUID
      val desc     = FileDescription(fileUuid, "my s3.json", Some(`text/plain(UTF-8)`))
      val filePath = "/storage/s3.json"
      val path     = Paths.get(getClass.getResource(filePath).toURI)
      val attr     = save(resid, desc, FileIO.fromPath(path)).ioValue

      attr.location shouldEqual Uri(
        s"http://minio.dev.nexus.ocp.bbp.epfl.ch/nexus-storage/${mangle(projectRef, fileUuid, "my s3.json")}"
      )
      attr.mediaType shouldEqual `text/plain(UTF-8)`
      attr.bytes shouldEqual 263L
      attr.filename shouldEqual "my s3.json"
      attr.digest shouldEqual Digest("SHA-256", "5602c497e51680bef1f3120b1d6f65d480555002a3290029f8178932e8f4801a")

      val download =
        fetch(attr).ioValue.runWith(Sink.head).futureValue.decodeString(UTF_8)
      download shouldEqual contentOf(filePath)

      // bucket has one object
      verify.apply.ioValue shouldEqual Right(())

      val randomUuid = UUID.randomUUID
      val inexistent = fetch(
        attr.copy(
          uuid = randomUuid,
          location =
            Uri(s"http://minio.dev.nexus.ocp.bbp.epfl.ch/nexus-storage/${mangle(projectRef, randomUuid, "my s3.json")}")
        )
      ).failed[KgError.InternalError]
      inexistent.msg shouldEqual s"Empty content fetching S3 object with key '${mangle(projectRef, randomUuid, "my s3.json")}' in bucket 'nexus-storage'"
    }
  }

  "S3Settings" should {
    "select the system proxy" in {
      System.setProperty("http.proxyHost", "example.com")
      System.setProperty("http.proxyPort", "8080")
      System.setProperty("https.proxyHost", "secure.example.com")
      System.setProperty("https.proxyPort", "8080")
      System.setProperty("http.nonProxyHosts", "*.epfl.ch|*.cluster.local")

      S3Settings.getSystemProxy("http://s3.amazonaws.com") shouldEqual Some(s3.Proxy("example.com", 8080, "http"))
      S3Settings.getSystemProxy("https://s3.amazonaws.com") shouldEqual Some(
        s3.Proxy("secure.example.com", 8080, "http")
      )
      S3Settings.getSystemProxy("https://www.epfl.ch") shouldEqual None
      S3Settings.getSystemProxy("http://foo.bar.cluster.local") shouldEqual None
    }
  }

}
