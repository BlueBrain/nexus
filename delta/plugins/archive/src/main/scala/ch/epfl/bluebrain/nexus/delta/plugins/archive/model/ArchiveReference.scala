package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceRepresentation.{CompactedJsonLd, SourceJson}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}
import io.circe.Encoder

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
  def path: Option[AbsolutePath]

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
      path: Option[AbsolutePath],
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
      path: Option[AbsolutePath]
  ) extends ArchiveReference {
    override val tpe: ArchiveReferenceType = ArchiveReferenceType.File
  }

  sealed private trait ReferenceInput extends Product with Serializable

  final private case class ResourceInput(
      resourceId: Iri,
      project: Option[ProjectRef],
      tag: Option[TagLabel],
      rev: Option[Long],
      path: Option[AbsolutePath],
      originalSource: Option[Boolean],
      format: Option[ArchiveResourceRepresentation]
  ) extends ReferenceInput

  final private case class FileInput(
      resourceId: Iri,
      project: Option[ProjectRef],
      tag: Option[TagLabel],
      rev: Option[Long],
      path: Option[AbsolutePath]
  ) extends ReferenceInput

  @nowarn("cat=unused")
  implicit final val referenceInputJsonLdDecoder: JsonLdDecoder[ArchiveReference] = {
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
        Right(ResourceReference(ref, project, path, repr))
      case FileInput(_, _, Some(_: TagLabel), Some(_: Long), _)                                   =>
        Left(ParsingFailure("An archive file reference cannot use both 'rev' and 'tag' fields."))
      case FileInput(resourceId, project, tag, rev, path)                                         =>
        val ref = refOf(resourceId, tag, rev)
        Right(FileReference(ref, project, path))
    }
  }

  @nowarn("cat=unused")
  implicit private[model] val archiveReferenceEncoder: Encoder[ArchiveReference] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val cfg: Configuration = Configuration.default
      .withDiscriminator(keywords.tpe)
      .copy(transformConstructorNames = {
        case "ResourceInput" => "Resource"
        case "FileInput"     => "File"
        case other           => other
      })

    def tagOf(ref: ResourceRef): Option[TagLabel] = ref match {
      case Tag(_, _, tag) => Some(tag)
      case _              => None
    }

    def revOf(ref: ResourceRef): Option[Long] = ref match {
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
    }
  }

}
