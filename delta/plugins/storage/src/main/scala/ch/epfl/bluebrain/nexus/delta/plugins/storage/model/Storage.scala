package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

import java.nio.file.Path

sealed trait Storage extends Product with Serializable {

  /**
    * @return the view id
    */
  def id: Iri

  /**
    * @return a reference to the project that the storage belongs to
    */
  def project: ProjectRef

  /**
    * @return the tag -> rev mapping
    */
  def tags: Map[Label, Long]

  /**
    * @return the original json document provided at creation or update
    */
  def source: Json
}

object Storage {

  /**
    * A storage that stores and fetches files from a local volume
    */
  final case class DiskStorage(
      id: Iri,
      project: ProjectRef,
      default: Boolean,
      algorithm: Algorithm,
      volume: Path,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      tags: Map[Label, Long],
      source: Json
  ) extends Storage

  /**
    * A storage that stores and fetches files from an S3 compatible service
    */
  final case class S3Storage(
      id: Iri,
      project: ProjectRef,
      default: Boolean,
      algorithm: Algorithm,
      bucket: String,
      settings: S3Settings,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      tags: Map[Label, Long],
      source: Json
  ) extends Storage

  /**
    * A storage that stores and fetches files from a remote volume using a well-defined API
    */
  final case class RemoteDiskStorage(
      id: Iri,
      project: ProjectRef,
      default: Boolean,
      algorithm: Algorithm,
      endpoint: Uri,
      credentials: Option[String],
      folder: String,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long,
      tags: Map[Label, Long],
      source: Json
  ) extends Storage
}
