package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.{DecodingDerivationFailure, ParsingFailure}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}

import java.nio.file.{Path, Paths}

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
  final case class ResourceReference(
      ref: ResourceRef,
      project: Option[ProjectRef],
      path: Option[Path],
      representation: Option[ArchiveResourceRepresentation]
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
      project: Option[ProjectRef],
      path: Option[Path]
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

  implicit final val archiveReferenceJsonLdDecoder: JsonLdDecoder[ArchiveReference] = {
    // custom decoder for resource ref to rev and tag references from separate fields
    implicit val resourceRefDecoder: JsonLdDecoder[ResourceRef] =
      (cursor: ExpandedJsonLdCursor) =>
        for {
          iri    <- cursor.downField(nxv + "resourceId").get[Iri]
          revOpt <- cursor.downField(nxv + "rev").get[Option[Long]]
          tagOpt <- cursor.downField(nxv + "tag").get[Option[TagLabel]]
          value  <- (revOpt, tagOpt) match {
                      case (None, None)      => Right(ResourceRef.Latest(iri))
                      case (Some(rev), None) => Right(ResourceRef.Revision(iri, rev))
                      case (None, Some(tag)) => Right(ResourceRef.Tag(iri, iri, tag))
                      case _                 =>
                        Left(ParsingFailure("An archive resource reference cannot use both 'rev' and 'tag' fields."))
                    }
        } yield value

    // path decoder that checks for absolute paths
    implicit val absolutePathDecoder: JsonLdDecoder[Path] = {
      val pathDecoder: JsonLdDecoder[Path] = (cursor: ExpandedJsonLdCursor) =>
        cursor.getValueTry[Path](str => Paths.get(str))
      pathDecoder.flatMap { path =>
        if (path.isAbsolute) Right(path)
        else Left(ParsingFailure(s"The archive reference path '$path' is not absolute."))
      }
    }

    val resourceReferenceDecoder: JsonLdDecoder[ResourceReference] =
      (cursor: ExpandedJsonLdCursor) =>
        for {
          ref     <- cursor.get[ResourceRef]
          project <- cursor.downField(nxv + "project").get[Option[ProjectRef]]
          path    <- cursor.downField(nxv + "path").get[Option[Path]]
          repr    <- cursor.get[Option[ArchiveResourceRepresentation]]
        } yield ResourceReference(ref, project, path, repr)

    val fileReferenceDecoder: JsonLdDecoder[FileReference] =
      (cursor: ExpandedJsonLdCursor) =>
        for {
          ref     <- cursor.get[ResourceRef]
          project <- cursor.downField(nxv + "project").get[Option[ProjectRef]]
          path    <- cursor.downField(nxv + "path").get[Option[Path]]
        } yield FileReference(ref, project, path)

    (cursor: ExpandedJsonLdCursor) =>
      for {
        types     <- cursor.getTypes
        reference <- if (types.contains(nxv + "Resource")) resourceReferenceDecoder(cursor)
                     else if (types.contains(nxv + "File")) fileReferenceDecoder(cursor)
                     else Left(DecodingDerivationFailure("Unable to find type discriminator for 'ArchiveReference'"))
      } yield reference
  }

}
