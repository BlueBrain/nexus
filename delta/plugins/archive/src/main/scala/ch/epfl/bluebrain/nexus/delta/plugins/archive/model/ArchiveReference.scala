package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.Order
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

import java.nio.file.Path

/**
  * Enumeration of archive references.
  */
sealed trait ArchiveReference extends Product with Serializable {

  /**
    * @return the referenced resource id optionally qualified with a tag or a revision
    */
  def ref: ResourceRef

  /**
    * @return the parent project of the referenced resource
    */
  def project: ProjectRef

  /**
    * @return the target location in the archive
    */
  def path: Path

  /**
    * @return the archive reference type
    */
  def tpe: ArchiveReferenceType
}

object ArchiveReference {

  /**
    * An archive resource reference.
    *
    * @param ref            the referenced resource id
    * @param project        the parent project of the referenced resource
    * @param path           the target location in the archive
    * @param representation the format in which the resource should be represented
    */
  final case class ResourceReference(
      ref: ResourceRef,
      project: ProjectRef,
      path: Path,
      representation: ArchiveResourceRepresentation
  ) extends ArchiveReference {
    override val tpe: ArchiveReferenceType = ArchiveReferenceType.Resource
  }

  /**
    * An archive file reference.
    *
    * @param ref     the referenced resource id
    * @param project the parent project of the referenced resource
    * @param path    the target location in the archive
    */
  final case class FileReference(
      ref: ResourceRef,
      project: ProjectRef,
      path: Path
  ) extends ArchiveReference {
    override val tpe: ArchiveReferenceType = ArchiveReferenceType.File
  }

  // order also implies equality
  implicit final val archiveReferenceOrder: Order[ArchiveReference] = Order.by {
    case FileReference(ref, project, path)                     =>
      ref.original.toString + project.toString + path.toString
    case ResourceReference(ref, project, path, representation) =>
      ref.original.toString + project.toString + path.toString + representation.toString
  }

}
