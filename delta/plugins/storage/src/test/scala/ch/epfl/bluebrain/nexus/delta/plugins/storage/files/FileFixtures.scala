package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, MessageEntity, Multipart, Uri}
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValues
import org.scalatest.Suite

import java.nio.file.{Files => JavaFiles}
import java.util.{Base64, UUID}

trait FileFixtures extends EitherValues {

  self: Suite =>

  val uuid                  = UUID.fromString("8249ba90-7cc6-4de5-93a1-802c04200dcc")
  val uuid2                 = UUID.fromString("12345678-7cc6-4de5-93a1-802c04200dcc")
  val uuidOrg2              = UUID.fromString("66666666-7cc6-4de5-93a1-802c04200dcc")
  val ref                   = Ref.of[IO, UUID](uuid).unsafeRunSync()
  implicit val uuidF: UUIDF = UUIDF.fromRef(ref)
  val org                   = Label.unsafe("org")
  val org2                  = Label.unsafe("org2")
  val project               = ProjectGen.project(org.value, "proj", base = nxv.base, mappings = ApiMappings("file" -> schemas.files))
  val project2              =
    ProjectGen.project(org2.value, "proj2", base = nxv.base, mappings = ApiMappings("file" -> schemas.files))
  val deprecatedProject     = ProjectGen.project("org", "proj-deprecated")
  val projectRef            = project.ref
  val projectRefOrg2        = project2.ref
  val diskId2               = nxv + "disk2"
  val file1                 = nxv + "file1"
  val file2                 = nxv + "file2"
  val fileTagged            = nxv + "fileTagged"
  val fileTagged2           = nxv + "fileTagged2"
  val file1Encoded          = UrlUtils.encode(file1.toString)
  val encodeId              = (id: String) => UrlUtils.encode((nxv + id).toString)
  val generatedId           = project.base.iri / uuid.toString
  val generatedId2          = project.base.iri / uuid2.toString

  val content = "file content"
  val path    = AbsolutePath(JavaFiles.createTempDirectory("files")).rightValue
  val digest  =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

  def withUUIDF[T](id: UUID)(test: => T): T = (for {
    old <- ref.getAndSet(id)
    t   <- IO(test).onError(_ => ref.set(old))
    _   <- ref.set(old)
  } yield t).unsafeRunSync()

  def attributes(
      filename: String = "file.txt",
      size: Long = 12,
      id: UUID = uuid,
      projRef: ProjectRef = projectRef
  ): FileAttributes = {
    val uuidPathSegment = id.toString.take(8).mkString("/")
    FileAttributes(
      id,
      s"file://$path/${projRef.toString}/$uuidPathSegment/$filename",
      Uri.Path(s"${projRef.toString}/$uuidPathSegment/$filename"),
      filename,
      Some(`text/plain(UTF-8)`),
      size,
      digest,
      Client
    )
  }

  def entity(filename: String = "file.txt"): MessageEntity =
    Multipart
      .FormData(
        Multipart.FormData.BodyPart("file", HttpEntity(`text/plain(UTF-8)`, content), Map("filename" -> filename))
      )
      .toEntity()

  def randomEntity(filename: String, size: Int): MessageEntity =
    Multipart
      .FormData(
        Multipart.FormData.BodyPart("file", HttpEntity(`text/plain(UTF-8)`, "0" * size), Map("filename" -> filename))
      )
      .toEntity()

  def base64encode(input: String) = {
    val encodedBytes = Base64.getEncoder.encode(input.getBytes("UTF-8"))
    new String(encodedBytes, "UTF-8")
  }
}
