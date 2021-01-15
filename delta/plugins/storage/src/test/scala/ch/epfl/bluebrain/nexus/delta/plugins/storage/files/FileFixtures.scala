package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, MessageEntity, Multipart, Uri}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF

import java.util.UUID
import java.nio.file.{Files => JavaFiles}

trait FileFixtures {

  val uuid                     = UUID.fromString("8249ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit val uuidF: UUIDF    = UUIDF.fixed(uuid)
  val org                      = Label.unsafe("org")
  val orgDeprecated            = Label.unsafe("org-deprecated")
  val project                  = ProjectGen.project("org", "proj", base = nxv.base, mappings = ApiMappings.default)
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
  val projectRef               = project.ref
  val diskId                   = nxv + "disk"
  val diskRev                  = ResourceRef.Revision(iri"$diskId?rev=1", diskId, 1)
  val diskId2                  = nxv + "disk2"
  val file1                    = nxv + "file1"
  val file2                    = nxv + "file2"
  val file1Encoded             = UrlUtils.encode(file1.toString)
  val generatedId              = project.base.iri / uuid.toString

  val content = "file content"
  val path    = JavaFiles.createTempDirectory("files")
  val digest  =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

  def attributes(filename: String = "file.txt", size: Long = 12): FileAttributes = FileAttributes(
    uuid,
    s"file://$path/org/proj/8/2/4/9/b/a/9/0/$filename",
    Uri.Path(s"org/proj/8/2/4/9/b/a/9/0/$filename"),
    filename,
    `text/plain(UTF-8)`,
    size,
    digest,
    Client
  )

  def entity(filename: String = "file.txt"): MessageEntity =
    Multipart
      .FormData(
        Multipart.FormData.BodyPart("file", HttpEntity(`text/plain(UTF-8)`, content), Map("filename" -> filename))
      )
      .toEntity()
}
