package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.Order
import cats.implicits.toBifunctorOps
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.PathIsNotAbsolute
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.{CompactedJsonLd, SourceJson}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}

import java.nio.file.{Path, Paths}
import scala.annotation.nowarn

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
  def project: Option[ProjectRef]

  /**
    * @return the target location in the archive
    */
  def path: Option[Path]

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
  final case class ResourceReference private (
      ref: ResourceRef,
      project: Option[ProjectRef],
      path: Option[Path],
      representation: Option[ArchiveResourceRepresentation]
  ) extends ArchiveReference {
    override val tpe: ArchiveReferenceType = ArchiveReferenceType.Resource
  }

  object ResourceReference {

    /**
      * Safely constructs a resource reference that checks that if a path is provided it will be absolute.
      *
      * @param ref            the referenced resource id
      * @param project        the parent project of the referenced resource
      * @param path           the target location in the archive
      * @param representation the format in which the resource should be represented
      */
    def apply(
        ref: ResourceRef,
        project: Option[ProjectRef],
        path: Option[Path],
        representation: Option[ArchiveResourceRepresentation]
    ): Either[PathIsNotAbsolute, ResourceReference] =
      path match {
        case None                            => Right(new ResourceReference(ref, project, path, representation))
        case Some(value) if value.isAbsolute => Right(new ResourceReference(ref, project, path, representation))
        case Some(value)                     => Left(PathIsNotAbsolute(Set(value)))
      }
  }

  /**
    * An archive file reference.
    *
    * @param ref     the referenced resource id
    * @param project the parent project of the referenced resource
    * @param path    the target location in the archive
    */
  final case class FileReference private (
      ref: ResourceRef,
      project: Option[ProjectRef],
      path: Option[Path]
  ) extends ArchiveReference {
    override val tpe: ArchiveReferenceType = ArchiveReferenceType.File
  }

  object FileReference {

    /**
      * Safely constructs a file reference that checks that if a path is provided it will be absolute.
      *
      * @param ref            the referenced resource id
      * @param project        the parent project of the referenced resource
      * @param path           the target location in the archive
      */
    def apply(
        ref: ResourceRef,
        project: Option[ProjectRef],
        path: Option[Path]
    ): Either[PathIsNotAbsolute, FileReference] =
      path match {
        case None                            => Right(new FileReference(ref, project, path))
        case Some(value) if value.isAbsolute => Right(new FileReference(ref, project, path))
        case Some(value)                     => Left(PathIsNotAbsolute(Set(value)))
      }
  }

  // order also implies equality
  implicit final val archiveReferenceOrder: Order[ArchiveReference] = Order.by {
    case FileReference(ref, project, path)                     =>
      ref.original.toString + project.toString + path.toString
    case ResourceReference(ref, project, path, representation) =>
      ref.original.toString + project.toString + path.toString + representation.toString
  }

  sealed private trait ReferenceInput extends Product with Serializable

  final private case class ResourceInput(
      resourceId: Iri,
      project: Option[ProjectRef],
      tag: Option[TagLabel],
      rev: Option[Long],
      path: Option[Path],
      originalSource: Option[Boolean],
      format: Option[ArchiveResourceRepresentation]
  ) extends ReferenceInput

  final private case class FileInput(
      resourceId: Iri,
      project: Option[ProjectRef],
      tag: Option[TagLabel],
      rev: Option[Long],
      path: Option[Path]
  ) extends ReferenceInput

  @nowarn("cat=unused")
  implicit final val referenceInputJsonLdDecoder: JsonLdDecoder[ArchiveReference] = {

    // path decoder that checks for absolute paths
    implicit val absolutePathDecoder: JsonLdDecoder[Path] = {
      val pathDecoder: JsonLdDecoder[Path] = (cursor: ExpandedJsonLdCursor) =>
        cursor.getValueTry[Path](str => Paths.get(str))
      pathDecoder.flatMap { path =>
        if (path.isAbsolute) Right(path)
        else Left(ParsingFailure(s"The archive reference path '$path' is not absolute."))
      }
    }

    def refOf(resourceId: Iri, tag: Option[TagLabel], rev: Option[Long]): ResourceRef =
      (tag, rev) match {
        case (Some(tagLabel), None) => Tag(resourceId, tagLabel)
        case (None, Some(revision)) => Revision(resourceId, revision)
        case _                      => Latest(resourceId)
      }

    val ctx = Configuration.default.context
      .addAliasIdType("ResourceInput", nxv + "Resource")
      .addAliasIdType("FileInput", nxv + "File")

    implicit val cfg: Configuration = Configuration.default.copy(context = ctx)

    deriveConfigJsonLdDecoder[ReferenceInput].flatMap {
      case ResourceInput(_, _, Some(_: TagLabel), Some(_: Long), _, _, _)                         =>
        Left(ParsingFailure("An archive resource reference cannot use both 'rev' and 'tag' fields."))
      case ResourceInput(_, _, _, _, _, Some(_: Boolean), Some(_: ArchiveResourceRepresentation)) =>
        Left(ParsingFailure("An archive resource reference cannot use both 'originalSource' and 'format' fields."))
      case ResourceInput(resourceId, project, tag, rev, path, originalSource, format)             =>
        val ref  = refOf(resourceId, tag, rev)
        val repr = (originalSource, format) match {
          case (_, Some(repr))  => Some(repr)
          case (Some(true), _)  => Some(SourceJson)
          case (Some(false), _) => Some(CompactedJsonLd)
          case _                => None
        }
        ResourceReference(ref, project, path, repr).leftMap(err => ParsingFailure(err.reason))
      case FileInput(_, _, Some(_: TagLabel), Some(_: Long), _)                                   =>
        Left(ParsingFailure("An archive file reference cannot use both 'rev' and 'tag' fields."))
      case FileInput(resourceId, project, tag, rev, path)                                         =>
        val ref = refOf(resourceId, tag, rev)
        FileReference(ref, project, path).leftMap(err => ParsingFailure(err.reason))
    }
  }
}
