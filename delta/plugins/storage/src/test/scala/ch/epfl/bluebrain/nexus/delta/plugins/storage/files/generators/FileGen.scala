package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FileResource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef, Tags}
import org.http4s.Uri

import java.nio.file.Files as JavaFiles
import java.time.Instant
import java.util.UUID

object FileGen {

  def state(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      attributes: FileAttributes,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): FileState = {
    FileState(
      id,
      project,
      storage,
      storageType,
      attributes,
      tags,
      rev,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      updatedBy
    )
  }

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      attributes: FileAttributes,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): FileResource =
    state(
      id,
      project,
      storage,
      attributes,
      storageType,
      rev,
      deprecated,
      tags,
      createdBy,
      updatedBy
    ).toResource

  def mkTempDir(prefix: String): AbsolutePath =
    AbsolutePath(JavaFiles.createTempDirectory(prefix)).fold(e => throw new Exception(e), identity)

  private val digest =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

  def attributes(
      filename: String,
      size: Long,
      id: UUID,
      projRef: ProjectRef,
      path: AbsolutePath,
      keywords: Map[Label, String],
      description: Option[String],
      name: Option[String]
  ): FileAttributes = {
    val uuidPathSegment = id.toString.take(8).mkString("/")
    FileAttributes(
      id,
      Uri.unsafeFromString(s"file://$path/${projRef.toString}/$uuidPathSegment/$filename"),
      Uri.Path.unsafeFromString(s"${projRef.toString}/$uuidPathSegment/$filename"),
      filename,
      Some(`text/plain(UTF-8)`),
      keywords,
      description,
      name,
      size,
      digest,
      Client
    )
  }
}
