package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas.fileSchemaUri
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv

import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

import scala.util.Try

/**
  * Enumeration of resource event types.
  */
sealed trait Event extends Product with Serializable {

  /**
    * @return the resource identifier
    */
  def id: Id[ProjectRef]

  /**
    * @return the organization resource identifier
    */
  def organization: OrganizationRef

  /**
    * @return the revision that this event generated
    */
  def rev: Long

  /**
    * @return the instant when this event was recorded
    */
  def instant: Instant

  /**
    * @return the subject which created this event
    */
  def subject: Subject
}

object Event {

  /**
    * A witness to a resource creation.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param schema       the schema that was used to constrain the resource
    * @param types        the collection of known resource types
    * @param source       the source representation of the resource
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class Created(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      schema: Ref,
      types: Set[AbsoluteIri],
      source: Json,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the revision that this event generated
      */
    val rev: Long = 1L
  }

  /**
    * A witness to a resource update.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the revision that this event generated
    * @param types        the collection of new known resource types
    * @param source       the source representation of the new resource value
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class Updated(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      rev: Long,
      types: Set[AbsoluteIri],
      source: Json,
      instant: Instant,
      subject: Subject
  ) extends Event

  /**
    * A witness to a resource deprecation.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the revision that this event generated
    * @param types        the collection of new known resource types
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class Deprecated(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      rev: Long,
      types: Set[AbsoluteIri],
      instant: Instant,
      subject: Subject
  ) extends Event

  /**
    * A witness to a resource tagging. This event creates an alias for a revision.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the revision that this event generated
    * @param targetRev    the revision that is being aliased with the provided ''tag''
    * @param tag          the tag of the alias for the provided ''rev''
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class TagAdded(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      rev: Long,
      targetRev: Long,
      tag: String,
      instant: Instant,
      subject: Subject
  ) extends Event

  /**
    * A witness that a file resource has been created.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to save the file
    * @param attributes   the metadata of the file
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileCreated(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      storage: StorageReference,
      attributes: FileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the revision that this event generated
      */
    val rev: Long = 1L

    /**
      * the schema that has been used to constrain the resource
      */
    val schema: Ref = fileSchemaUri.ref

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  /**
    * A witness that a file digest has been updated.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to fetch the digest of the file
    * @param rev          the revision that this event generated
    * @param digest       the updated digest
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileDigestUpdated(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      storage: StorageReference,
      rev: Long,
      digest: Digest,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  /**
    * A witness that a file attributes has been updated.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to fetch the attributes of the file
    * @param rev          the revision that this event generated
    * @param attributes   the updated file attributes
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileAttributesUpdated(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      storage: StorageReference,
      rev: Long,
      attributes: StorageFileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  /**
    * A witness that a file resource has been updated.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param storage      the reference to the storage used to save the file
    * @param rev          the revision that this event generated
    * @param attributes   the metadata of the file
    * @param instant      the instant when this event was recorded
    * @param subject      the identity which generated this event
    */
  final case class FileUpdated(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      storage: StorageReference,
      rev: Long,
      attributes: FileAttributes,
      instant: Instant,
      subject: Subject
  ) extends Event {

    /**
      * the collection of known resource types
      */
    val types: Set[AbsoluteIri] = Set(nxv.File.value)
  }

  @nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
  object JsonLd {

    implicit private val config: Configuration = Configuration.default
      .withDiscriminator("@type")
      .copy(transformMemberNames = {
        case "id"           => "_resourceId"
        case "organization" => nxv.organizationUuid.prefix
        case "storage"      => "_storage"
        case "rev"          => nxv.rev.prefix
        case "instant"      => nxv.instant.prefix
        case "subject"      => "_subject"
        case "schema"       => nxv.constrainedBy.prefix
        case "attributes"   => "_attributes"
        case "source"       => "_source"
        case "types"        => "_types"
        case "bytes"        => nxv.bytes.prefix
        case "digest"       => nxv.digest.prefix
        case "algorithm"    => nxv.algorithm.prefix
        case "value"        => nxv.value.prefix
        case "filename"     => nxv.filename.prefix
        case "mediaType"    => nxv.mediaType.prefix
        case "uuid"         => nxv.uuid.prefix
        case "location"     => "_location"
        case "path"         => "_path"
        case other          => other
      })

    implicit private val refEncoder: Encoder[Ref]          = Encoder.encodeJson.contramap(_.iri.asJson)
    implicit private val refDecoder: Decoder[Ref]          = absoluteIriDecoder.map(Ref(_))
    implicit private val uriEncoder: Encoder[Uri]          = Encoder.encodeString.contramap(_.toString)
    implicit private val uriDecoder: Decoder[Uri]          = Decoder.decodeString.emapTry(s => Try(Uri(s)))
    implicit private val uriPathEncoder: Encoder[Uri.Path] = Encoder.encodeString.contramap(_.toString)
    implicit private val uriPathDecoder: Decoder[Uri.Path] = Decoder.decodeString.emapTry(s => Try(Uri.Path(s)))

    implicit private val storageReferenceEncoder: Encoder[StorageReference] = deriveConfiguredEncoder[StorageReference]
    implicit private val storageReferenceDecoder: Decoder[StorageReference] = deriveConfiguredDecoder[StorageReference]

    implicit private val digestEncoder: Encoder[Digest] = deriveConfiguredEncoder[Digest]
    implicit private val digestDecoder: Decoder[Digest] = deriveConfiguredDecoder[Digest]

    implicit private val digestStorageEncoder: Encoder[StorageDigest] = deriveConfiguredEncoder[StorageDigest]
    implicit private val digestStorageDecoder: Decoder[StorageDigest] = deriveConfiguredDecoder[StorageDigest]

    implicit private val fileAttributesEncoder: Encoder[FileAttributes] = deriveConfiguredEncoder[FileAttributes]
    implicit private val fileAttributesDecoder: Decoder[FileAttributes] = deriveConfiguredDecoder[FileAttributes]

    implicit private val storageFileAttributesEncoder: Encoder[StorageFileAttributes] =
      deriveConfiguredEncoder[StorageFileAttributes]
    implicit private val storageFileAttributesDecoder: Decoder[StorageFileAttributes] =
      deriveConfiguredDecoder[StorageFileAttributes]

    implicit private val idEncoder: Encoder[Id[ProjectRef]] =
      Encoder.encodeJson.contramap { id =>
        Json.obj("_resourceId" -> id.value.asJson, nxv.projectUuid.prefix -> id.parent.id.toString.asJson)
      }

    implicit private val idDecoder: Decoder[Id[ProjectRef]] =
      Decoder.forProduct2[Id[ProjectRef], AbsoluteIri, ProjectRef]("_resourceId", nxv.projectUuid.prefix) {
        case (value, project) => Id(project, value)
      }

    @nowarn("cat=unused")
    implicit private def subjectIdEncoder(implicit http: HttpConfig): Encoder[Subject] =
      Encoder.encodeJson.contramap(_.id.asJson)

    implicit private def subjectIdDecoder: Decoder[Subject] =
      Decoder.decodeString.emap { s =>
        Iri
          .absolute(s)
          .flatMap(iri =>
            Identity(iri) match {
              case Some(subject: Subject) => Right(subject)
              case Some(identity)         => Left(s"Identity '$identity' is not a subject")
              case None                   => Left(s"Url '$iri' cannot be converted to a subject")
            }
          )
      }

    implicit def eventsEventEncoder(implicit http: HttpConfig): Encoder[Event] = {
      val enc = deriveConfiguredEncoder[Event]
      Encoder.encodeJson.contramap[Event] { ev =>
        enc(ev).addContext(resourceCtxUri).removeKeys("_resourceId", nxv.projectUuid.prefix) deepMerge ev.id.asJson
      }
    }

    implicit val eventsEventDecoder: Decoder[Event] = {
      val dec = deriveConfiguredDecoder[Event]
      Decoder.instance { hc =>
        for {
          id      <- idDecoder(hc)
          idJson   = Json.obj("_resourceId" -> id.asJson)
          restJson = hc.value.removeKeys("_resourceId", nxv.projectUuid.prefix)
          event   <- dec(restJson.deepMerge(idJson).hcursor)
        } yield event
      }

    }
  }
}
