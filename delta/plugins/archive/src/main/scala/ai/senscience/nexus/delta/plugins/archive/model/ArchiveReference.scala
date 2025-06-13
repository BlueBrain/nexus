package ai.senscience.nexus.delta.plugins.archive.model

import cats.Order
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.instances.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation.{CompactedJsonLd, SourceJson}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Encoder
import org.http4s.Uri

/**
  * Enumeration of archive references.
  */
sealed trait ArchiveReference extends Product with Serializable {

  /**
    * @return
    *   the target location in the archive
    */
  def path: Option[AbsolutePath]
}

/**
  * Enumeration of fully-qualified archive references
  */
sealed trait FullArchiveReference extends ArchiveReference {

  /**
    * @return
    *   the referenced resource id optionally qualified with a tag or a revision
    */
  def ref: ResourceRef

  /**
    * @return
    *   the parent project of the referenced resource
    */
  def project: Option[ProjectRef]

}

object ArchiveReference {

  /**
    * An archive resource reference.
    *
    * @param ref
    *   the referenced resource id
    * @param project
    *   the parent project of the referenced resource
    * @param path
    *   the target location in the archive
    * @param representation
    *   the format in which the resource should be represented
    */
  final case class ResourceReference(
      ref: ResourceRef,
      project: Option[ProjectRef],
      path: Option[AbsolutePath],
      representation: Option[ResourceRepresentation]
  ) extends FullArchiveReference {

    def representationOrDefault: ResourceRepresentation = representation.getOrElse(CompactedJsonLd)

    def defaultFileName = s"${UrlUtils.encodeUri(ref.original.toString)}${representationOrDefault.extension}"
  }

  object ResourceReference {
    implicit val resourceReferenceOrder: Order[ResourceReference] =
      Order.by { resourceReference =>
        (resourceReference.ref, resourceReference.project, resourceReference.path, resourceReference.representation)
      }
  }

  /**
    * An archive file reference.
    *
    * @param ref
    *   the referenced resource id
    * @param project
    *   the parent project of the referenced resource
    * @param path
    *   the target location in the archive
    */
  final case class FileReference(
      ref: ResourceRef,
      project: Option[ProjectRef],
      path: Option[AbsolutePath]
  ) extends FullArchiveReference

  object FileReference {
    implicit val fileReferenceOrder: Order[FileReference] =
      Order.by { fileReference =>
        (fileReference.ref, fileReference.project, fileReference.path)
      }
  }

  /**
    * An archive file self reference, but with a raw uri rather than a full reference
    *
    * @param value
    *   the '_self' of the file, which is the url a user would input to access the file in nexus
    * @param path
    *   the target location in the archive
    */
  final case class FileSelfReference(value: Uri, path: Option[AbsolutePath]) extends ArchiveReference

  object FileSelfReference {
    implicit val fileSelfReferenceOrder: Order[FileSelfReference] =
      Order.by { fileReference =>
        (fileReference.value.toString(), fileReference.path)
      }
  }

  sealed private trait ReferenceInput extends Product with Serializable

  final private case class ResourceInput(
      resourceId: Iri,
      project: Option[ProjectRef],
      tag: Option[UserTag],
      rev: Option[Int],
      path: Option[AbsolutePath],
      originalSource: Option[Boolean],
      format: Option[ResourceRepresentation]
  ) extends ReferenceInput

  final private case class FileInput(
      resourceId: Iri,
      project: Option[ProjectRef],
      tag: Option[UserTag],
      rev: Option[Int],
      path: Option[AbsolutePath]
  ) extends ReferenceInput

  final private case class FileSelfInput(
      value: Uri,
      path: Option[AbsolutePath]
  ) extends ReferenceInput

  implicit final val referenceInputJsonLdDecoder: JsonLdDecoder[ArchiveReference] = {
    def refOf(resourceId: Iri, tag: Option[UserTag], rev: Option[Int]): ResourceRef =
      (tag, rev) match {
        case (Some(tagLabel), None) => Tag(resourceId, tagLabel)
        case (None, Some(revision)) => Revision(resourceId, revision)
        case _                      => Latest(resourceId)
      }

    val ctx = Configuration.default.context
      .addAliasIdType("ResourceInput", nxv + "Resource")
      .addAliasIdType("FileInput", nxv + "File")
      .addAliasIdType("FileSelfInput", nxv + "FileSelf")

    implicit val cfg: Configuration = Configuration.default.copy(context = ctx)

    deriveConfigJsonLdDecoder[ReferenceInput].flatMap {
      case ResourceInput(_, _, Some(_: UserTag), Some(_: Int), _, _, _)                    =>
        Left(ParsingFailure("An archive resource reference cannot use both 'rev' and 'tag' fields."))
      case ResourceInput(_, _, _, _, _, Some(_: Boolean), Some(_: ResourceRepresentation)) =>
        Left(ParsingFailure("An archive resource reference cannot use both 'originalSource' and 'format' fields."))
      case ResourceInput(resourceId, project, tag, rev, path, originalSource, format)      =>
        val ref  = refOf(resourceId, tag, rev)
        val repr = (originalSource, format) match {
          case (_, Some(repr))  => Some(repr)
          case (Some(true), _)  => Some(SourceJson)
          case (Some(false), _) => Some(CompactedJsonLd)
          case _                => None
        }
        Right(ResourceReference(ref, project, path, repr))
      case FileInput(_, _, Some(_: UserTag), Some(_: Int), _)                              =>
        Left(ParsingFailure("An archive file reference cannot use both 'rev' and 'tag' fields."))
      case FileInput(resourceId, project, tag, rev, path)                                  =>
        val ref = refOf(resourceId, tag, rev)
        Right(FileReference(ref, project, path))
      case FileSelfInput(value, path)                                                      =>
        Right(FileSelfReference(value, path))
    }
  }

  implicit private[model] val archiveReferenceEncoder: Encoder[ArchiveReference] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto.*
    implicit val cfg: Configuration = Configuration.default
      .withDiscriminator(keywords.tpe)
      .copy(transformConstructorNames = {
        case "ResourceInput" => "Resource"
        case "FileInput"     => "File"
        case "FileSelfInput" => "FileSelf"
        case other           => other
      })

    def tagOf(ref: ResourceRef): Option[UserTag] = ref match {
      case Tag(_, _, tag) => Some(tag)
      case _              => None
    }

    def revOf(ref: ResourceRef): Option[Int] = ref match {
      case Revision(_, _, rev) => Some(rev)
      case _                   => None
    }

    deriveConfiguredEncoder[ReferenceInput].contramap[ArchiveReference] {
      case ResourceReference(ref, project, path, representation) =>
        ResourceInput(
          resourceId = ref.iri,
          project = project,
          tag = tagOf(ref),
          rev = revOf(ref),
          path = path,
          originalSource = None,
          format = representation
        )
      case FileReference(ref, project, path)                     =>
        FileInput(
          resourceId = ref.iri,
          project = project,
          tag = tagOf(ref),
          rev = revOf(ref),
          path = path
        )
      case FileSelfReference(self, path)                         =>
        FileSelfInput(value = self, path = path)
    }
  }

  implicit val fullArchiveReferenceOrder: Order[FullArchiveReference] = Order.from {
    case (_: ResourceReference, _: FileReference)       => -1
    case (r1: ResourceReference, r2: ResourceReference) => ResourceReference.resourceReferenceOrder.compare(r1, r2)
    case (_: FileReference, _: ResourceReference)       => 1
    case (f1: FileReference, f2: FileReference)         => FileReference.fileReferenceOrder.compare(f1, f2)
  }

  implicit val archiveReferenceOrder: Order[ArchiveReference] = Order.from {
    case (f1: FullArchiveReference, f2: FullArchiveReference) => fullArchiveReferenceOrder.compare(f1, f2)
    case (fs1: FileSelfReference, fs2: FileSelfReference)     => FileSelfReference.fileSelfReferenceOrder.compare(fs1, fs2)
    case (_: FileSelfReference, _: FullArchiveReference)      => -1
    case (_: FullArchiveReference, _: FileSelfReference)      => 1
  }

}
